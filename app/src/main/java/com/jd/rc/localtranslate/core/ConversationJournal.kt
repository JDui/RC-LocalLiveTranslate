package com.jd.rc.localtranslate.core

interface ConversationJournal {
    suspend fun append(event: SubtitleEvent)
    suspend fun merge(merge: SpeakerMerge)
}
