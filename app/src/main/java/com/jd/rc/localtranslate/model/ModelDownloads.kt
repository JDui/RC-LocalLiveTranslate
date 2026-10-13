package com.jd.rc.localtranslate.model

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class DownloadFile(val path: String, val size: Long, val sha256: String, val url: String)
internal data class DownloadModel(val id: String, val files: List<DownloadFile>)

/** Fixed public weights only. No recordings, captions, or device identifiers leave the app. */
internal class ModelDownloads(private val context: Context) {
    fun catalog(): List<DownloadModel> {
        val array = JSONArray(context.assets.open("model-downloads/catalog.json").bufferedReader().use { it.readText() })
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val files = item.getJSONArray("files")
            DownloadModel(item.getString("id"), (0 until files.length()).map { i ->
                val file = files.getJSONObject(i)
                DownloadFile(file.getString("path"), file.getLong("size"), file.getString("sha256"), file.getString("url"))
            })
        }
    }

    suspend fun prepare(model: DownloadModel, folder: File, progress: (Long) -> Unit): File {
        val downloaded = File(folder, model.id).apply { mkdirs() }
        for (file in model.files) {
            currentCoroutineContext().ensureActive()
            var url = URL(file.url)
            var connection: HttpURLConnection? = null
            try {
                for (redirect in 0..8) {
                    require(url.protocol == "https" && url.userInfo == null) { "下载只允许 HTTPS。" }
                    val next = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 10_000
                        instanceFollowRedirects = false
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    connection = next
                    currentCoroutineContext().ensureActive()
                    val status = next.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(redirect < 8) { "下载重定向次数过多。" }
                        val location = next.getHeaderField("Location") ?: error("下载地址不可用。")
                        url = URL(url, location); next.disconnect(); connection = null
                        continue
                    }
                    require(status == 200) { "下载失败（HTTP $status），可重试或离线导入。" }
                    val length = next.contentLengthLong
                    require(length == -1L || length == file.size) { "下载文件大小不符。" }
                    next.inputStream.use { input ->
                        copyVerified(input, safeModelFile(downloaded, file.path), file.size, file.sha256, progress)
                    }
                    break
                }
            } finally { connection?.disconnect() }
        }
        val archive = File(folder, "${model.id}.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.setLevel(0)
            suspend fun add(path: String, open: () -> InputStream) {
                zip.putNextEntry(ZipEntry(path))
                open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
            }
            for (file in model.files) {
                currentCoroutineContext().ensureActive()
                add(file.path) { safeModelFile(downloaded, file.path).inputStream() }
            }
            addAssetTree(zip, "model-downloads/${model.id}", "")
        }
        return archive
    }

    private suspend fun addAssetTree(zip: ZipOutputStream, root: String, relative: String) {
        for (name in context.assets.list(if (relative.isEmpty()) root else "$root/$relative").orEmpty()) {
            currentCoroutineContext().ensureActive()
            val path = if (relative.isEmpty()) name else "$relative/$name"
            val children = context.assets.list("$root/$path").orEmpty()
            if (children.isNotEmpty()) addAssetTree(zip, root, path) else {
                zip.putNextEntry(ZipEntry(path))
                context.assets.open("$root/$path").use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}

internal suspend fun copyVerified(input: InputStream, target: File, size: Long, sha256: String, progress: (Long) -> Unit) {
    require(size in 1..512L * 1024 * 1024 && sha256.matches(Regex("[a-f0-9]{64}")))
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    target.parentFile?.mkdirs()
    try {
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= size) { "下载文件超过登记大小。" }
                digest.update(buffer, 0, read); output.write(buffer, 0, read); progress(read.toLong())
            }
        }
        require(count == size && digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "下载文件校验失败，请重试。" }
    } catch (error: Throwable) { target.delete(); throw error }
}
