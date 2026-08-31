package com.mrashidcit.project1_webrtccameraapp.presentation

/** Camera permission state, as the OS/user has left it. */
enum class CameraPermissionStatus {
    /** We have not yet checked or asked. */
    Unknown,
    Granted,
    /** User said no, but we (or the system) may ask again. */
    Denied,
    /** User said no and checked "don't ask again" (or denied twice on some OEMs). */
    PermanentlyDenied
}

/** Lifecycle of the WebRTC camera pipeline itself, independent of permission. */
sealed interface CameraPipelineStatus {
    data object Idle : CameraPipelineStatus
    data object Starting : CameraPipelineStatus
    data object Running : CameraPipelineStatus
    data object Stopped : CameraPipelineStatus
    data class Error(val message: String) : CameraPipelineStatus
}

data class CameraPreviewUiState(
    val permissionStatus: CameraPermissionStatus = CameraPermissionStatus.Unknown,
    val pipelineStatus: CameraPipelineStatus = CameraPipelineStatus.Idle
)

enum class Camera {
    Front, Back;

    val isFront: Boolean
        get() = this == Front

    val isBack: Boolean
        get() = this == Back


}
