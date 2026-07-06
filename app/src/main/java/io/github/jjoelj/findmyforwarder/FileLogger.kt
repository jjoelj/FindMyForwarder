package io.github.jjoelj.findmyforwarder

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object FileLogger {
    private lateinit var file: File
    private const val LOGCAT_TAG = "FindMyForwarder"
    private val executor = Executors.newSingleThreadExecutor()

    private val _logFlow = MutableStateFlow<String?>(null)
    val logFlow: StateFlow<String?> = _logFlow
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "app.log")
        executor.execute {
            _logFlow.value = readFile()
        }
    }

    private fun readFile(): String? {
        return if (::file.isInitialized && file.exists()) file.readText() else null
    }

    private fun logToFile(message: String) {
        val timestamp = dateFormat.format(Date())
        val logMessage = "$timestamp — $message\n"

        val existingContent = readFile() ?: ""
        val newContent = existingContent + logMessage
        val lines = newContent.split("\n")
        val trimmedContent = lines.takeLast(500).joinToString("\n")
        file.writeText(trimmedContent)

        _logFlow.value = trimmedContent
    }

    fun d(message: String) {
        Log.d(LOGCAT_TAG, message)
        logToFile(message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        val fullMessage = throwable?.let { "$message - ${it.localizedMessage}" } ?: message
        Log.e(LOGCAT_TAG, fullMessage, throwable)
        logToFile("[ERROR]: $fullMessage")
    }

    fun w(message: String) {
        Log.w(LOGCAT_TAG, message)
        logToFile("[WARNING]: $message")
    }

    fun i(message: String) {
        Log.i(LOGCAT_TAG, message)
        logToFile("[INFO]: $message")
    }

    fun v(message: String) {
        Log.v(LOGCAT_TAG, message)
        logToFile("[VERBOSE]: $message")
    }

    fun getLog(): String? {
        return readFile()
    }
}