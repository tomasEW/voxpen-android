package com.voxpen.app.util

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioChunker {
    private const val WAV_HEADER_SIZE = 44
    private const val DEFAULT_MAX_CHUNK_BYTES = 25 * 1024 * 1024 // 25MB

    fun chunk(
        data: ByteArray,
        maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
    ): List<ByteArray> {
        if (data.size <= maxChunkBytes) return listOf(data)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxChunkBytes, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    fun chunkWav(
        wavBytes: ByteArray,
        maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
    ): List<ByteArray> {
        require(maxChunkBytes > WAV_HEADER_SIZE) { "maxChunkBytes must be larger than the WAV header." }
        if (wavBytes.size <= maxChunkBytes) return listOf(wavBytes)
        require(isWav(wavBytes)) { "Only WAV audio can be split safely without decoding." }

        val header = wavBytes.copyOfRange(0, WAV_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // Extract audio parameters from WAV header
        buffer.position(22)
        val channels = buffer.short.toInt()
        val sampleRate = buffer.int
        buffer.position(34)
        val bitsPerSample = buffer.short.toInt()
        val bytesPerSample = channels * bitsPerSample / 8
        require(channels > 0 && sampleRate > 0 && bitsPerSample > 0 && bytesPerSample > 0) {
            "WAV audio format is invalid."
        }

        val pcmData = wavBytes.copyOfRange(WAV_HEADER_SIZE, wavBytes.size)
        val maxPcmPerChunk = maxChunkBytes - WAV_HEADER_SIZE

        // Align to sample boundaries
        val alignedMaxPcm = (maxPcmPerChunk / bytesPerSample) * bytesPerSample
        require(alignedMaxPcm > 0) { "maxChunkBytes is too small for one WAV sample." }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < pcmData.size) {
            val end = minOf(offset + alignedMaxPcm, pcmData.size)
            val chunkPcm = pcmData.copyOfRange(offset, end)
            chunks.add(AudioEncoder.pcmToWav(chunkPcm, sampleRate, channels, bitsPerSample))
            offset = end
        }
        return chunks
    }

    /**
     * Streams one supported audio file through the chunker without loading the complete file into
     * memory. WAV files are split at sample boundaries and rebuilt with a valid header. Other
     * formats are kept as one opaque upload when they fit in the provider limit; they are rejected
     * when they are too large because arbitrary byte slicing would corrupt the codec stream.
     *
     * The input stream remains owned by the caller and is not closed here.
     */
    suspend fun streamChunks(
        input: InputStream,
        maxChunkBytes: Int = DEFAULT_MAX_CHUNK_BYTES,
        onChunk: suspend (ByteArray) -> Unit,
    ): Long {
        require(maxChunkBytes > WAV_HEADER_SIZE) { "maxChunkBytes must be larger than the WAV header." }

        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(WAV_HEADER_SIZE)
        val probe = ByteArray(WAV_HEADER_SIZE)
        val probeBytes = readAtMost(buffered, probe)
        buffered.reset()

        return if (probeBytes == WAV_HEADER_SIZE && isWav(probe)) {
            streamWav(buffered, maxChunkBytes, onChunk)
        } else {
            streamOpaque(buffered, maxChunkBytes, onChunk)
        }
    }

    fun isWav(data: ByteArray): Boolean {
        if (data.size < WAV_HEADER_SIZE) return false
        val riff = String(data, 0, 4, Charsets.US_ASCII)
        val wave = String(data, 8, 4, Charsets.US_ASCII)
        return riff == "RIFF" && wave == "WAVE"
    }

    private suspend fun streamWav(
        input: InputStream,
        maxChunkBytes: Int,
        onChunk: suspend (ByteArray) -> Unit,
    ): Long {
        val header = ByteArray(WAV_HEADER_SIZE)
        check(readAtMost(input, header) == WAV_HEADER_SIZE) { "WAV header is incomplete." }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(22)
        val channels = buffer.short.toInt()
        val sampleRate = buffer.int
        buffer.position(34)
        val bitsPerSample = buffer.short.toInt()
        val bytesPerSample = channels * bitsPerSample / 8
        require(channels > 0 && sampleRate > 0 && bitsPerSample > 0 && bytesPerSample > 0) {
            "WAV audio format is invalid."
        }

        val maxPcmPerChunk = maxChunkBytes - WAV_HEADER_SIZE
        val alignedMaxPcm = (maxPcmPerChunk / bytesPerSample) * bytesPerSample
        require(alignedMaxPcm > 0) { "maxChunkBytes is too small for one WAV sample." }

        var totalBytes = WAV_HEADER_SIZE.toLong()
        while (true) {
            val pcmChunk = ByteArray(alignedMaxPcm)
            val bytesRead = readAtMost(input, pcmChunk)
            if (bytesRead == 0) break

            totalBytes += bytesRead
            onChunk(
                AudioEncoder.pcmToWav(
                    if (bytesRead == pcmChunk.size) pcmChunk else pcmChunk.copyOf(bytesRead),
                    sampleRate,
                    channels,
                    bitsPerSample,
                ),
            )
            if (bytesRead < pcmChunk.size) break
        }
        return totalBytes
    }

    private suspend fun streamOpaque(
        input: InputStream,
        maxChunkBytes: Int,
        onChunk: suspend (ByteArray) -> Unit,
    ): Long {
        val output = ByteArrayOutputStream(maxChunkBytes)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead == 0) continue
            totalBytes += bytesRead
            if (totalBytes > maxChunkBytes) {
                throw IllegalArgumentException(
                    "Non-WAV audio larger than ${maxChunkBytes / (1024 * 1024)} MB cannot be split safely.",
                )
            }
            output.write(buffer, 0, bytesRead)
        }
        onChunk(output.toByteArray())
        return totalBytes
    }

    private fun readAtMost(input: InputStream, target: ByteArray): Int {
        var totalBytes = 0
        while (totalBytes < target.size) {
            val bytesRead = input.read(target, totalBytes, target.size - totalBytes)
            if (bytesRead < 0) break
            if (bytesRead == 0) continue
            totalBytes += bytesRead
        }
        return totalBytes
    }
}
