package com.jd.rc.localtranslate.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.runtime.PerformanceMode
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

private val Context.preferences by preferencesDataStore("settings")
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class UserSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val bilingual: Boolean = true,
    val fontSize: Float = 19f, val keepSessions: Boolean = true, val reduceTransparency: Boolean = false,
    val performanceMode: PerformanceMode = PerformanceMode.AUTO,
    val translationMode: TranslationMode = TranslationMode.TO_ZH, val sessionCity: String = "")

/** Only explicit user changes are written; a stale UI snapshot cannot restore unrelated settings. */
data class SettingsChange(val theme: ThemeMode? = null, val bilingual: Boolean? = null,
    val fontSize: Float? = null, val keepSessions: Boolean? = null, val reduceTransparency: Boolean? = null,
    val performanceMode: PerformanceMode? = null, val translationMode: TranslationMode? = null, val sessionCity: String? = null) {
    fun applyTo(current: UserSettings) = current.copy(theme = theme ?: current.theme,
        bilingual = bilingual ?: current.bilingual, fontSize = fontSize?.coerceIn(8f, 24f) ?: current.fontSize,
        keepSessions = keepSessions ?: current.keepSessions,
        reduceTransparency = reduceTransparency ?: current.reduceTransparency,
        performanceMode = performanceMode ?: current.performanceMode,
        translationMode = translationMode ?: current.translationMode,
        sessionCity = sessionCity?.let(SessionTitle::normalizeCity) ?: current.sessionCity)
}

class SettingsRepository(private val context: Context) {
    @Volatile private var keepIntent: Boolean? = null
    private val privacyEpoch = java.util.concurrent.atomic.AtomicLong()
    private val mutableRetentionEpoch = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val retentionChanges = mutableRetentionEpoch.asStateFlow()
    fun retentionEpoch(): Long = privacyEpoch.get()
    private val theme = stringPreferencesKey("theme")
    private val bilingual = booleanPreferencesKey("bilingual")
    private val font = floatPreferencesKey("font_size")
    private val keep = booleanPreferencesKey("keep_sessions")
    private val opaque = booleanPreferencesKey("reduce_transparency")
    private val city = stringPreferencesKey("session_city")
    private val translation = stringPreferencesKey("translation_mode")
    private val performance = stringPreferencesKey("performance_mode")
    val settings = context.preferences.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        UserSettings(ThemeMode.entries.find { mode -> mode.name == it[theme] } ?: ThemeMode.SYSTEM,
            it[bilingual] ?: true, (it[font] ?: 19f).coerceIn(8f, 24f), it[keep] ?: true, it[opaque] ?: false,
            PerformanceMode.entries.find { mode -> mode.name == it[performance] } ?: PerformanceMode.AUTO,
            TranslationMode.fromStored(it[translation]), SessionTitle.normalizeCity(it[city].orEmpty()))
    }
    suspend fun canKeepSessions(): Boolean {
        if (keepIntent == false) return false
        val persisted = try { context.preferences.data.first()[keep] ?: true }
        catch (_: IOException) { return false }
        return persisted && keepIntent != false
    }
    suspend fun update(change: SettingsChange) {
        // A disable request protects privacy even while the storage write is pending or fails.
        change.keepSessions?.let { keepIntent = it; if (!it) mutableRetentionEpoch.value = privacyEpoch.incrementAndGet() }
        context.preferences.edit {
            change.theme?.let { value -> it[theme] = value.name }
            change.bilingual?.let { value -> it[bilingual] = value }
            change.fontSize?.let { value -> it[font] = value.coerceIn(8f, 24f) }
            change.keepSessions?.let { value -> it[keep] = value }
            change.reduceTransparency?.let { value -> it[opaque] = value }
            change.sessionCity?.let { value -> it[city] = SessionTitle.normalizeCity(value) }
            change.translationMode?.let { value -> it[translation] = value.name }
            change.performanceMode?.let { value -> it[performance] = value.name }
        }
    }
    suspend fun update(value: UserSettings) = update(SettingsChange(value.theme, value.bilingual,
        value.fontSize, value.keepSessions, value.reduceTransparency, value.performanceMode, value.translationMode, value.sessionCity))

    suspend fun updateCityIfUnchanged(expected: String, value: String): Boolean {
        var changed = false
        context.preferences.edit {
            if (SessionTitle.normalizeCity(it[city].orEmpty()) == expected) {
                it[city] = SessionTitle.normalizeCity(value)
                changed = true
            }
        }
        return changed
    }
}

