package com.mrashidcit.project1_webrtccameraapp.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Owns every native WebRTC object needed to capture the device camera and
 * expose it as a [VideoTrack]. This class deliberately knows nothing about
 * signaling, peer connections to a remote party, or Jetpack Compose — it is
 * a small, focused wrapper around the local media pipeline:
 *
 *   Android Camera -> CameraVideoCapturer -> VideoSource -> VideoTrack
 *
 * Every WebRTC lifecycle rule (initialize before use, dispose in the
 * reverse order of creation) is enforced here so the rest of the app never
 * touches raw WebRTC objects directly.
 */
class WebRtcManager(private val appContext: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "local_camera_track"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
    }

    // EglBase wraps a shared OpenGL ES / EGL context. Camera frames arrive as
    // GPU textures; the camera capturer, the encoder/decoder factories, and
    // the SurfaceViewRenderer all must share this same context so frames can
    // be handed between them without an expensive GPU-to-CPU-to-GPU copy.
    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null

    var videoTrack: VideoTrack? = null
        private set

    private var isReleased = false

    /**
     * PeerConnectionFactory is the root object of the whole WebRTC native
     * library. Nothing else — capturers, sources, tracks, or (in a later
     * project) peer connections — can be created before it exists, because
     * it owns the native signaling/worker/network threads every other
     * WebRTC object schedules work on.
     *
     * [PeerConnectionFactory.initialize] must run exactly once per process
     * before the first factory is built: it loads the native WebRTC
     * libraries and configures process-wide options.
     */
    fun ensurePeerConnectionFactoryInitialized() {
        if (peerConnectionFactory != null) return

        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(appContext)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // The encoder/decoder factories receive the shared EGL context so
        // hardware codecs can read frames directly from GPU memory. This
        // project never actually encodes/decodes a remote stream, but
        // PeerConnectionFactory.builder() still requires both factories.
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Builds the CameraVideoCapturer -> VideoSource -> VideoTrack chain:
     *
     * - CameraVideoCapturer opens the Android camera (via Camera2 when
     *   supported, Camera1 otherwise) and pushes captured frames onward.
     * - VideoSource is the WebRTC-side endpoint that receives those frames
     *   through its [org.webrtc.CapturerObserver] and turns them into a
     *   stream any number of tracks can share.
     * - VideoTrack is a lightweight, shareable handle onto that VideoSource;
     *   it is what gets attached to a renderer (or, in a real call, sent
     *   over a PeerConnection).
     *
     * Returns [Result.success] with the created [VideoTrack], or
     * [Result.failure] describing what went wrong (no camera present, the
     * capturer could not be created, etc.) so the caller can show an error
     * state instead of crashing.
     */
    fun startCameraCapture(preferFrontCamera: Boolean = true): Result<VideoTrack> {
        val factory = peerConnectionFactory
            ?: return Result.failure(IllegalStateException("PeerConnectionFactory not initialized"))

        return try {
            val enumerator = createCameraEnumerator()
            val cameraName = pickCameraDeviceName(enumerator, preferFrontCamera)
                ?: return Result.failure(IllegalStateException("No usable camera found on this device"))

            val capturer = enumerator.createCapturer(cameraName, null)
                ?: return Result.failure(IllegalStateException("Could not create a capturer for camera: $cameraName"))

            val textureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)
            val source = factory.createVideoSource(capturer.isScreencast)

            capturer.initialize(textureHelper, appContext, source.capturerObserver)
            capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

            val track = factory.createVideoTrack(VIDEO_TRACK_ID, source)
            track.setEnabled(true)

            videoCapturer = capturer
            surfaceTextureHelper = textureHelper
            videoSource = source
            videoTrack = track

            Result.success(track)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start camera capture", t)
            releaseCaptureResources()
            Result.failure(t)
        }
    }

    /** Stops the camera without tearing down the factory, so it can be restarted later. */
    fun stopCameraCapture() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping camera capture", e)
        }
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(
            object: CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(p0: Boolean) {
                    Unit
                }

                override fun onCameraSwitchError(p0: String?) {
                    Unit
                }

            },
        )
    }


    /** Disposes capturer/source/track but keeps the PeerConnectionFactory alive for a restart. */
    private fun releaseCaptureResources() {
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    /**
     * Full teardown, called once when the owning ViewModel is cleared. Order
     * matters: stop capture first, dispose everything that depends on the
     * factory and the EGL context, dispose the factory itself, and only
     * then release the EGL context that everything above borrowed.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        stopCameraCapture()
        releaseCaptureResources()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
    }

    private fun createCameraEnumerator(): CameraEnumerator =
        if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(/* captureToTexture = */ true)
        }

    private fun pickCameraDeviceName(enumerator: CameraEnumerator, preferFront: Boolean): String? {
        val deviceNames = enumerator.deviceNames
        val preferred = deviceNames.firstOrNull { enumerator.isFrontFacing(it) == preferFront }
        return preferred ?: deviceNames.firstOrNull()
    }
}
