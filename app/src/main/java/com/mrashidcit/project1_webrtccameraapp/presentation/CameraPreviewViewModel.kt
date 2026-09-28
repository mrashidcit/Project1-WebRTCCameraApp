package com.mrashidcit.project1_webrtccameraapp.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrashidcit.project1_webrtccameraapp.webrtc.WebRtcManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.VideoSink

/**
 * Holds UI state as a [StateFlow] and delegates all WebRTC work to
 * [WebRtcManager]. No org.webrtc type is exposed to Compose except the two
 * things a renderer literally cannot work without: the shared EGL context
 * (for [org.webrtc.SurfaceViewRenderer.init]) and the ability to attach
 * itself as a [VideoSink] on the live track.
 */
class CameraPreviewViewModel(
    application: Application,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AndroidViewModel(application) {

    private val webRtcManager = WebRtcManager(application.applicationContext)

    private val _uiState = MutableStateFlow(CameraPreviewUiState())
    val uiState: StateFlow<CameraPreviewUiState> = _uiState.asStateFlow()

    /** Shared EGL context that any SurfaceViewRenderer showing this track must init() with. */
    val eglBaseContext: EglBase.Context get() = webRtcManager.eglBaseContext

    fun onPermissionGranted() {
        _uiState.update { it.copy(permissionStatus = CameraPermissionStatus.Granted) }
        startCamera()
    }

    fun onPermissionDenied(permanently: Boolean) {
        _uiState.update {
            it.copy(
                permissionStatus = if (permanently) {
                    CameraPermissionStatus.PermanentlyDenied
                } else {
                    CameraPermissionStatus.Denied
                }
            )
        }
    }

    fun startCamera(camera: Camera = Camera.Front) {
        val current = _uiState.value.pipelineStatus
        if (current is CameraPipelineStatus.Running || current is CameraPipelineStatus.Starting) return

        _uiState.update { it.copy(pipelineStatus = CameraPipelineStatus.Starting) }

        viewModelScope.launch {
            // PeerConnectionFactory creation and camera capture touch native
            // code and hardware; keep them off the main thread so the UI
            // never freezes while the camera opens.
            val result = withContext(backgroundDispatcher) {
                webRtcManager.ensurePeerConnectionFactoryInitialized()
                webRtcManager.startCameraCapture(preferFrontCamera = camera.isFront)
            }
            _uiState.update {
                it.copy(
                    pipelineStatus = result.fold(
                        onSuccess = { CameraPipelineStatus.Running },
                        onFailure = { error ->
                            CameraPipelineStatus.Error(error.message ?: "Unknown camera error")
                        }
                    )
                )
            }
        }
    }

    fun stopCamera() {
        webRtcManager.stopCameraCapture()
        _uiState.update { it.copy(pipelineStatus = CameraPipelineStatus.Stopped) }
    }

    /** Attaches a renderer as a frame sink of the live video track, if one exists. */
    fun attachSink(sink: VideoSink) {
        webRtcManager.videoTrack?.addSink(sink)
    }

    fun detachSink(sink: VideoSink) {
        webRtcManager.videoTrack?.removeSink(sink)
    }

    fun switchCamera() {
        webRtcManager.switchCamera()
    }

    /**
     * Called by the framework when this ViewModel (and the screen that owns
     * it) is gone for good. This is the single place the whole WebRTC
     * pipeline is torn down, which avoids leaking the camera, native
     * threads, or the EGL context.
     */
    override fun onCleared() {
        webRtcManager.release()
        super.onCleared()
    }

    companion object {
        /**
         * [viewModel()][androidx.lifecycle.viewmodel.compose.viewModel]'s default
         * factory only knows how to call an `AndroidViewModel(Application)`
         * constructor via reflection - it can't see [backgroundDispatcher],
         * even though that parameter has a default value. Kotlin still
         * compiles a two-argument constructor, so that reflective lookup
         * fails with NoSuchMethodException. This factory constructs the
         * ViewModel directly, sidestepping the reflection entirely.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                CameraPreviewViewModel(application)
            }
        }
    }
}
