package com.voxpen.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.RecordingMode
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.model.ToneStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val languageFlow: Flow<SttLanguage> = dataStore.data.map { languageFromKey(it[LANGUAGE_KEY] ?: "auto") }
        val recordingModeFlow: Flow<RecordingMode> = dataStore.data.map { RecordingMode.valueOf(it[RECORDING_MODE_KEY] ?: DEFAULT_RECORDING_MODE.name) }
        val refinementEnabledFlow: Flow<Boolean> = dataStore.data.map { it[REFINEMENT_ENABLED_KEY] ?: DEFAULT_REFINEMENT_ENABLED }
        val downloadLoggingEnabledFlow: Flow<Boolean> = dataStore.data.map { it[DOWNLOAD_LOGGING_ENABLED_KEY] ?: DEFAULT_DOWNLOAD_LOGGING_ENABLED }
        val sttModelFlow: Flow<String> = dataStore.data.map { it[STT_MODEL_KEY] ?: DEFAULT_STT_MODEL }
        val sttProviderFlow: Flow<SttProvider> = dataStore.data.map { prefs ->
            val providerKey = prefs[STT_PROVIDER_KEY]
            if (providerKey == null && !prefs[CUSTOM_STT_BASE_URL_KEY].isNullOrBlank()) SttProvider.Custom else SttProvider.fromKey(providerKey)
        }
        val llmModelFlow: Flow<String> = dataStore.data.map { it[LLM_MODEL_KEY] ?: DEFAULT_LLM_MODEL }
        val llmProviderFlow: Flow<LlmProvider> = dataStore.data.map { LlmProvider.fromKey(it[LLM_PROVIDER_KEY] ?: LlmProvider.DEFAULT.key) }
        val customLlmModelFlow: Flow<String> = dataStore.data.map { it[CUSTOM_LLM_MODEL_KEY] ?: "" }
        val customSttBaseUrlFlow: Flow<String> = dataStore.data.map { it[CUSTOM_STT_BASE_URL_KEY] ?: "" }
        val toneStyleFlow: Flow<ToneStyle> = dataStore.data.map { ToneStyle.fromKey(it[TONE_STYLE_KEY] ?: ToneStyle.DEFAULT.key) }
        val onboardingCompletedFlow: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_COMPLETED_KEY] ?: false }
        val keyboardTooltipsShownFlow: Flow<Boolean> = dataStore.data.map { it[KEYBOARD_TOOLTIPS_SHOWN_KEY] ?: false }
        val translationEnabledFlow: Flow<Boolean> = dataStore.data.map { it[TRANSLATION_ENABLED_KEY] ?: DEFAULT_TRANSLATION_ENABLED }
        val translationTargetLanguageFlow: Flow<SttLanguage> = dataStore.data.map { languageFromKey(it[TRANSLATION_TARGET_LANGUAGE_KEY] ?: "en") }
        val autoToneEnabledFlow: Flow<Boolean> = dataStore.data.map { it[AUTO_TONE_ENABLED_KEY] ?: DEFAULT_AUTO_TONE_ENABLED }
        val customAppToneRulesFlow: Flow<Map<String, ToneStyle>> = dataStore.data.map { prefs ->
            val json = prefs[CUSTOM_APP_TONE_RULES_KEY] ?: return@map emptyMap()
            try {
                Json.decodeFromString<Map<String, String>>(json).mapValues { (_, key) -> ToneStyle.fromKey(key) }
            } catch (_: Exception) {
                emptyMap()
            }
        }

        suspend fun setLanguage(language: SttLanguage) { dataStore.edit { it[LANGUAGE_KEY] = languageToKey(language) } }
        suspend fun setRecordingMode(mode: RecordingMode) { dataStore.edit { it[RECORDING_MODE_KEY] = mode.name } }
        suspend fun setRefinementEnabled(enabled: Boolean) { dataStore.edit { it[REFINEMENT_ENABLED_KEY] = enabled } }
        suspend fun setDownloadLoggingEnabled(enabled: Boolean) { dataStore.edit { it[DOWNLOAD_LOGGING_ENABLED_KEY] = enabled } }
        suspend fun setSttModel(model: String) { dataStore.edit { it[STT_MODEL_KEY] = model } }
        suspend fun setSttProvider(provider: SttProvider) { dataStore.edit { it[STT_PROVIDER_KEY] = provider.key } }
        suspend fun setLlmModel(model: String) { dataStore.edit { it[LLM_MODEL_KEY] = model } }
        suspend fun setLlmProvider(provider: LlmProvider) { dataStore.edit { it[LLM_PROVIDER_KEY] = provider.key } }
        suspend fun setCustomLlmModel(model: String) { dataStore.edit { it[CUSTOM_LLM_MODEL_KEY] = model } }
        suspend fun setCustomSttBaseUrl(url: String) { dataStore.edit { it[CUSTOM_STT_BASE_URL_KEY] = url } }
        suspend fun setToneStyle(tone: ToneStyle) { dataStore.edit { it[TONE_STYLE_KEY] = tone.key } }
        suspend fun setOnboardingCompleted(completed: Boolean) { dataStore.edit { it[ONBOARDING_COMPLETED_KEY] = completed } }
        suspend fun setKeyboardTooltipsShown(shown: Boolean) { dataStore.edit { it[KEYBOARD_TOOLTIPS_SHOWN_KEY] = shown } }
        suspend fun setTranslationEnabled(enabled: Boolean) { dataStore.edit { it[TRANSLATION_ENABLED_KEY] = enabled } }
        suspend fun setTranslationTargetLanguage(language: SttLanguage) { dataStore.edit { it[TRANSLATION_TARGET_LANGUAGE_KEY] = languageToKey(language) } }
        suspend fun setAutoToneEnabled(enabled: Boolean) { dataStore.edit { it[AUTO_TONE_ENABLED_KEY] = enabled } }

        suspend fun setCustomAppToneRule(packageName: String, tone: ToneStyle) {
            dataStore.edit { prefs ->
                val raw = prefs[CUSTOM_APP_TONE_RULES_KEY]?.let {
                    try { Json.decodeFromString<Map<String, String>>(it).toMutableMap() } catch (_: Exception) { mutableMapOf() }
                } ?: mutableMapOf()
                raw[packageName] = tone.key
                prefs[CUSTOM_APP_TONE_RULES_KEY] = Json.encodeToString<Map<String, String>>(raw)
            }
        }

        suspend fun removeCustomAppToneRule(packageName: String) {
            dataStore.edit { prefs ->
                val existing = prefs[CUSTOM_APP_TONE_RULES_KEY] ?: return@edit
                val raw = try { Json.decodeFromString<Map<String, String>>(existing).toMutableMap() } catch (_: Exception) { return@edit }
                raw.remove(packageName)
                prefs[CUSTOM_APP_TONE_RULES_KEY] = Json.encodeToString<Map<String, String>>(raw)
            }
        }

        fun customPromptFlow(languageKey: String): Flow<String?> = dataStore.data.map { it[stringPreferencesKey("custom_prompt_$languageKey")] }

        suspend fun setCustomPrompt(languageKey: String, prompt: String?) {
            dataStore.edit { prefs ->
                val key = stringPreferencesKey("custom_prompt_$languageKey")
                if (prompt.isNullOrBlank()) prefs.remove(key) else prefs[key] = prompt
            }
        }

        companion object {
            val DEFAULT_LANGUAGE: SttLanguage = SttLanguage.Auto
            val DEFAULT_RECORDING_MODE: RecordingMode = RecordingMode.TAP_TO_TOGGLE
            const val DEFAULT_REFINEMENT_ENABLED: Boolean = true
            const val DEFAULT_DOWNLOAD_LOGGING_ENABLED: Boolean = false
            const val DEFAULT_STT_MODEL: String = "whisper-large-v3-turbo"
            const val DEFAULT_LLM_MODEL: String = "llama-3.3-70b-versatile"
            const val DEFAULT_TRANSLATION_ENABLED: Boolean = false
            val DEFAULT_TRANSLATION_TARGET_LANGUAGE: SttLanguage = SttLanguage.English
            const val DEFAULT_AUTO_TONE_ENABLED: Boolean = true

            private val LANGUAGE_KEY = stringPreferencesKey("stt_language")
            private val RECORDING_MODE_KEY = stringPreferencesKey("recording_mode")
            private val REFINEMENT_ENABLED_KEY = booleanPreferencesKey("refinement_enabled")
            private val DOWNLOAD_LOGGING_ENABLED_KEY = booleanPreferencesKey("download_logging_enabled")
            private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")
            private val KEYBOARD_TOOLTIPS_SHOWN_KEY = booleanPreferencesKey("keyboard_tooltips_shown")
            private val STT_MODEL_KEY = stringPreferencesKey("stt_model")
            private val STT_PROVIDER_KEY = stringPreferencesKey("stt_provider")
            private val LLM_MODEL_KEY = stringPreferencesKey("llm_model")
            private val TONE_STYLE_KEY = stringPreferencesKey("tone_style")
            private val LLM_PROVIDER_KEY = stringPreferencesKey("llm_provider")
            private val CUSTOM_LLM_MODEL_KEY = stringPreferencesKey("custom_llm_model")
            private val CUSTOM_STT_BASE_URL_KEY = stringPreferencesKey("custom_stt_base_url_pref")
            private val TRANSLATION_ENABLED_KEY = booleanPreferencesKey("translation_enabled")
            private val TRANSLATION_TARGET_LANGUAGE_KEY = stringPreferencesKey("translation_target_language")
            private val AUTO_TONE_ENABLED_KEY = booleanPreferencesKey("auto_tone_enabled")
            private val CUSTOM_APP_TONE_RULES_KEY = stringPreferencesKey("custom_app_tone_rules")

            fun languageFromKey(key: String): SttLanguage = when (key) {
                "zh" -> SttLanguage.Chinese
                "en" -> SttLanguage.English
                "ja" -> SttLanguage.Japanese
                "ko" -> SttLanguage.Korean
                "fr" -> SttLanguage.French
                "de" -> SttLanguage.German
                "es" -> SttLanguage.Spanish
                "vi" -> SttLanguage.Vietnamese
                "id" -> SttLanguage.Indonesian
                "th" -> SttLanguage.Thai
                else -> SttLanguage.Auto
            }

            fun languageToKey(language: SttLanguage): String = when (language) {
                SttLanguage.Auto -> "auto"
                SttLanguage.Chinese -> "zh"
                SttLanguage.English -> "en"
                SttLanguage.Japanese -> "ja"
                SttLanguage.Korean -> "ko"
                SttLanguage.French -> "fr"
                SttLanguage.German -> "de"
                SttLanguage.Spanish -> "es"
                SttLanguage.Vietnamese -> "vi"
                SttLanguage.Indonesian -> "id"
                SttLanguage.Thai -> "th"
            }
        }
    }
