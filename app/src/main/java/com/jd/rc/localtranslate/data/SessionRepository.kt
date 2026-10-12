package com.jd.rc.localtranslate.data

import android.util.JsonReader
import android.util.JsonToken
import androidx.room.withTransaction
import com.jd.rc.localtranslate.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Reader
import java.io.Writer

const val SESSION_PAGE_SIZE = 50
const val CAPTION_PAGE_SIZE = 200
data class SessionCaptionPage(val items: List<SubtitleItem>, val hasMore: Boolean)

class SessionRepository(private val db: SessionDatabase) {
    private val sessions = db.sessions()
    private val captions = db.captions()
    private val legacyMutex = Mutex()

    /** SQLite substr counts code points; Reader consumes UTF-16 units without splitting a page offset. */
    private class LegacyReader(private val dao: SessionDao, private val id: String, private val length: Long) : Reader() {
        private var sqlOffset = 1L
        private var page = ""
        private var offset = 0
        private var closed = false
        override fun read(buffer: CharArray, start: Int, count: Int): Int {
            check(!closed)
            if (count == 0) return 0
            if (offset == page.length) {
                if (sqlOffset > length) return -1
                page = dao.legacySlice(id, sqlOffset, 16_384) ?: error("会话已删除。")
                sqlOffset += 16_384; offset = 0
                if (page.isEmpty()) return -1
            }
            val size = minOf(count, page.length - offset)
            page.toCharArray(buffer, start, offset, offset + size)
            offset += size
            return size
        }
        override fun close() { closed = true; page = "" }
    }

    private fun readItem(reader: JsonReader): SubtitleItem {
        val value = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.NULL -> { reader.nextNull(); value.put(name, JSONObject.NULL) }
                JsonToken.STRING -> value.put(name, reader.nextString())
                JsonToken.NUMBER -> value.put(name, reader.nextString().let { it.toLongOrNull() ?: it.toDouble() })
                JsonToken.BOOLEAN -> value.put(name, reader.nextBoolean())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CaptionJson.decodeObject(value)
    }

    /** Legacy blobs stay intact until all bounded batches have converted successfully. */
    suspend fun normalize(id: String) = withContext(Dispatchers.IO) {
        legacyMutex.withLock {
            val length = sessions.legacyLength(id) ?: error("会话已删除。")
            if (length <= 2) return@withLock
            val batch = ArrayList<CaptionRow>(100)
            JsonReader(LegacyReader(sessions, id, length)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val item = readItem(reader).copy(sessionId = id)
                    batch += CaptionRow.from(item)
                    if (batch.size == 100) { captions.save(batch.toList()); batch.clear() }
                }
                reader.endArray()
                check(reader.peek() == JsonToken.END_DOCUMENT) { "记录格式不完整。" }
            }
            if (batch.isNotEmpty()) captions.save(batch)
            sessions.clearLegacy(id)
        }
    }

    suspend fun page(id: String, page: Int): SessionCaptionPage = withContext(Dispatchers.IO) {
        require(page >= 0 && page <= Int.MAX_VALUE / CAPTION_PAGE_SIZE)
        normalize(id)
        val rows = captions.page(id, CAPTION_PAGE_SIZE + 1, page * CAPTION_PAGE_SIZE)
        SessionCaptionPage(rows.take(CAPTION_PAGE_SIZE).map { it.item() }, rows.size > CAPTION_PAGE_SIZE)
    }

    fun observePage(id: String, page: Int): Flow<SessionCaptionPage> = flow {
        require(page >= 0 && page <= Int.MAX_VALUE / CAPTION_PAGE_SIZE)
        normalize(id)
        emitAll(captions.observePage(id, CAPTION_PAGE_SIZE + 1, page * CAPTION_PAGE_SIZE).map { rows ->
            SessionCaptionPage(rows.take(CAPTION_PAGE_SIZE).map { it.item() }, rows.size > CAPTION_PAGE_SIZE)
        })
    }.flowOn(Dispatchers.IO)

    suspend fun saveDemo(session: SavedSession, items: List<SubtitleItem>, settings: SettingsRepository? = null, acceptedEpoch: Long = settings?.retentionEpoch() ?: 0) = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (settings != null && (!settings.canKeepSessions() || settings.retentionEpoch() != acceptedEpoch)) return@withTransaction
            sessions.create(session.copy(captionsJson = "[]"))
            sessions.duration(session.id, session.durationSeconds)
            items.chunked(100).forEach { captions.save(it.map(CaptionRow::from)) }
            if (settings != null && (!settings.canKeepSessions() || settings.retentionEpoch() != acceptedEpoch)) sessions.delete(session.id)
        }
    }

    /** Export one database page at a time; no whole-session JSON/text string is built. */
    suspend fun export(id: String, format: String, writer: Writer) = withContext(Dispatchers.IO) {
        normalize(id)
        var start = -1L; var segment = ""; var index = 0
        if (format == "json") writer.write("[")
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = captions.after(id, start, segment, CAPTION_PAGE_SIZE)
            if (rows.isEmpty()) break
            for (row in rows) {
                val item = row.item()
                when (format) {
                    "json" -> { if (index > 0) writer.write(","); writer.write(CaptionJson.encodeItem(item)) }
                    "srt" -> { if (index > 0) writer.write("\n\n"); writer.write(SessionExport.srtEntry(index, item)) }
                    else -> { if (index > 0) writer.write("\n\n"); writer.write(SessionExport.text(listOf(item))) }
                }
                index++
            }
            start = rows.last().startSample; segment = rows.last().segmentId
        }
        if (format == "json") writer.write("]")
        if (format == "srt") writer.write("\n")
    }
}
