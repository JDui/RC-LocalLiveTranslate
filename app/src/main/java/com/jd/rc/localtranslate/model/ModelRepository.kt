package com.jd.rc.localtranslate.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class ModelSlot(val id: String, val title: String, val description: String)
val modelSlots = listOf(
    ModelSlot("sensevoice", "SenseVoice Small", "中 / 英 / 日 / 韩 / 粤 · 语音识别与语言检测"),
    ModelSlot("silero-vad", "Silero VAD", "语音活动检测"),
    ModelSlot("speaker", "Speaker Embedding", "会话内说话人分离"),
    ModelSlot("hy-mt2", "Hy-MT2 1.8B · 1.25-bit", "中 / 英 / 日 / 韩 / 粤互译"),
)
data class InstalledModel(val id: String, val version: String, val license: String,
    val source: String, val sizeBytes: Long)
data class ModelState(val installed: List<InstalledModel> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val inUse: Boolean = false, val downloading: Boolean = false,
    val downloadedBytes: Long = 0, val downloadTotalBytes: Long = 0)

data class ModelBundle(val id: String, val directory: File, val files: Map<String, File>, val hashes: Map<String, String>) {
    fun required(name: String): File = files[name]?.takeIf { it.isFile && it.length() > 0 }
        ?: error("$id 缺少推理文件：$name")
}

class ModelLease internal constructor(val bundles: Map<String, ModelBundle>, private val release: suspend () -> Unit) {
    private val mutex = Mutex()
    private var closed = false
    suspend fun close() = withContext(NonCancellable) { mutex.withLock { if (!closed) { release(); closed = true } } }
}

