package com.jd.rc.localtranslate.data

import androidx.room.withTransaction
import com.jd.rc.localtranslate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded journal; only inference workers can wait for disk backpressure, never AudioRecord. */
class SessionRecorder(private val db: SessionDatabase, private val settings: SettingsRepository,
    private val metadata: SavedSession, private val mode: TranslationMode, parent: CoroutineScope,
    private val onFailure: (String) -> Unit = {}) : ConversationJournal {
    private sealed interface Command {
        data class Event(val event: SubtitleEvent, val epoch: Long) : Command
        data class Merge(val merge: SpeakerMerge, val epoch: Long) : Command
        data object Purge : Command
    }
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    private val commands = Channel<Command>(128)
    private var cache = SubtitleReducer(metadata.id, mode, 500)
    private val dirty = linkedMapOf<String, CaptionRow>()
    private val merges = ArrayList<SpeakerMerge>()
    private val pendingSpeakers = linkedMapOf<String, SubtitleEvent.Speaker>()
    private val pendingTranslations = linkedMapOf<String, SubtitleEvent.Translation>()
    private val finishMutex = Mutex()
    @Volatile private var failure: Throwable? = null
    @Volatile private var ended = false
    private var duration = 0L
    private var epoch = settings.retentionEpoch()
    private var purged = false
    private val privacy = scope.launch {
        combine(settings.settings.map { it.keepSessions }.distinctUntilChanged(), settings.retentionChanges) { _, _ -> Unit }
            .collect { commands.send(Command.Purge) }
    }
    private val worker = scope.launch {
        try {
            var closed = false
            while (!closed) {
                val first = commands.receiveCatching().getOrNull() ?: break
                process(first)
                val deadline = System.nanoTime() + 250_000_000L
                var count = 1
                while (count < 32) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000
                    if (remaining <= 0) break
                    val next = withTimeoutOrNull(remaining) { commands.receiveCatching() } ?: break
                    val command = next.getOrNull()
                    if (command == null) { closed = true; break }
                    process(command); count++
                }
                flush()
            }
            flush()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failure = error
            onFailure("记录保存失败，采集已停止；已保存内容仍可查看。")
        } finally { commands.close(); privacy.cancel() }
    }
    override suspend fun append(event: SubtitleEvent) {
        check(event.sessionId == metadata.id)
        failure?.let { throw IllegalStateException("会话保存失败。", it) }
        val acceptedEpoch = settings.retentionEpoch()
        if (settings.canKeepSessions() && acceptedEpoch == settings.retentionEpoch()) commands.send(Command.Event(event, acceptedEpoch))
    }
    override suspend fun merge(merge: SpeakerMerge) {
        failure?.let { throw IllegalStateException("会话保存失败。", it) }
        val acceptedEpoch = settings.retentionEpoch()
        if (settings.canKeepSessions() && acceptedEpoch == settings.retentionEpoch()) commands.send(Command.Merge(merge, acceptedEpoch))
    }
    private suspend fun purge() {
        dirty.clear(); merges.clear(); pendingSpeakers.clear(); pendingTranslations.clear()
        cache = SubtitleReducer(metadata.id, mode, 500)
        if (!purged) db.sessions().delete(metadata.id)
        purged = true
    }
    private suspend fun process(command: Command) {
        if (epoch != settings.retentionEpoch()) { purge(); epoch = settings.retentionEpoch() }
        if (!settings.canKeepSessions()) { purge(); return }
        when (command) {
            is Command.Event -> {
                if (command.epoch != epoch) return
                val event = command.event
                val old = dirty[event.segmentId]?.item() ?: cache.get(event.segmentId)
                    ?: db.captions().get(metadata.id, event.segmentId)?.item()
                if (event is SubtitleEvent.Speaker && old == null) {
                    val previous = pendingSpeakers[event.segmentId]
                    if (event.revision >= 0 && event.revision > (previous?.revision ?: -1)) pendingSpeakers[event.segmentId] = cache.canonicalEvent(event)
                    while (pendingSpeakers.size > 256) pendingSpeakers.remove(pendingSpeakers.keys.first())
                    return
                }
                if (event is SubtitleEvent.Translation && (old == null || event.revision > old.revision)) {
                    val previous = pendingTranslations[event.segmentId]
                    if (event.revision >= 0 && event.revision > (previous?.revision ?: -1)) pendingTranslations[event.segmentId] = event
                    while (pendingTranslations.size > 256) pendingTranslations.remove(pendingTranslations.keys.first())
                    return
                }
                // A single-row reducer reuses the exact revision rules even after live-window eviction.
                val reducer = SubtitleReducer(metadata.id, mode)
                old?.let(reducer::seed)
                reducer.apply(if (event is SubtitleEvent.Speaker) cache.canonicalEvent(event) else event)
                if (event is SubtitleEvent.Transcript) {
                    pendingSpeakers.remove(event.segmentId)?.let { reducer.apply(cache.canonicalEvent(it)) }
                    pendingTranslations[event.segmentId]?.let {
                        if (it.revision <= event.revision) { reducer.apply(it); pendingTranslations.remove(event.segmentId) }
                    }
                }
                val updated = reducer.get(event.segmentId)?.let(cache::canonicalize) ?: return
                cache.seed(updated)
                if (updated != old) dirty[event.segmentId] = CaptionRow.from(updated)
                duration = maxOf(duration, updated.endSample / 16_000)
            }
            is Command.Merge -> {
                if (command.epoch != epoch) return
                val before = cache.canonicalId(command.merge.fromId)
                cache.mergeSpeakers(command.merge)
                val into = cache.canonicalId(command.merge.fromId)
                if (before == into || into == null) return
                val merge = command.merge.copy(intoId = into)
                dirty.replaceAll { _, row -> if (row.speakerId == merge.fromId)
                    row.copy(speakerId = into, speakerRevision = maxOf(row.speakerRevision, merge.revision)) else row }
                merges += merge
            }
            Command.Purge -> Unit
        }
    }
    private suspend fun flush() {
        if (epoch != settings.retentionEpoch()) { purge(); epoch = settings.retentionEpoch(); return }
        if (!settings.canKeepSessions()) { purge(); return }
        if (dirty.isEmpty() && merges.isEmpty()) return
        db.withTransaction {
            purged = false
            db.sessions().create(metadata.copy(captionsJson = "[]"))
            db.captions().save(dirty.values.toList())
            merges.forEach { db.captions().merge(metadata.id, it.fromId, it.intoId, it.revision) }
            db.sessions().duration(metadata.id, duration)
            if (!settings.canKeepSessions() || epoch != settings.retentionEpoch()) db.sessions().delete(metadata.id)
        }
        dirty.clear(); merges.clear()
    }
    suspend fun finish(durationSeconds: Long, save: Boolean = true) = withContext(NonCancellable + Dispatchers.IO) {
        finishMutex.withLock {
            if (ended) return@withLock
            ended = true
            privacy.cancelAndJoin(); commands.close(); worker.join()
            try {
                failure?.let { throw IllegalStateException("会话保存失败。", it) }
                if (save && settings.canKeepSessions() && epoch == settings.retentionEpoch()) db.sessions().duration(metadata.id, durationSeconds)
                else db.sessions().delete(metadata.id)
            } finally { scope.cancel() }
        }
    }
}