@Entity(tableName = "sessions")
data class SavedSession(@PrimaryKey val id: String, val title: String, val createdAt: Long,
    val durationSeconds: Long, val isDemo: Boolean, val captionsJson: String)
@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC") fun observe(): Flow<List<SavedSession>>
    @Query("SELECT id, title, createdAt, durationSeconds, isDemo, '[]' AS captionsJson FROM sessions ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observeSummaries(limit: Int, offset: Int): Flow<List<SavedSession>>
    @Query("SELECT COUNT(*) FROM sessions") fun observeCount(): Flow<Int>
    @Query("SELECT id, title, createdAt, durationSeconds, isDemo, '[]' AS captionsJson FROM sessions WHERE id = :id")
    suspend fun metadata(id: String): SavedSession?
    @Query("SELECT length(captionsJson) FROM sessions WHERE id = :id") suspend fun legacyLength(id: String): Long?
    @Query("SELECT substr(captionsJson, :start, :length) FROM sessions WHERE id = :id")
    fun legacySlice(id: String, start: Long, length: Int): String?
    @Query("UPDATE sessions SET captionsJson = '[]' WHERE id = :id") suspend fun clearLegacy(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun create(session: SavedSession)
    @Query("UPDATE sessions SET durationSeconds = MAX(durationSeconds, :seconds) WHERE id = :id")
    suspend fun duration(id: String, seconds: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(session: SavedSession)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE sessions SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)
}


object CaptionJson {
    fun encode(items: List<SubtitleItem>): String = JSONArray().apply { items.forEach { put(objectFor(it)) } }.toString(2)
    fun encodeItem(item: SubtitleItem): String = objectFor(item).toString()
    private fun objectFor(item: SubtitleItem): JSONObject = JSONObject().apply {
        put("sessionId", item.sessionId); put("segmentId", item.segmentId)
        put("startSample", item.startSample); put("endSample", item.endSample); put("revision", item.revision)
        put("language", item.sourceLanguage.name); put("source", item.sourceText); put("target", item.targetText)
        put("speaker", item.speakerId ?: JSONObject.NULL); put("stage", item.stage.name)
        put("targetLanguage", item.targetLanguage?.name ?: JSONObject.NULL)
        put("translationStatus", item.translationStatus.name)
        put("speakerRevision", item.speakerRevision); put("translationRevision", item.translationRevision)
    }
    fun decode(value: String): List<SubtitleItem> {
        val array = JSONArray(value)
        return (0 until array.length()).map { decodeObject(array.getJSONObject(it)) }
    }
    fun decodeItem(value: String): SubtitleItem = decodeObject(JSONObject(value))
    fun decodeObject(value: JSONObject): SubtitleItem = value.let {
        SubtitleItem(it.getString("sessionId"), it.getString("segmentId"), it.getLong("startSample"),
            it.getLong("endSample"), it.getInt("revision"), SourceLanguage.valueOf(it.getString("language")),
            it.getString("source"), it.getString("target"), if (it.isNull("speaker")) null else it.getString("speaker"),
            CaptionStage.valueOf(it.getString("stage")), TranslationStatus.valueOf(it.getString("translationStatus")),
            it.optInt("speakerRevision", -1), it.optInt("translationRevision", -1),
            if (it.has("targetLanguage")) { if (it.isNull("targetLanguage")) null else SourceLanguage.valueOf(it.getString("targetLanguage")) }
            else TranslationMode.TO_ZH.target(SourceLanguage.valueOf(it.getString("language"))))
    }
}
