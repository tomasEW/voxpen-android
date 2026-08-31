package com.voxpen.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.voxpen.app.data.local.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** Writes INFO/WARN/ERROR logs to Downloads/VoxPen only when explicitly enabled in Settings. */
class DownloadLogTree(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
) : Timber.Tree() {
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.INFO && runBlocking { preferencesManager.downloadLoggingEnabledFlow.first() }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.ERROR -> "ERROR"
            Log.WARN -> "WARN"
            Log.INFO -> "INFO"
            else -> "LOG"
        }
        val safeMessage = redact(message)
        val stack = t?.let { "\n${redact(Log.getStackTraceString(it))}" }.orEmpty()
        val line = "${timestampFormat.format(Date())} [$level] ${tag ?: "VoxPen"}: $safeMessage$stack\n"

        synchronized(lock) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appendWithMediaStore(line) else appendLegacy(line)
            }.onFailure { Log.e("DownloadLogTree", "Unable to write support log", it) }
        }
    }

    private fun appendWithMediaStore(line: String) {
        val resolver = context.contentResolver
        val fileName = currentFileName()
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/VoxPen/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        } ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            },
        ) ?: error("Unable to create log file in Downloads")

        resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(line) }
            ?: error("Unable to open log file for append")
    }

    private fun appendLegacy(line: String) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "VoxPen")
        if (!dir.exists()) dir.mkdirs()
        FileOutputStream(File(dir, currentFileName()), true).bufferedWriter(Charsets.UTF_8).use { it.write(line) }
    }

    private fun currentFileName(): String = "voxpen-log-${dateFormat.format(Date())}.txt"

    private fun redact(value: String): String = value
        .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)\\b(gsk|sk)-[A-Za-z0-9_-]{12,}\\b"), "[REDACTED_API_KEY]")
}
