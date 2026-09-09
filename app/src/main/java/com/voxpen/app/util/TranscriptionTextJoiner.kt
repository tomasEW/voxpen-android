package com.voxpen.app.util

import com.voxpen.app.data.model.SttLanguage

object TranscriptionTextJoiner {
    fun join(chunks: Iterable<String>, language: SttLanguage): String {
        val nonBlank = chunks.map { it.trim() }.filter { it.isNotBlank() }
        return if (language == SttLanguage.Chinese) {
            nonBlank.joinToString("")
        } else {
            nonBlank.joinToString(" ")
        }
    }
}
