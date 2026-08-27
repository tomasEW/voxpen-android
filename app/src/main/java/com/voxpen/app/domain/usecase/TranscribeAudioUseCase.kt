package com.voxpen.app.domain.usecase

import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.repository.SttRepository
import com.voxpen.app.util.AudioEncoder
import com.voxpen.app.util.LiveAudioChunker
import javax.inject.Inject

class TranscribeAudioUseCase
    @Inject
    constructor(
        private val sttRepository: SttRepository,
    ) {
        suspend operator fun invoke(
            pcmData: ByteArray,
            language: SttLanguage,
            apiKey: String,
            model: String = "whisper-large-v3-turbo",
            vocabularyHint: String? = null,
            sampleRate: Int = SAMPLE_RATE,
            channels: Int = CHANNELS,
            bitsPerSample: Int = BITS_PER_SAMPLE,
            provider: SttProvider = SttProvider.DEFAULT,
            customSttBaseUrl: String? = null,
        ): Result<String> {
            val textChunks = mutableListOf<String>()
            val chunks =
                LiveAudioChunker.chunkPcm(
                    pcmData = pcmData,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                )
            for (chunk in chunks) {
                val wavBytes = AudioEncoder.pcmToWav(chunk, sampleRate, channels, bitsPerSample)
                val result =
                    sttRepository.transcribe(
                        wavBytes = wavBytes,
                        language = language,
                        apiKey = apiKey,
                        model = model,
                        vocabularyHint = vocabularyHint,
                        provider = provider,
                        customSttBaseUrl = customSttBaseUrl,
                    )
                result.fold(
                    onSuccess = { textChunks.add(it.text) },
                    onFailure = { return Result.failure(it) },
                )
            }
            val joined = textChunks.filter { it.isNotBlank() }.joinToString(" ")
            return Result.success(collapseObviousAdjacentDuplicates(joined))
        }

        /**
         * Removes only obvious ASR duplication: an immediately repeated phrase of at least eight
         * characters. Short repetitions are intentionally left alone because they can be natural
         * emphasis (for example, "真的真的" or "哈哈哈哈").
         */
        internal fun collapseObviousAdjacentDuplicates(text: String): String {
            var cleaned = text
            var previous: String
            do {
                previous = cleaned
                cleaned =
                    ADJACENT_DUPLICATE_REGEX.replace(cleaned) { match ->
                        val phrase = match.groupValues[1]
                        if (phrase.toSet().size <= 1) match.value else phrase
                    }
            } while (cleaned != previous)
            return cleaned.trim()
        }

        companion object {
            const val SAMPLE_RATE = 16000
            const val CHANNELS = 1
            const val BITS_PER_SAMPLE = 16

            private val ADJACENT_DUPLICATE_REGEX =
                Regex("(?s)(.{8,120}?)(?:\\s*\\1)+")
        }
    }
