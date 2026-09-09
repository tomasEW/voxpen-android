package com.voxpen.app.ui.transcription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.TranscriptionEntity
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.repository.DictionaryRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.domain.usecase.TranscribeFileUseCase
import com.voxpen.app.domain.usecase.RetryTranscriptionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class TranscriptionViewModel
    @Inject
    constructor(
        private val transcriptionRepository: TranscriptionRepository,
        private val retryTranscriptionUseCase: RetryTranscriptionUseCase,
        private val transcribeFileUseCase: TranscribeFileUseCase,
        private val dictionaryRepository: DictionaryRepository,
        private val apiKeyManager: ApiKeyManager,
        private val preferencesManager: PreferencesManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TranscriptionUiState())
        val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                transcriptionRepository.getAll().collect { list ->
                    _uiState.update { it.copy(transcriptions = list) }
                }
            }
        }

        fun selectTranscription(entity: TranscriptionEntity) {
            _uiState.update { it.copy(selectedTranscription = entity) }
        }

        fun clearSelection() {
            _uiState.update { it.copy(selectedTranscription = null) }
        }

        fun deleteTranscription(id: Long) {
            viewModelScope.launch {
                transcriptionRepository.deleteById(id)
                _uiState.update {
                    if (it.selectedTranscription?.id == id) {
                        it.copy(selectedTranscription = null)
                    } else {
                        it
                    }
                }
            }
        }

        fun clearError() {
            _uiState.update { it.copy(error = null) }
        }

        fun transcribeFile(
            fileName: String,
            fileMimeType: String?,
            openStream: () -> InputStream?,
        ) {
            if (_uiState.value.isTranscribing) return
            val language = _uiState.value.selectedLanguage
            _uiState.update { it.copy(isTranscribing = true, progress = "Preparing…", error = null) }

            viewModelScope.launch {
                var input: InputStream? = null
                val result =
                    try {
                        val sttProvider = preferencesManager.sttProviderFlow.first()
                        val sttApiKey = apiKeyManager.getSttApiKey(sttProvider).orEmpty()
                        val sttModel = preferencesManager.sttModelFlow.first().ifBlank { sttProvider.defaultModelId }
                        val customSttBaseUrl =
                            if (sttProvider == SttProvider.Custom) {
                                preferencesManager.customSttBaseUrlFlow.first().ifBlank { null }
                            } else {
                                null
                            }
                        val llmProvider = preferencesManager.llmProviderFlow.first()
                        val llmApiKey = apiKeyManager.getApiKey(llmProvider) ?: apiKeyManager.getGroqApiKey()
                        val llmModel =
                            if (llmProvider == LlmProvider.Custom) {
                                preferencesManager.customLlmModelFlow.first().ifBlank { preferencesManager.llmModelFlow.first() }
                            } else {
                                preferencesManager.llmModelFlow.first()
                            }
                        val refinementEnabled = preferencesManager.refinementEnabledFlow.first()
                        val tone = preferencesManager.toneStyleFlow.first()
                        val vocabulary = dictionaryRepository.getWords(500)
                        val langKey = PreferencesManager.languageToKey(language)
                        val customPrompt = preferencesManager.customPromptFlow(langKey).first()
                        val customLlmBaseUrl =
                            if (llmProvider == LlmProvider.Custom) {
                                apiKeyManager.getCustomBaseUrl()
                            } else {
                                null
                            }

                        input = withContext(Dispatchers.IO) { openStream() }
                        if (input == null) {
                            Result.failure(IllegalStateException("Could not read file"))
                        } else {
                            withContext(Dispatchers.IO) {
                                transcribeFileUseCase(
                                    input = input!!,
                                    fileName = fileName,
                                    fileMimeType = fileMimeType,
                                    language = language,
                                    apiKey = sttApiKey,
                                    sttProvider = sttProvider,
                                    sttModel = sttModel,
                                    customSttBaseUrl = customSttBaseUrl,
                                    refinementApiKey = if (refinementEnabled) llmApiKey else null,
                                    llmModel = llmModel,
                                    llmProvider = llmProvider,
                                    customLlmBaseUrl = customLlmBaseUrl,
                                    tone = tone,
                                    vocabulary = vocabulary,
                                    customPrompt = customPrompt,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    } finally {
                        input?.let { stream -> withContext(Dispatchers.IO) { stream.close() } }
                    }

                result.fold(
                    onSuccess = ::onTranscriptionComplete,
                    onFailure = { onTranscriptionError(it.message ?: "Transcription failed") },
                )
            }
        }

        fun onTranscriptionComplete(entity: TranscriptionEntity) {
            _uiState.update {
                it.copy(
                    isTranscribing = false,
                    progress = "",
                    selectedTranscription = entity,
                )
            }
        }

        fun setLanguage(language: SttLanguage) {
            _uiState.update { it.copy(selectedLanguage = language) }
        }

        fun onTranscriptionError(message: String) {
            _uiState.update {
                it.copy(isTranscribing = false, progress = "", error = message)
            }
        }

        fun retryTranscription(id: Long) {
            viewModelScope.launch {
                _uiState.update { it.copy(retryingId = id, error = null) }
                val provider = preferencesManager.sttProviderFlow.first()
                val model = preferencesManager.sttModelFlow.first().ifBlank { provider.defaultModelId }
                val customSttBaseUrl =
                    if (provider == SttProvider.Custom) {
                        preferencesManager.customSttBaseUrlFlow.first().ifBlank { null }
                    } else {
                        null
                    }
                val apiKey = apiKeyManager.getSttApiKey(provider)
                if (apiKey.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(retryingId = null, error = "API key not configured")
                    }
                    return@launch
                }
                val result =
                    retryTranscriptionUseCase(
                        id = id,
                        apiKey = apiKey,
                        provider = provider,
                        model = model,
                        customSttBaseUrl = customSttBaseUrl,
                    )
                result.fold(
                    onSuccess = { entity ->
                        _uiState.update {
                            it.copy(retryingId = null, selectedTranscription = entity)
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(retryingId = null, error = error.message ?: "Retry failed")
                        }
                    },
                )
            }
        }
    }
