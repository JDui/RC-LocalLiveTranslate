package com.jd.rc.localtranslate.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jd.rc.localtranslate.core.SubtitleItem
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "session_captions", primaryKeys = ["sessionId", "segmentId"],
    foreignKeys = [ForeignKey(entity = SavedSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "startSample", "segmentId"])])
data class CaptionRow(val sessionId: String, val segmentId: String, val startSample: Long,
    val captionJson: String, val speakerId: String?, val speakerRevision: Int) {
    fun item(): SubtitleItem = CaptionJson.decodeItem(captionJson).copy(speakerId = speakerId, speakerRevision = speakerRevision)
    companion object {
        fun from(item: SubtitleItem) = CaptionRow(item.sessionId, item.segmentId, item.startSample,
            CaptionJson.encodeItem(item), item.speakerId, item.speakerRevision)
    }
}

@Dao
interface CaptionDao {
    @Upsert suspend fun save(rows: List<CaptionRow>)
    @Query("SELECT * FROM session_captions WHERE sessionId = :sessionId AND segmentId = :segmentId")
    suspend fun get(sessionId: String, segmentId: String): CaptionRow?
    @Query("SELECT * FROM session_captions WHERE sessionId = :id ORDER BY startSample, segmentId LIMIT :limit OFFSET :offset")
    suspend fun page(id: String, limit: Int, offset: Int): List<CaptionRow>
    @Query("SELECT * FROM session_captions WHERE sessionId = :id ORDER BY startSample, segmentId LIMIT :limit OFFSET :offset")
    fun observePage(id: String, limit: Int, offset: Int): Flow<List<CaptionRow>>
    @Query("SELECT * FROM session_captions WHERE sessionId = :id AND (startSample > :start OR (startSample = :start AND segmentId > :segment)) ORDER BY startSample, segmentId LIMIT :limit")
    suspend fun after(id: String, start: Long, segment: String, limit: Int): List<CaptionRow>
    @Query("UPDATE session_captions SET speakerId = :into, speakerRevision = MAX(speakerRevision, :revision) WHERE sessionId = :id AND speakerId = :from")
    suspend fun merge(id: String, from: String, into: String, revision: Int)
    @Query("SELECT COUNT(*) FROM session_captions WHERE sessionId = :id") suspend fun count(id: String): Int
}

@Database(entities = [SavedSession::class, CaptionRow::class], version = 2, exportSchema = false)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun captions(): CaptionDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS session_captions (sessionId TEXT NOT NULL, segmentId TEXT NOT NULL, startSample INTEGER NOT NULL, captionJson TEXT NOT NULL, speakerId TEXT, speakerRevision INTEGER NOT NULL, PRIMARY KEY(sessionId, segmentId), FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_session_captions_sessionId_startSample_segmentId ON session_captions(sessionId, startSample, segmentId)")
            }
        }
    }
}
