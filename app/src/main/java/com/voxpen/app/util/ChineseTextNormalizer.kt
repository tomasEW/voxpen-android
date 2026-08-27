package com.voxpen.app.util

import android.content.Context
import com.zqc.opencc.android.lib.ChineseConverter
import com.zqc.opencc.android.lib.ConversionType
import timber.log.Timber

object ChineseTextNormalizer {

    fun toMainlandSimplified(
        text: String,
        context: Context,
    ): String {
        if (text.isBlank()) return text

        return try {
            ChineseConverter.convert(
                text,
                ConversionType.TW2SP,
                context,
            )
        } catch (e: Exception) {
            Timber.w(e, "OpenCC TW2SP conversion failed")
            text
        }
    }
}
