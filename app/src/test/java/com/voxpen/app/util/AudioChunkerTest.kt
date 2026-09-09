package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AudioChunkerTest {
    @Test
    fun `should not chunk small data`() {
        val data = ByteArray(1000) { it.toByte() }
        val chunks = AudioChunker.chunk(data, maxChunkBytes = 5000)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0]).isEqualTo(data)
    }

    @Test
    fun `should chunk data exceeding max size`() {
        val data = ByteArray(10_000) { it.toByte() }
        val chunks = AudioChunker.chunk(data, maxChunkBytes = 3000)
        assertThat(chunks).hasSize(4)
        assertThat(chunks[0]).hasLength(3000)
        assertThat(chunks[1]).hasLength(3000)
        assertThat(chunks[2]).hasLength(3000)
        assertThat(chunks[3]).hasLength(1000)
    }

    @Test
    fun `should handle exact multiple of chunk size`() {
        val data = ByteArray(6000) { it.toByte() }
        val chunks = AudioChunker.chunk(data, maxChunkBytes = 3000)
        assertThat(chunks).hasSize(2)
        assertThat(chunks[0]).hasLength(3000)
        assertThat(chunks[1]).hasLength(3000)
    }

    @Test
    fun `should handle empty data`() {
        val data = ByteArray(0)
        val chunks = AudioChunker.chunk(data, maxChunkBytes = 5000)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0]).hasLength(0)
    }

    @Test
    fun `should use default max chunk size of 25MB`() {
        val smallData = ByteArray(100)
        val chunks = AudioChunker.chunk(smallData)
        assertThat(chunks).hasSize(1)
    }

    @Test
    fun `should chunk WAV preserving header for each chunk`() {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val pcmData = ByteArray(200_000) { (it % 256).toByte() }
        val wavBytes = AudioEncoder.pcmToWav(pcmData, sampleRate, channels, bitsPerSample)

        val chunks = AudioChunker.chunkWav(wavBytes, maxChunkBytes = 50_000)
        assertThat(chunks.size).isGreaterThan(1)

        // Each chunk should be a valid WAV
        for (chunk in chunks) {
            assertThat(chunk.size).isGreaterThan(44)
            val header = String(chunk, 0, 4, Charsets.US_ASCII)
            assertThat(header).isEqualTo("RIFF")
            val format = String(chunk, 8, 4, Charsets.US_ASCII)
            assertThat(format).isEqualTo("WAVE")
        }
    }

    @Test
    fun `should not chunk small WAV file`() {
        val pcmData = ByteArray(1000) { (it % 256).toByte() }
        val wavBytes = AudioEncoder.pcmToWav(pcmData, 16000, 1, 16)

        val chunks = AudioChunker.chunkWav(wavBytes, maxChunkBytes = 50_000)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0]).isEqualTo(wavBytes)
    }

    @Test
    fun `should detect WAV file correctly`() {
        val pcmData = ByteArray(100) { (it % 256).toByte() }
        val wavBytes = AudioEncoder.pcmToWav(pcmData, 16000, 1, 16)
        assertThat(AudioChunker.isWav(wavBytes)).isTrue()
    }

    @Test
    fun `should reject non-WAV as not WAV`() {
        val randomData = ByteArray(100) { it.toByte() }
        assertThat(AudioChunker.isWav(randomData)).isFalse()
    }

    @Test
    fun `should reject small data as not WAV`() {
        val tinyData = ByteArray(10)
        assertThat(AudioChunker.isWav(tinyData)).isFalse()
    }

    @Test
    fun `should stream WAV chunks without loading the whole file`() = runTest {
        val pcmData = ByteArray(200_000) { (it % 256).toByte() }
        val wavBytes = AudioEncoder.pcmToWav(pcmData, 16000, 1, 16)
        val chunks = mutableListOf<ByteArray>()

        val totalBytes = AudioChunker.streamChunks(
            ByteArrayInputStream(wavBytes),
            maxChunkBytes = 50_000,
        ) { chunks.add(it) }

        assertThat(totalBytes).isEqualTo(wavBytes.size.toLong())
        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach {
            assertThat(AudioChunker.isWav(it)).isTrue()
            assertThat(it.size).isAtMost(50_000)
        }
    }

    @Test
    fun `should preserve a small opaque file as one upload`() = runTest {
        val data = ByteArray(100) { it.toByte() }
        val chunks = mutableListOf<ByteArray>()

        val totalBytes = AudioChunker.streamChunks(
            ByteArrayInputStream(data),
            maxChunkBytes = 200,
        ) { chunks.add(it) }

        assertThat(totalBytes).isEqualTo(100)
        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()).isEqualTo(data)
    }

    @Test
    fun `should reject an opaque file that would need unsafe byte slicing`() = runTest {
        val data = ByteArray(201)

        val error = try {
            AudioChunker.streamChunks(ByteArrayInputStream(data), maxChunkBytes = 200) { }
            null
        } catch (throwable: Throwable) {
            throwable
        }
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
