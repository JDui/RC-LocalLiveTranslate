package com.jd.rc.localtranslate

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jd.rc.localtranslate.core.*
import com.jd.rc.localtranslate.data.*
import com.jd.rc.localtranslate.inference.ConversationStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TranslateApplication::class)
class SessionStorageTest {
    private val app get() = ApplicationProvider.getApplicationContext<TranslateApplication>()
    private fun database() = Room.inMemoryDatabaseBuilder(app, SessionDatabase::class.java).build()
    private fun metadata(id: String, time: Long = 1) = SavedSession(id, "Session $id", time, 0, false, "[]")
    private fun item(id: String, index: Int) = SubtitleItem(id, "row-$index", index * 16_000L, (index + 1) * 16_000L,
        1, SourceLanguage.EN, "Line $index", "", "B", CaptionStage.FINAL)
    private fun transcript(id: String, index: Int) = item(id, index).let {
        SubtitleEvent.Transcript(id, it.segmentId, 1, it.startSample, it.endSample, it.sourceLanguage, it.sourceText, it.stage)
    }

    @Test fun migrationKeepsLegacyBlobAndStreamsUnicodePagesAndAllExportFormats() = runBlocking {
        val path = app.getDatabasePath("migration-maintenance.db")
        path.parentFile!!.mkdirs(); app.deleteDatabase(path.name)
        val expected = (0 until 1105).map { item("legacy", it).copy(sourceText = "\uD83D\uDE80日本語 $it " + "x".repeat(30)) }
        val oldJson = CaptionJson.encode(expected)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, durationSeconds INTEGER NOT NULL, isDemo INTEGER NOT NULL, captionsJson TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions VALUES (?, ?, ?, ?, ?, ?)", arrayOf<Any>("legacy", "Original name", 1, 1105, 0, oldJson))
            db.version = 1
        }
        val db = Room.databaseBuilder(app, SessionDatabase::class.java, path.name).addMigrations(SessionDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(oldJson, db.sessions().observe().first().single().captionsJson)
            assertEquals("[]", db.sessions().observeSummaries(50, 0).first().single().captionsJson)
            val repository = SessionRepository(db)
            val first = repository.page("legacy", 0)
            assertEquals(expected.take(200), first.items); assertTrue(first.hasMore)
            assertEquals(expected.takeLast(105), repository.page("legacy", 5).items)
            assertFalse(repository.page("legacy", 5).hasMore)
            assertEquals(1105, db.captions().count("legacy"))
            assertEquals(2L, db.sessions().legacyLength("legacy"))
            val text = StringWriter(); repository.export("legacy", "txt", text)
            val srt = StringWriter(); repository.export("legacy", "srt", srt)
            val json = StringWriter(); repository.export("legacy", "json", json)
            assertEquals(SessionExport.text(expected), text.toString())
            assertEquals(SessionExport.srt(expected), srt.toString())
            assertEquals(expected, CaptionJson.decode(json.toString()))
            db.sessions().rename("legacy", "Renamed")
            assertEquals("Renamed", db.sessions().metadata("legacy")!!.title)
            db.sessions().delete("legacy"); assertEquals(0, db.captions().count("legacy"))
        } finally { db.close(); app.deleteDatabase(path.name) }
    }

    @Test fun legacyLargerThanCursorWindowCanBeReadAndPagingRemainsBounded() = runBlocking {
        val db = database()
        try {
            val rows = (0 until 800).map { item("large", it).copy(sourceText = "\uD83D\uDE80" + "x".repeat(3000)) }
            val json = CaptionJson.encode(rows)
            assertTrue(json.length > 2_097_152)
            db.sessions().save(metadata("large").copy(captionsJson = json))
            assertEquals("[]", db.sessions().observeSummaries(50, 0).first().single().captionsJson)
            val repository = SessionRepository(db)
            assertEquals(rows.take(200), repository.page("large", 0).items)
            assertEquals(rows.takeLast(200), repository.page("large", 3).items)
            assertEquals(800, db.captions().count("large"))
            assertEquals(2L, db.sessions().legacyLength("large"))
        } finally { db.close() }
    }

    @Test fun legacyMissingFieldsAndOwningRecordIdRemainCompatible() = runBlocking {
        val db = database()
        try {
            val legacy = org.json.JSONObject(CaptionJson.encodeItem(item("old-inner-id", 0)))
            legacy.remove("speakerRevision"); legacy.remove("translationRevision"); legacy.remove("targetLanguage")
            db.sessions().save(metadata("owner").copy(captionsJson = "[$legacy]"))
            val normalized = SessionRepository(db).page("owner", 0).items.single()
            assertEquals("owner", normalized.sessionId)
            assertEquals(SourceLanguage.ZH, normalized.targetLanguage)
            assertEquals(-1, normalized.speakerRevision); assertEquals(-1, normalized.translationRevision)
        } finally { db.close() }
    }

    @Test fun malformedLegacyRecordRemainsIntactOnConversionFailure() = runBlocking {
        val db = database()
        try {
            val broken = CaptionJson.encode((0 until 101).map { item("broken", it) }).dropLast(1)
            db.sessions().save(metadata("broken").copy(captionsJson = broken))
            assertTrue(runCatching { SessionRepository(db).page("broken", 0) }.isFailure)
            assertEquals(broken, db.sessions().observe().first().single().captionsJson)
        } finally { db.close() }
    }

    @Test fun summariesHaveStableBoundedPaginationWithoutCaptionBlobs() = runBlocking {
        val db = database()
        try {
            repeat(125) { db.sessions().save(metadata("$it", it.toLong()).copy(captionsJson = "x".repeat(10000))) }
            assertEquals(125, db.sessions().observeCount().first())
            val pages = (0..2).map { db.sessions().observeSummaries(50, it * 50).first() }
            assertEquals(listOf(50, 50, 25), pages.map { it.size })
            assertEquals(125, pages.flatten().map { it.id }.toSet().size)
            assertTrue(pages.flatten().all { it.captionsJson == "[]" })
            assertEquals("124", pages.first().first().id)
        } finally { db.close() }
    }

    @Test fun detailObservationUpdatesLateTranslationWithinBoundedPage() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = SessionRepository(db)
            repository.saveDemo(metadata("observed"), (0 until 205).map { item("observed", it) })
            val ready = CompletableDeferred<Unit>(); val updated = CompletableDeferred<SessionCaptionPage>()
            val observer = parent.launch {
                repository.observePage("observed", 0).collect { page ->
                    ready.complete(Unit)
                    if (page.items.first().targetText == "Late update") updated.complete(page)
                }
            }
            withTimeout(5000) { ready.await() }
            db.captions().save(listOf(CaptionRow.from(item("observed", 0).copy(targetText = "Late update", translationStatus = TranslationStatus.COMPLETE))))
            val page = withTimeout(5000) { updated.await() }
            assertEquals(200, page.items.size); assertTrue(page.hasMore)
            observer.cancelAndJoin()
        } finally { parent.cancel(); db.close() }
    }

    @Test fun longJournalKeepsAllRowsAndUpdatesEvictedTranslationsAndSpeakerAliases() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val store = ConversationStore(); store.begin("long")
        val recorder = SessionRecorder(db, app.settings, metadata("long"), TranslationMode.TO_ZH, parent)
        store.attachJournal("long", recorder)
        try {
            withTimeout(30_000) {
                repeat(1200) {
                    store.publish(transcript("long", it))
                    store.publish(SubtitleEvent.Speaker("long", "row-$it", 1, "B"))
                }
                assertEquals(500, store.state.value.captions.size)
                assertTrue(store.state.value.liveWindowTrimmed)
                assertEquals("row-700", store.state.value.captions.first().segmentId)
                store.publishMerge("long", SpeakerMerge("B", "A", 2))
                store.publish(SubtitleEvent.Speaker("long", "row-0", 5, "B"))
                store.publish(SubtitleEvent.Translation("long", "row-0", 1, "迟到的译文"))
                store.publish(SubtitleEvent.Translation("long", "row-0", 0, "过期译文"))
                recorder.finish(1200)
            }
            assertEquals(1200, db.captions().count("long"))
            val old = db.captions().get("long", "row-0")!!.item()
            assertEquals("迟到的译文", old.targetText); assertEquals("A", old.speakerId)
            assertEquals(5, old.speakerRevision)
            assertEquals("A", db.captions().get("long", "row-1199")!!.speakerId)
            assertEquals(1200L, db.sessions().metadata("long")!!.durationSeconds)
            assertFalse(store.state.value.captions.any { it.segmentId == "row-0" })
        } finally { parent.cancel(); db.close() }
    }

    @Test fun realTrackerMergeUpdatesPersistedCaptionsOlderThanEvidenceAndLiveWindows() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val tracker = SpeakerTracker(); val store = ConversationStore(); store.begin("s")
        val recorder = SessionRecorder(db, app.settings, metadata("s"), TranslationMode.TO_ZH, parent)
        store.attachJournal("s", recorder)
        var index = 0
        suspend fun observe(degrees: Int) {
            val id = "${index++}"
            store.publish(SubtitleEvent.Transcript("s", id, 0, index * 32000L, index * 32000L + 32000,
                SourceLanguage.EN, "source-$id", CaptionStage.FINAL))
            val radians = Math.toRadians(degrees.toDouble())
            val assignments = tracker.observe(id, floatArrayOf(kotlin.math.cos(radians).toFloat(), kotlin.math.sin(radians).toFloat()), 2f)
            tracker.drainMerges().forEach { store.publishMerge("s", it) }
            assignments.forEach { store.publish(SubtitleEvent.Speaker("s", it.segmentId, it.revision, it.speakerId)) }
        }
        try {
            withTimeout(30_000) {
                repeat(2) { observe(0) }; repeat(2) { observe(100) }
                assertEquals("B", store.state.value.captions.first { it.segmentId == "2" }.speakerId)
                repeat(64) { observe(30); observe(70) }
                repeat(64) { observe(35); observe(65) }
                repeat(256) { observe(38); observe(62) }
                recorder.finish(index * 2L)
            }
            assertTrue(index > tracker.retainedEvidenceCount)
            val old = db.captions().get("s", "2")!!.item()
            assertEquals("A", old.speakerId); assertEquals("source-2", old.sourceText)
            assertEquals(96000L, old.startSample); assertEquals(0, old.revision)
            assertEquals(2, tracker.allocatedSpeakerCount)
            assertEquals(index, db.captions().count("s"))
            assertEquals(500, store.state.value.captions.size)
            assertFalse(store.state.value.captions.any { it.segmentId == "2" })
        } finally { parent.cancel(); db.close() }
    }

    @Test fun privacyDisablePurgesIdleRecordingAndReenableCannotRestoreQueuedOldContent() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val recorder = SessionRecorder(db, app.settings, metadata("privacy"), TranslationMode.TO_ZH, parent)
        try {
            repeat(180) { recorder.append(transcript("privacy", it)) }
            withTimeout(5000) { while (db.captions().count("privacy") == 0) delay(10) }
            app.settings.update(SettingsChange(keepSessions = false))
            withTimeout(5000) { while (db.sessions().metadata("privacy") != null) delay(10) }
            recorder.append(transcript("privacy", 180))
            app.settings.update(SettingsChange(keepSessions = true))
            recorder.append(transcript("privacy", 181))
            recorder.finish(182)
            assertEquals(listOf("row-181"), db.captions().page("privacy", 200, 0).map { it.segmentId })
        } finally { parent.cancel(); db.close() }
    }

    @Test fun rapidPrivacyToggleRejectsOldEpochEvenWhenWorkerHasNotObservedDisable() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val recorder = SessionRecorder(db, app.settings, metadata("rapid"), TranslationMode.TO_ZH, parent)
        try {
            repeat(80) { recorder.append(transcript("rapid", it)) }
            app.settings.update(SettingsChange(keepSessions = false))
            app.settings.update(SettingsChange(keepSessions = true))
            recorder.append(transcript("rapid", 80)); recorder.finish(81)
            assertEquals(listOf("row-80"), db.captions().page("rapid", 200, 0).map { it.segmentId })
        } finally { parent.cancel(); db.close() }
    }

    @Test fun futureRevisionAndEarlySpeakerStayBoundedAndApplyToMatchingTranscript() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val recorder = SessionRecorder(db, app.settings, metadata("order"), TranslationMode.TO_ZH, parent)
        try {
            recorder.append(SubtitleEvent.Speaker("order", "row-0", 1, "B"))
            recorder.append(SubtitleEvent.Translation("order", "row-0", 2, "第二版"))
            recorder.append(transcript("order", 0))
            recorder.append(transcript("order", 0).copy(revision = 2, text = "Revision two"))
            recorder.finish(1)
            val row = db.captions().get("order", "row-0")!!.item()
            assertEquals("第二版", row.targetText); assertEquals(2, row.translationRevision)
            assertEquals("B", row.speakerId); assertEquals("Revision two", row.sourceText)
        } finally { parent.cancel(); db.close() }
    }

    @Test fun persistenceFailureSignalsCaptureStopAndFinishDoesNotHang() = runBlocking {
        val db = database(); val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        app.settings.update(UserSettings())
        val failed = CompletableDeferred<String>()
        val recorder = SessionRecorder(db, app.settings, metadata("failed"), TranslationMode.TO_ZH, parent) { failed.complete(it) }
        try {
            withContext(Dispatchers.IO) {
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_caption BEFORE INSERT ON session_captions BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END")
            }
            recorder.append(transcript("failed", 0))
            assertTrue(withTimeout(5000) { failed.await() }.contains("采集已停止"))
            assertTrue(runCatching { recorder.append(transcript("failed", 1)) }.isFailure)
            assertTrue(withTimeout(5000) { runCatching { recorder.finish(1) }.isFailure })
        } finally { parent.cancel(); db.close() }
    }

    @Test fun demoSaveWithOldPrivacyEpochIsRejectedAfterReenable() = runBlocking {
        val db = database()
        try {
            app.settings.update(UserSettings())
            val epoch = app.settings.retentionEpoch()
            app.settings.update(SettingsChange(keepSessions = false))
            app.settings.update(SettingsChange(keepSessions = true))
            SessionRepository(db).saveDemo(metadata("demo"), listOf(item("demo", 0)), app.settings, epoch)
            assertNull(db.sessions().metadata("demo"))
        } finally { db.close() }
    }
}
