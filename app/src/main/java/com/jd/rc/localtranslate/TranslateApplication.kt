package com.jd.rc.localtranslate

import android.app.Application
import androidx.room.Room
import com.jd.rc.localtranslate.audio.RecordingController
import com.jd.rc.localtranslate.data.SessionDatabase
import com.jd.rc.localtranslate.data.SessionRepository
import com.jd.rc.localtranslate.data.SettingsRepository
import com.jd.rc.localtranslate.model.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TranslateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        workScope.launch { models.installBundledModels() }
    }
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recording by lazy { RecordingController(this) }
    val settings by lazy { SettingsRepository(this) }
    val sessionDatabase by lazy { Room.databaseBuilder(this, SessionDatabase::class.java, "sessions.db")
        .addMigrations(SessionDatabase.MIGRATION_1_2).build() }
    val sessions by lazy { sessionDatabase.sessions() }
    val sessionRecords by lazy { SessionRepository(sessionDatabase) }
    val models by lazy { ModelRepository(this) }
}
