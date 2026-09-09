package com.voxpen.app.util

import com.google.common.truth.Truth.assertThat
import com.voxpen.app.data.model.SttLanguage
import org.junit.jupiter.api.Test

class TranscriptionTextJoinerTest {
    @Test
    fun `should join Chinese chunks without inserting spaces`() {
        assertThat(
            TranscriptionTextJoiner.join(listOf(" 第一段 ", "第二段"), SttLanguage.Chinese),
        ).isEqualTo("第一段第二段")
    }

    @Test
    fun `should join non-Chinese chunks with spaces`() {
        assertThat(
            TranscriptionTextJoiner.join(listOf(" first ", "second"), SttLanguage.English),
        ).isEqualTo("first second")
    }

    @Test
    fun `should ignore blank chunks`() {
        assertThat(
            TranscriptionTextJoiner.join(listOf("", "  ", "text"), SttLanguage.Auto),
        ).isEqualTo("text")
    }
}
