package io.github.crossbowcraft13.openvoice.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.crossbowcraft13.openvoice.ai.ModelManager
import io.github.crossbowcraft13.openvoice.util.PermissionManager
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the first-run setup screen. Tracks permission state, resolves the
 * device-appropriate model recommendation, and runs the one-tap download.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val permissionManager: PermissionManager,
    private val modelManager: ModelManager
) : ViewModel() {

    enum class DownloadStatus { IDLE, DOWNLOADING, DONE, ERROR }

    var micGranted by mutableStateOf(false)
        private set
    var notificationGranted by mutableStateOf(false)
        private set
    var accessibilityGranted by mutableStateOf(false)
        private set

    var recommendedModel by mutableStateOf<ModelManager.RecommendedModel?>(null)
        private set
    var downloadStatus by mutableStateOf(DownloadStatus.IDLE)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var downloadError by mutableStateOf("")
        private set

    init {
        refreshPermissions()
        recommendedModel = modelManager.recommendModelForDevice()
    }

    /** Re-read live permission state (called on resume after settings trips). */
    fun refreshPermissions() {
        micGranted = permissionManager.isAudioGranted()
        notificationGranted = permissionManager.isNotificationGranted()
        accessibilityGranted = permissionManager.isAccessibilityEnabled()
    }

    /** Deep-link straight to OpenVoice's toggle inside Accessibility settings. */
    fun openAccessibilitySettings() = permissionManager.openAccessibilitySettings()

    fun downloadRecommendedModel() {
        val model = recommendedModel ?: return
        if (downloadStatus == DownloadStatus.DOWNLOADING) return
        viewModelScope.launch {
            downloadStatus = DownloadStatus.DOWNLOADING
            downloadProgress = 0f
            downloadError = ""
            val ok = modelManager.downloadAndActivate(model) { progress ->
                downloadProgress = progress
            }
            downloadStatus = if (ok) DownloadStatus.DONE else DownloadStatus.ERROR
            if (!ok) downloadError = "Download failed. Check your connection and try again."
        }
    }
}
