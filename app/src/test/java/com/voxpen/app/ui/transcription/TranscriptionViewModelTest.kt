package com.voxpen.app.ui.transcription

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.local.ApiKeyManager
import com.voxpen.app.data.local.PreferencesManager
import com.voxpen.app.data.local.RecordingStore
import com.voxpen.app.data.local.TranscriptionEntity
import com.voxpen.app.data.repository.DictionaryRepository
import com.voxpen.app.data.model.SttProvider
import com.voxpen.app.data.remote.SttApi
import com.voxpen.app.data.remote.SttApiFactory
import com.voxpen.app.data.remote.WhisperResponse
import com.voxpen.app.data.repository.SttRepository
import com.voxpen.app.data.repository.TranscriptionRepository
import com.voxpen.app.domain.usecase.RetryTranscriptionUseCase
import com.voxpen.app.domain.usecase.TranscribeFileUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var transcriptionRepository: TranscriptionRepository
    private lateinit var retryTranscriptionUseCase: RetryTranscriptionUseCase
    private lateinit var transcribeFileUseCase: TranscribeFileUseCase
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var apiKeyManager: ApiKeyManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var viewModel: TranscriptionViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        transcriptionRepository = mockk(relaxed = true)
        retryTranscriptionUseCase = mockk(relaxed = true)
        transcribeFileUseCase = mockk(relaxed = true)
        dictionaryRepository = mockk(relaxed = true)
        apiKeyManager = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        every { transcriptionRepository.getAll() } returns flowOf(emptyList())
        every { preferencesManager.sttProviderFlow } returns MutableStateFlow<SttProvider>(SttProvider.Groq)
        every { preferencesManager.sttModelFlow } returns MutableStateFlow(PreferencesManager.DEFAULT_STT_MODEL)
        every { preferencesManager.customSttBaseUrlFlow } returns MutableStateFlow("")
        every { apiKeyManager.getSttApiKey(any()) } returns "stt-key"
        every { apiKeyManager.getGroqApiKey() } returns "stt-key"
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TranscriptionViewModel =
        TranscriptionViewModel(
            transcriptionRepository,
            retryTranscriptionUseCase,
            transcribeFileUseCase,
            dictionaryRepository,
            apiKeyManager,
            preferencesManager,
        )

    @Test
    fun `should start with empty transcription list`() =
        runTest {
            viewModel = createViewModel()

            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.transcriptions).isEmpty()
                assertThat(state.isTranscribing).isFalse()
                assertThat(state.error).isNull()
            }
        }

    @Test
    fun `should collect transcriptions from repository`() =
        runTest {
            val entities =
                listOf(
                    TranscriptionEntity(
                        id = 1,
                        fileName = "a.wav",
                        originalText = "Hello",
                        language = "en",
                        createdAt = 2000L,
                    ),
                    TranscriptionEntity(
                        id = 2,
                        fileName = "b.wav",
                        originalText = "World",
                        language = "zh",
                        createdAt = 1000L,
                    ),
                )
            every { transcriptionRepository.getAll() } returns flowOf(entities)
            viewModel = createViewModel()

            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.transcriptions).hasSize(2)
                assertThat(state.transcriptions[0].fileName).isEqualTo("a.wav")
            }
        }

    @Test
    fun `should select transcription for detail view`() =
        runTest {
            val entity =
                TranscriptionEntity(
                    id = 1,
                    fileName = "a.wav",
                    originalText = "Hello",
                    language = "en",
                    createdAt = 1000L,
                )
            every { transcriptionRepository.getAll() } returns flowOf(listOf(entity))
            viewModel = createViewModel()

            viewModel.selectTranscription(entity)

            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.selectedTranscription).isNotNull()
                assertThat(state.selectedTranscription?.fileName).isEqualTo("a.wav")
            }
        }

    @Test
    fun `should clear selection`() =
        runTest {
            val entity =
                TranscriptionEntity(
                    id = 1,
                    fileName = "a.wav",
                    originalText = "Hello",
                    language = "en",
                    createdAt = 1000L,
                )
            every { transcriptionRepository.getAll() } returns flowOf(listOf(entity))
            viewModel = createViewModel()

            viewModel.selectTranscription(entity)
            viewModel.clearSelection()

            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.selectedTranscription).isNull()
            }
        }

    @Test
    fun `should delete transcription by id`() =
        runTest {
            viewModel = createViewModel()

            viewModel.deleteTranscription(1L)

            coVerify { transcriptionRepository.deleteById(1L) }
        }

    @Test
    fun `should clear error`() =
        runTest {
            viewModel = createViewModel()

            viewModel.clearError()

            viewModel.uiState.test {
                val state = awaitItem()
                assertThat(state.error).isNull()
            }
        }

    @Test
    fun `should increment voice usage after successful live retry for Free users`() =
        runTest {
            val retryRepository: TranscriptionRepository = mockk()
            val recordingStore: RecordingStore = mockk()
            val sttApi: SttApi = mockk()
            val sttApiFactory: SttApiFactory = mockk()
            every { sttApiFactory.createForProvider(any()) } returns sttApi
            val sttRepository = SttRepository(sttApiFactory)
            retryTranscriptionUseCase =
                RetryTranscriptionUseCase(
                    transcriptionRepository = retryRepository,
                    recordingStore = recordingStore,
                    sttRepository = sttRepository,
                    ioDispatcher = testDispatcher,
                )
            viewModel = createViewModel()
            val failedEntity =
                TranscriptionEntity(
                    id = 7,
                    fileName = "Live recording",
                    originalText = "",
                    language = "en",
                    status = TranscriptionEntity.STATUS_FAILED,
                    audioPath = "retry.wav",
                    createdAt = 1000L,
                )
            val entity =
                TranscriptionEntity(
                    id = 7,
                    fileName = "Live recording",
                    originalText = "Retried",
                    language = "en",
                    status = TranscriptionEntity.STATUS_COMPLETED,
                    createdAt = 1000L,
                )
            coEvery { retryRepository.getById(7) } returns failedEntity
            every { recordingStore.exists("retry.wav") } returns true
            every { recordingStore.read("retry.wav") } returns ByteArray(100)
            coEvery {
                sttApi.transcribe(any(), any(), any(), any(), any(), any())
            } returns WhisperResponse(text = "Retried")
            coEvery { retryRepository.markCompletedAfterRetry(7, "Retried") } returns entity

            viewModel.retryTranscription(7)
            advanceUntilIdle()
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.uiState.test {
                assertThat(awaitItem().selectedTranscription).isEqualTo(entity)
            }
        }
}
