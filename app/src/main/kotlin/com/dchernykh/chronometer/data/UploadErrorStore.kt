package com.dchernykh.chronometer.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UploadError(
    val workId: String,
    val message: String,
)

/** Keeps the last error across retries and process restarts, tied to its work id. */
class UploadErrorStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("upload_diagnostics", Context.MODE_PRIVATE)
    private val errorState =
        MutableStateFlow(
            prefs.getString("message", null)?.let {
                UploadError(prefs.getString("work_id", "").orEmpty(), it)
            },
        )
    val error: StateFlow<UploadError?> = errorState.asStateFlow()

    fun record(
        workId: String,
        message: String,
    ) {
        prefs.edit {
            putString("work_id", workId)
            putString("message", message)
        }
        errorState.value = UploadError(workId, message)
    }
}
