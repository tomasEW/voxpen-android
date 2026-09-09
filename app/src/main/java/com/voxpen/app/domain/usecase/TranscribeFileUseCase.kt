package com.voxpen.app.domain.usecase

import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.TranscriptionEntity
import com.voxpen.app.data.model.LlmProvider
import com.voxpen.app.data.model.SttLanguage
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.model.ToneStyle
import com.voxpen.app.data.repository.SttRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.data.repository.TranscriptionSegment
import com.voxpen.app.util.AudioChunker
import com.voxpen.app.util.TranscriptionTextJoiner
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

class TranscribeFileUseCase
    @Inject
    constructor(
        private val sttRepository: SttRepository,
        private val transcriptionRepository: TranscriptionRepository,
        private val refineTextUseCase: RefineTextUseCase,
    ) {
        suspend operator fun invoke(
            fileBytes: ByteArray,
            fileName: String,
            language: SttLanguage,
            apiKey: String,
            maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
            sttProvider: SttProvider = SttProvider.DEFAULT,
            sttModel: String = sttProvider.defaultModelId,
            customSttBaseUrl: String? = null,
            refinementApiKey: String? = null,
            llmModel: String? = null,
            llmProvider: LlmProvider? = null,
            customLlmBaseUrl: String? = null,
            tone: ToneStyle = ToneStyle.Casual,
            vocabulary: List<String> = emptyList(),
            customPrompt: String? = null,
            fileMimeType: String? = null,
        ): Result<TranscriptionEntity> {
            val isWav = AudioChunker.isWav(fileBytes)
            if (!isWav && fileBytes.size > maxChunkBytes) {
                return Result.failure(
                    IllegalArgumentException(
                        "Non-WAV audio larger than ${maxChunkBytes / (1024 * 1024)} MB cannot be split safely.",
                    ),
                )
            }

            val chunks = if (isWav) AudioChunker.chunkWav(fileBytes, maxChunkBytes) else listOf(fileBytes)
            return transcribeChunks(
                chunksProvider = { emit -> chunks.forEach { emit(it) } },
                fileName = if (isWav) "recording.wav" else fileName,
                mimeType = if (isWav) "audio/wav" else fileMimeType ?: mimeTypeForFileName(fileName),
                fileSizeBytesProvider = { fileBytes.size.toLong() },
                language = language,
                apiKey = apiKey,
                sttProvider = sttProvider,
                sttModel = sttModel,
                customSttBaseUrl = customSttBaseUrl,
                refinementApiKey = refinementApiKey,
                llmModel = llmModel,
                llmProvider = llmProvider,
                customLlmBaseUrl = customLlmBaseUrl,
                tone = tone,
                vocabulary = vocabulary,
                customPrompt = customPrompt,
            )
        }

        /**
         * Transcribes a content-provider stream. The stream is consumed incrementally so the UI
         * does not need to retain the entire selected file in memory.
         */
        suspend operator fun invoke(
            input: InputStream,
            fileName: String,
            fileMimeType: String?,
            language: SttLanguage,
            apiKey: String,
            maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
            sttProvider: SttProvider = SttProvider.DEFAULT,
            sttModel: String = sttProvider.defaultModelId,
            customSttBaseUrl: String? = null,
            refinementApiKey: String? = null,
            llmModel: String? = null,
            llmProvider: LlmProvider? = null,
            customLlmBaseUrl: String? = null,
            tone: ToneStyle = ToneStyle.Casual,
            vocabulary: List<String> = emptyList(),
            customPrompt: String? = null,
        ): Result<TranscriptionEntity> {
            var fileSizeBytes = 0L
            return try {
                transcribeChunks(
                    chunksProvider = { emit ->
                        fileSizeBytes = AudioChunker.streamChunks(input, maxChunkBytes, emit)
                    },
                    fileName = fileName,
                    mimeType = fileMimeType ?: mimeTypeForFileName(fileName),
                    fileSizeBytesProvider = { fileSizeBytes },
                    language = language,
                    apiKey = apiKey,
                    sttProvider = sttProvider,
                    sttModel = sttModel,
                    customSttBaseUrl = customSttBaseUrl,
                    refinementApiKey = refinementApiKey,
                    llmModel = llmModel,
                    llmProvider = llmProvider,
                    customLlmBaseUrl = customLlmBaseUrl,
                    tone = tone,
                    vocabulary = vocabulary,
                    customPrompt = customPrompt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        private suspend fun transcribeChunks(
            chunksProvider: suspend (suspend (ByteArray) -> Unit) -> Unit,
            fileName: String,
            mimeType: String,
            fileSizeBytesProvider: () -> Long,
            language: SttLanguage,
            apiKey: String,
            sttProvider: SttProvider,
            sttModel: String,
            customSttBaseUrl: String?,
            refinementApiKey: String?,
            llmModel: String?,
            llmProvider: LlmProvider?,
            customLlmBaseUrl: String?,
            tone: ToneStyle,
            vocabulary: List<String>,
            customPrompt: String?,
        ): Result<TranscriptionEntity> {
            val transcriptions = mutableListOf<String>()
            val allSegments = mutableListOf<TranscriptionSegment>()
            var segmentOffsetMs = 0L
            var failure: Throwable? = null

            chunksProvider { chunk ->
                if (failure == null) {
                    val result =
                        sttRepository.transcribe(
                            wavBytes = chunk,
                            language = language,
                            apiKey = apiKey,
                            model = sttModel,
                            provider = sttProvider,
                            customSttBaseUrl = customSttBaseUrl,
                            fileName = fileName,
                            mimeType = mimeType,
                        )
                    result.fold(
                        onSuccess = { tr ->
                            transcriptions.add(tr.text)
                            tr.segments.forEach { seg ->
                                allSegments.add(
                                    TranscriptionSegment(
                                        startMs = seg.startMs + segmentOffsetMs,
                                        endMs = seg.endMs + segmentOffsetMs,
                                        text = seg.text,
                                    ),
                                )
                            }
                            tr.segments.lastOrNull()?.let { segmentOffsetMs += it.endMs }
                        },
                        onFailure = { failure = it },
                    )
                }
            }

            failure?.let { return Result.failure(it) }

            val mergedText = TranscriptionTextJoiner.join(transcriptions, language)

            val refinedText = if (!refinementApiKey.isNullOrBlank() && llmProvider != null && llmModel != null) {
                refineTextUseCase(
                    text = mergedText,
                    language = language,
                    apiKey = refinementApiKey,
                    model = llmModel,
                    vocabulary = vocabulary,
                    customPrompt = customPrompt,
                    tone = tone,
                    provider = llmProvider,
                    customBaseUrl = customLlmBaseUrl,
                ).getOrNull()
            } else {
                null
            }

            val segmentsJson = if (allSegments.isNotEmpty()) {
                Json.encodeToString(allSegments.map { StoredSegment(it.startMs, it.endMs, it.text) })
            } else {
                null
            }

            val languageKey = PreferencesManager.languageToKey(language)
            val entity =
                TranscriptionEntity(
                    fileName = fileName,
                    originalText = mergedText,
                    refinedText = refinedText,
                    language = languageKey,
                    fileSizeBytes = fileSizeBytesProvider(),
                    segmentsJson = segmentsJson,
                    status = TranscriptionEntity.STATUS_COMPLETED,
                    provider = sttProvider.key,
                    createdAt = System.currentTimeMillis(),
                )
            val id = transcriptionRepository.insert(entity)
            return Result.success(entity.copy(id = id))
        }

        private fun mimeTypeForFileName(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "m4a", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg", "oga" -> "audio/ogg"
                "flac" -> "audio/flac"
                "webm" -> "audio/webm"
                else -> "application/octet-stream"
            }

        companion object {
            private const val DEFAULT_MAX_CHUNK_BYTES = 25 * 1024 * 1024
        }
    }

@Serializable
private data class StoredSegment(val s: Long, val e: Long, val t: String)
