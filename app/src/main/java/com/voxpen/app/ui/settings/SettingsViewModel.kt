package com.voxpen.app.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxpen.app.billing.BillingManager
import com.voxpen.app.billing.LicenseManager
import com.voxpen.app.billing.ProStatusResolver
import com.voxpen.app.billing.UsageLimiter
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.RecordingMode
import com.voxpen.app.data.model.RefinementPrompt
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.model.ToneStyle
import com.voxpen.app.data.repository.LlmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val apiKeyManager: ApiKeyManager,
    private val preferencesManager: PreferencesManager,
    private val billingManager: BillingManager,
    private val usageLimiter: UsageLimiter,
    private val licenseManager: LicenseManager,
    private val proStatusResolver: ProStatusResolver,
    private val llmRepository: LlmRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var customPromptJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                isApiKeyConfigured = apiKeyManager.isGroqKeyConfigured(),
                apiKeyDisplay = maskApiKey(apiKeyManager.getGroqApiKey()),
                remainingVoiceInputs = usageLimiter.remainingVoiceInputs(),
                remainingRefinements = usageLimiter.remainingRefinements(),
                remainingFileTranscriptions = usageLimiter.remainingFileTranscriptions(),
                providerApiKeys = LlmProvider.all.associate { p -> p.key to apiKeyManager.isKeyConfigured(p) },
                sttProviderApiKeys = SttProvider.all.associate { p -> p.key to apiKeyManager.isSttKeyConfigured(p) },
                customBaseUrl = apiKeyManager.getCustomBaseUrl() ?: "",
            )
        }
        viewModelScope.launch {
            preferencesManager.languageFlow.collect { lang ->
                _uiState.update { it.copy(language = lang) }
                loadCustomPromptForLanguage(lang)
            }
        }
        viewModelScope.launch { preferencesManager.recordingModeFlow.collect { v -> _uiState.update { it.copy(recordingMode = v) } } }
        viewModelScope.launch { preferencesManager.refinementEnabledFlow.collect { v -> _uiState.update { it.copy(refinementEnabled = v) } } }
        viewModelScope.launch { preferencesManager.downloadLoggingEnabledFlow.collect { v -> _uiState.update { it.copy(downloadLoggingEnabled = v) } } }
        viewModelScope.launch { preferencesManager.sttModelFlow.collect { v -> _uiState.update { it.copy(sttModel = v) } } }
        viewModelScope.launch { preferencesManager.sttProviderFlow.collect { v -> _uiState.update { it.copy(sttProvider = v) } } }
        viewModelScope.launch { preferencesManager.llmModelFlow.collect { v -> _uiState.update { it.copy(llmModel = v) } } }
        viewModelScope.launch { preferencesManager.toneStyleFlow.collect { v -> _uiState.update { it.copy(toneStyle = v) } } }
        viewModelScope.launch { preferencesManager.llmProviderFlow.collect { v -> _uiState.update { it.copy(llmProvider = v) } } }
        viewModelScope.launch { preferencesManager.customLlmModelFlow.collect { v -> _uiState.update { it.copy(customLlmModel = v) } } }
        viewModelScope.launch { preferencesManager.customSttBaseUrlFlow.collect { v -> _uiState.update { it.copy(customSttBaseUrl = v) } } }
        viewModelScope.launch { preferencesManager.translationEnabledFlow.collect { v -> _uiState.update { it.copy(translationEnabled = v) } } }
        viewModelScope.launch { preferencesManager.translationTargetLanguageFlow.collect { v -> _uiState.update { it.copy(translationTargetLanguage = v) } } }
        viewModelScope.launch { proStatusResolver.proStatus.collect { v -> _uiState.update { it.copy(proStatus = v) } } }
        viewModelScope.launch { preferencesManager.autoToneEnabledFlow.collect { v -> _uiState.update { it.copy(autoToneEnabled = v) } } }
        viewModelScope.launch { preferencesManager.customAppToneRulesFlow.collect { v -> _uiState.update { it.copy(customAppToneRules = v) } } }
    }

    fun saveApiKey(key: String) {
        apiKeyManager.setGroqApiKey(key)
        _uiState.update { it.copy(isApiKeyConfigured = apiKeyManager.isGroqKeyConfigured(), apiKeyDisplay = maskApiKey(key)) }
    }

    fun setLanguage(v: SttLanguage) { viewModelScope.launch { preferencesManager.setLanguage(v) } }
    fun setRecordingMode(v: RecordingMode) { viewModelScope.launch { preferencesManager.setRecordingMode(v) } }
    fun setRefinementEnabled(v: Boolean) { viewModelScope.launch { preferencesManager.setRefinementEnabled(v) } }
    fun setDownloadLoggingEnabled(v: Boolean) { viewModelScope.launch { preferencesManager.setDownloadLoggingEnabled(v) } }
    fun setSttModel(v: String) { viewModelScope.launch { preferencesManager.setSttModel(v) } }
    fun setSttProvider(v: SttProvider) { viewModelScope.launch { preferencesManager.setSttProvider(v); preferencesManager.setSttModel(v.defaultModelId) } }
    fun setLlmModel(v: String) { viewModelScope.launch { preferencesManager.setLlmModel(v) } }
    fun setToneStyle(v: ToneStyle) { viewModelScope.launch { preferencesManager.setToneStyle(v) } }
    fun setLlmProvider(v: LlmProvider) { viewModelScope.launch { preferencesManager.setLlmProvider(v); if (v.defaultModelId.isNotBlank()) preferencesManager.setLlmModel(v.defaultModelId) } }

    fun saveProviderApiKey(provider: LlmProvider, key: String) {
        apiKeyManager.setApiKey(provider, key)
        _uiState.update { it.copy(providerApiKeys = it.providerApiKeys + (provider.key to key.isNotBlank()), isApiKeyConfigured = if (provider == LlmProvider.Groq) key.isNotBlank() else it.isApiKeyConfigured, apiKeyDisplay = if (provider == LlmProvider.Groq) maskApiKey(key) else it.apiKeyDisplay) }
    }

    fun saveSttProviderApiKey(provider: SttProvider, key: String) {
        apiKeyManager.setSttApiKey(provider, key)
        _uiState.update { it.copy(sttProviderApiKeys = it.sttProviderApiKeys + (provider.key to key.isNotBlank()), isApiKeyConfigured = if (provider == SttProvider.Groq) key.isNotBlank() else it.isApiKeyConfigured, apiKeyDisplay = if (provider == SttProvider.Groq) maskApiKey(key) else it.apiKeyDisplay) }
    }

    fun setCustomLlmModel(v: String) { viewModelScope.launch { preferencesManager.setCustomLlmModel(v) } }
    fun setCustomSttBaseUrl(v: String) { viewModelScope.launch { preferencesManager.setCustomSttBaseUrl(v) } }
    fun setTranslationEnabled(v: Boolean) { viewModelScope.launch { preferencesManager.setTranslationEnabled(v) } }
    fun setTranslationTargetLanguage(v: SttLanguage) { viewModelScope.launch { preferencesManager.setTranslationTargetLanguage(v) } }
    fun setAutoToneEnabled(v: Boolean) { viewModelScope.launch { preferencesManager.setAutoToneEnabled(v) } }
    fun setCustomAppToneRule(packageName: String, tone: ToneStyle) { viewModelScope.launch { preferencesManager.setCustomAppToneRule(packageName, tone) } }
    fun removeCustomAppToneRule(packageName: String) { viewModelScope.launch { preferencesManager.removeCustomAppToneRule(packageName) } }
    fun setCustomBaseUrl(v: String) { apiKeyManager.setCustomBaseUrl(v); _uiState.update { it.copy(customBaseUrl = v) } }

    fun testLlmProvider() {
        val state = _uiState.value
        val customBaseUrl = if (state.llmProvider == LlmProvider.Custom) state.customBaseUrl.ifBlank { null } else null
        if (state.llmProvider == LlmProvider.Custom && customBaseUrl == null) {
            _uiState.update { it.copy(llmTestStatus = LlmTestStatus.NoBaseUrl) }
            return
        }
        val model = if (state.llmProvider == LlmProvider.Custom) state.customLlmModel.ifBlank { state.llmModel } else state.llmModel
        val apiKey = apiKeyManager.getApiKey(state.llmProvider).orEmpty()
        _uiState.update { it.copy(llmTestStatus = LlmTestStatus.Testing) }
        viewModelScope.launch {
            llmRepository.editText("Reply with exactly: ok", apiKey, model, state.llmProvider, customBaseUrl).fold(
                onSuccess = { reply -> _uiState.update { it.copy(llmTestStatus = LlmTestStatus.Success(reply)) } },
                onFailure = { e -> _uiState.update { it.copy(llmTestStatus = LlmTestStatus.Error(e.message ?: "Unknown error")) } },
            )
        }
    }

    fun launchPurchaseFlow(activity: Activity) { billingManager.launchPurchaseFlow(activity) }
    fun restorePurchases() { billingManager.restorePurchases() }
    fun toggleDebugPro() { if (com.voxpen.app.BuildConfig.DEBUG) billingManager.debugOverrideProStatus(!billingManager.proStatus.value.isPro) }
    fun refreshUsage() { _uiState.update { it.copy(remainingVoiceInputs = usageLimiter.remainingVoiceInputs(), remainingRefinements = usageLimiter.remainingRefinements(), remainingFileTranscriptions = usageLimiter.remainingFileTranscriptions()) } }

    fun activateLicense(key: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActivatingLicense = true, licenseError = null) }
            licenseManager.activateLicense(key).fold(
                onSuccess = { _uiState.update { it.copy(isActivatingLicense = false) } },
                onFailure = { e -> _uiState.update { it.copy(isActivatingLicense = false, licenseError = e.message) } },
            )
        }
    }

    fun deactivateLicense() { viewModelScope.launch { licenseManager.deactivateLicense() } }
    fun clearLicenseError() { _uiState.update { it.copy(licenseError = null) } }
    fun updateCustomPromptDraft(v: String) { _uiState.update { it.copy(customPromptDraft = v) } }

    fun saveCustomPrompt() {
        val state = _uiState.value
        val langKey = PreferencesManager.languageToKey(state.language)
        val defaultPrompt = RefinementPrompt.defaultForLanguage(state.language)
        val promptToSave = if (state.customPromptDraft.isBlank() || state.customPromptDraft == defaultPrompt) null else state.customPromptDraft
        viewModelScope.launch {
            preferencesManager.setCustomPrompt(langKey, promptToSave)
            _uiState.update { it.copy(customPrompt = promptToSave, promptSnackbar = "saved") }
        }
    }

    fun resetCustomPrompt() {
        val state = _uiState.value
        val langKey = PreferencesManager.languageToKey(state.language)
        val defaultPrompt = RefinementPrompt.defaultForLanguage(state.language)
        viewModelScope.launch {
            preferencesManager.setCustomPrompt(langKey, null)
            _uiState.update { it.copy(customPrompt = null, customPromptDraft = defaultPrompt, promptSnackbar = "reset") }
        }
    }

    fun clearPromptSnackbar() { _uiState.update { it.copy(promptSnackbar = null) } }

    private fun loadCustomPromptForLanguage(language: SttLanguage) {
        customPromptJob?.cancel()
        customPromptJob = viewModelScope.launch {
            val langKey = PreferencesManager.languageToKey(language)
            preferencesManager.customPromptFlow(langKey).collect { saved ->
                val defaultPrompt = RefinementPrompt.defaultForLanguage(language)
                _uiState.update { it.copy(customPrompt = saved, customPromptDraft = saved ?: defaultPrompt) }
            }
        }
    }

    private fun maskApiKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        if (key.length <= 8) return "••••••••"
        return key.take(4) + "••••" + key.takeLast(4)
    }
}
