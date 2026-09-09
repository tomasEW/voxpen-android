package com.voxpen.app.ui.transcription

import com.voxpen.app.data.local.TranscriptionEntity
import com.voxpen.app.data.model.SttLanguage

data class TranscriptionUiState(
    val transcriptions: List<TranscriptionEntity> = emptyList(),
    val selectedTranscription: TranscriptionEntity? = null,
    val isTranscribing: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val selectedLanguage: SttLanguage = SttLanguage.Auto,
    val retryingId: Long? = null,
)
