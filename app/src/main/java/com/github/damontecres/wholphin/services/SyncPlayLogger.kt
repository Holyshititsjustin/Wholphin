package com.github.damontecres.wholphin.services

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logger for SyncPlay sessions that captures all relevant events and commands
 * and can upload the log to the Jellyfin server when the session ends.
 */
@Singleton
class SyncPlayLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var currentLogFile: File? = null
    private var fileWriter: FileWriter? = null
    private var sessionStartTime: Long = 0
    private var isLogging = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Start logging a new SyncPlay session
     */
    fun startSession(groupId: String) {
        if (isLogging) {
            Timber.w("SyncPlay logging already active, stopping previous session")
            stopSession()
        }

        sessionStartTime = System.currentTimeMillis()
        val timestamp = dateFormat.format(Date(sessionStartTime))
        val filename = "syncplay_log_${groupId}_${sessionStartTime}.txt"
        currentLogFile = File(context.filesDir, filename)

        try {
            fileWriter = FileWriter(currentLogFile, false) // Overwrite if exists
            fileWriter?.write("SyncPlay Session Log\n")
            fileWriter?.write("Group ID: $groupId\n")
            fileWriter?.write("Started: $timestamp\n")
            fileWriter?.write("Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})\n")
            fileWriter?.write("Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            fileWriter?.write("Wholphin Version: ${com.github.damontecres.wholphin.BuildConfig.VERSION_NAME}\n")
            fileWriter?.write("========================================\n\n")
            fileWriter?.flush()
            isLogging = true
            Timber.i("Started SyncPlay logging to ${currentLogFile?.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start SyncPlay logging")
            currentLogFile = null
            fileWriter = null
        }
    }

    /**
     * Log a message to the current session log
     */
    fun log(message: String) {
        if (!isLogging || fileWriter == null) return

        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message\n"

        try {
            fileWriter?.write(logLine)
            fileWriter?.flush()
        } catch (e: Exception) {
            Timber.e(e, "Failed to write to SyncPlay log")
        }
    }

    /**
     * Stop the current logging session and upload the log to Jellyfin
     */
    fun stopSession() {
        if (!isLogging) return

        val logFile = currentLogFile
        val endTime = System.currentTimeMillis()
        val duration = endTime - sessionStartTime

        log("Session ended. Duration: ${duration}ms")

        try {
            fileWriter?.close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to close SyncPlay log file")
        }

        fileWriter = null
        isLogging = false

        // Upload the log to Jellyfin server
        logFile?.let { file ->
            CoroutineScope(Dispatchers.IO).launch {
                uploadLogToServer(file, duration)
            }
        }

        Timber.i("Stopped SyncPlay logging")
    }

    /**
     * Upload the log file to the Jellyfin server
     * Since Jellyfin doesn't have a direct client log upload API, we'll keep it local for now
     * TODO: Implement proper log upload when Jellyfin adds client log API
     */
    private suspend fun uploadLogToServer(
        logFile: File,
        duration: Long
    ) {
        try {
            // For now, just log that the session completed
            Timber.i("SyncPlay session log saved locally: ${logFile.absolutePath} (duration: ${duration}ms)")
            
            // In the future, this could upload to Jellyfin server when they add a client log API
            // For now, users can manually access logs from device storage
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to process SyncPlay log")
        }
    }

    /**
     * Get the current log file path (for debugging)
     */
    fun getCurrentLogPath(): String? = currentLogFile?.absolutePath

    /**
     * Check if logging is currently active
     */
    fun isActive(): Boolean = isLogging
}