class ModelRepository(private val context: Context) {
    private val root = File(context.filesDir, "models")
    private val mutex = Mutex()
    private var leases = 0
    private val mutable = MutableStateFlow(ModelState())
    val state = mutable.asStateFlow()
    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("model-download-") }?.forEach { it.deleteRecursively() }
        refreshLocked()
    } }
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock { importLocked({ context.contentResolver.openInputStream(uri) }) }
    }
    suspend fun downloadMissing() = withContext(Dispatchers.IO) { mutex.withLock {
        if (leases > 0) {
            mutable.value = mutable.value.copy(message = "请先停止会话，再下载模型。")
            return@withLock
        }
        refreshLocked()
        val downloader = ModelDownloads(context)
        val missing = downloader.catalog().filter { model -> mutable.value.installed.none { it.id == model.id } }
        if (missing.isEmpty()) return@withLock
        val folder = File(context.cacheDir, "model-download-${UUID.randomUUID()}").apply { mkdirs() }
        val total = missing.sumOf { it.files.sumOf { file -> file.size } }
        var bytes = 0L
        var lastUpdate = 0L
        mutable.value = mutable.value.copy(busy = true, downloading = true, downloadedBytes = 0,
            downloadTotalBytes = total, message = "正在下载缺失模型…")
        try {
            check(folder.usableSpace > 2L * 1024 * 1024 * 1024) { "请至少预留 2 GiB 存储空间，或从本地导入。" }
            for (model in missing) {
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(busy = true, message = "正在下载：${modelSlots.first { it.id == model.id }.title}")
                val archive = downloader.prepare(model, folder) { added ->
                    bytes += added
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 200 || bytes == total) {
                        mutable.value = mutable.value.copy(downloadedBytes = bytes); lastUpdate = now
                    }
                }
                mutable.value = mutable.value.copy(message = "正在校验与安装模型…")
                importLocked({ archive.inputStream() }, model.id)
                check(mutable.value.installed.any { it.id == model.id }) { mutable.value.message ?: "下载模型安装失败。" }
                File(folder, model.id).deleteRecursively(); archive.delete()
            }
            refreshLocked("模型已安装，可离线使用。")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            mutable.value = mutable.value.copy(message = "下载已取消，已安装模型保留。")
            throw cancelled
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(message = if (error is IllegalArgumentException || error is IllegalStateException)
                error.message ?: "下载校验失败，请重试。" else "下载失败，请检查网络与存储空间，或从本地导入。")
        } finally {
            folder.deleteRecursively()
            mutable.value = mutable.value.copy(busy = false, downloading = false)
        }
    } }
    suspend fun installBundledModels() {
        val assets = context.assets.list("bundled-models").orEmpty().toSet()
        if (assets.isEmpty()) return
        installBundled(modelSlots.associate { slot ->
            slot.id to { context.assets.open("bundled-models/${slot.id}.zip") }
        })
    }
    internal suspend fun installBundled(packages: Map<String, () -> InputStream>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (leases > 0) return@withLock
            refreshLocked()
            try {
                for ((id, open) in packages) {
                    require(modelSlots.any { it.id == id })
                    if (mutable.value.installed.any { it.id == id }) continue
                    importLocked(open, id)
                    check(mutable.value.installed.any { it.id == id }) { mutable.value.message ?: "内置模型安装失败。" }
                }
                refreshLocked("内置模型已就绪。")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "内置模型安装失败，请释放存储空间后重新启动。") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private suspend fun importLocked(open: () -> InputStream?, expectedId: String? = null) {
        if (leases > 0) {
            mutable.value = mutable.value.copy(message = "模型正在使用，请先停止会话再导入。")
            return
        }
        mutable.value = mutable.value.copy(busy = true, message = if (expectedId == null) null else "正在安装内置模型：$expectedId")
        root.mkdirs()
        val stage = File(root, ".import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var total = 0L
            val names = mutableSetOf<String>()
            open()?.use { input -> ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    require(names.size < 128) { "模型包文件数量超限。" }
                    require(names.add(entry.name)) { "模型包含重复文件。" }
                    val target = safeModelFile(stage, entry.name)
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var fileSize = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileSize += count; total += count
                                require(fileSize <= 512L * 1024 * 1024 && total <= 1024L * 1024 * 1024) { "模型包超过大小限制。" }
                                if (entry.name == "manifest.json") require(fileSize <= 64 * 1024) { "模型清单过大。" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            } } ?: error("无法读取所选文件。")
            val manifestFile = File(stage, "manifest.json")
            require(manifestFile.isFile && manifestFile.length() <= 64 * 1024) { "模型包缺少 manifest.json。" }
            val manifest = JSONObject(manifestFile.readText())
            val id = manifest.getString("id")
            require(expectedId == null || expectedId == id) { "内置模型 ID 不匹配。" }
            require(modelSlots.any { it.id == id }) { "不支持的模型 ID。" }
            require(manifest.getString("version").isNotBlank() && manifest.getString("license").isNotBlank() &&
                manifest.getString("sourceURL").isNotBlank()) { "清单缺少版本、许可或来源。" }
            require(manifest.getString("abi") == "arm64-v8a") { "模型包必须声明 arm64-v8a。" }
            val files = manifest.getJSONArray("files")
            require(files.length() in 1..64) { "模型清单必须包含文件。" }
            val declared = mutableSetOf("manifest.json")
            for (i in 0 until files.length()) {
                currentCoroutineContext().ensureActive()
                val spec = files.getJSONObject(i)
                val path = spec.getString("path")
                require(declared.add(path)) { "清单文件重复。" }
                val file = safeModelFile(stage, path)
                val expected = spec.getString("sha256").lowercase()
                require(expected.matches(Regex("[a-f0-9]{64}"))) { "无效的 SHA-256。" }
                require(file.isFile && file.length() == spec.getLong("size")) { "文件缺失或大小不符：$path" }
                // Model integrity is a required security check, never a source-code change check.
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "SHA-256 校验失败：$path" }
            }
            require(stage.walkTopDown().filter { it.isFile }.all { it.relativeTo(stage).invariantSeparatorsPath in declared }) {
                "模型包包含未登记文件。"
            }
            if (id == "hy-mt2") HyModelProfile.validate(manifest)
            val installed = File(root, id)
            val backup = File(root, ".backup-$id")
            if (backup.exists()) {
                if (!installed.exists()) check(backup.renameTo(installed)) else backup.deleteRecursively()
            }
            if (installed.exists()) check(installed.renameTo(backup)) { "无法替换旧模型。" }
            if (!stage.renameTo(installed)) {
                if (backup.exists()) backup.renameTo(installed)
                error("无法安装模型，旧版本已保留。")
            }
            backup.deleteRecursively()
            refreshLocked("模型文件已校验。")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(message = error.message ?: "模型导入失败。")
        } finally {
            stage.deleteRecursively()
            mutable.value = mutable.value.copy(busy = mutable.value.downloading)
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(modelSlots.any { it.id == id })
        check(leases == 0) { "模型正在使用，请先停止会话。" }
        val deleted = File(root, id).deleteRecursively()
        refreshLocked(if (deleted) "模型已删除。" else "无法删除模型。")
    } }
    private fun refreshLocked(message: String? = null) {
        root.mkdirs()
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
        val models = modelSlots.mapNotNull { slot ->
            val installed = File(root, slot.id)
            val backup = File(root, ".backup-${slot.id}")
            if (!installed.exists() && backup.exists()) backup.renameTo(installed)
            runCatching {
                val json = JSONObject(File(installed, "manifest.json").readText())
                InstalledModel(slot.id, json.getString("version"), json.getString("license"),
                    json.getString("sourceURL"), installed.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }.getOrNull()
        }
        mutable.value = mutable.value.copy(installed = models, message = message, inUse = leases > 0)
    }
    suspend fun acquire(): ModelLease {
        var acquired: ModelLease? = null
        try { return withContext(Dispatchers.IO) { mutex.withLock {
        refreshLocked()
        val bundles = mutable.value.installed.associate { installed ->
            val dir = File(root, installed.id)
            val manifest = JSONObject(File(dir, "manifest.json").readText())
            if (installed.id == "hy-mt2") HyModelProfile.validate(manifest)
            val specs = manifest.getJSONArray("files")
            val files = linkedMapOf<String, File>(); val hashes = linkedMapOf<String, String>()
            for (i in 0 until specs.length()) {
                val spec = specs.getJSONObject(i); val path = spec.getString("path")
                val file = safeModelFile(dir, path)
                require(file.isFile && file.length() == spec.getLong("size")) { "模型文件已损坏：$path" }
                files[path] = file; hashes[file.name] = spec.getString("sha256")
            }
            installed.id to ModelBundle(installed.id, dir, files, hashes)
        }
        listOf("sensevoice", "silero-vad", "speaker").forEach { check(it in bundles) { "请先安装 $it 模型。" } }
        bundles.getValue("sensevoice").required("model.onnx"); bundles.getValue("sensevoice").required("tokens.txt")
        bundles.getValue("silero-vad").required("silero_vad.onnx"); bundles.getValue("speaker").required("speaker.onnx")
        leases++
        mutable.value = mutable.value.copy(inUse = true)
        ModelLease(bundles) { mutex.withLock { leases--; mutable.value = mutable.value.copy(inUse = leases > 0) } }.also { acquired = it }
        } } } catch (error: Throwable) { acquired?.close(); throw error }
    }
}

internal fun safeModelFile(root: File, path: String): File {
    require(path.isNotBlank() && !path.contains('\\') && !path.contains(':') && !path.startsWith('/') &&
        path.split('/').none { it == ".." || it == "." }) { "模型包包含非法路径。" }
    val file = File(root, path).canonicalFile
    require(file.path.startsWith(root.canonicalPath + File.separator)) { "模型包包含越界路径。" }
    return file
}
