# Project #1 — WebRTC Camera Preview

A learning-focused reference for the code in this repo. No signaling server, no
SDP offer/answer, no STUN/TURN, no peer-to-peer calling — just the local
capture pipeline: **Android Camera → CameraVideoCapturer → VideoSource →
VideoTrack → SurfaceViewRenderer → Jetpack Compose**.

---

## 1. What we are building

An Android app that opens the device camera through WebRTC's own camera
capture APIs and renders the live feed on screen — nothing else. This is
deliberately *not* a video-calling app. There is no network connection
anywhere in this project: every object created here (`PeerConnectionFactory`,
`VideoSource`, `VideoTrack`) lives entirely on the local device. The goal is
to get comfortable with the four building blocks every later WebRTC project
(one-way streaming, then full two-way calling) is built on top of.

## 2. Architecture

```
Compose UI (CameraPreviewScreen)
        │
        ▼
ViewModel (CameraPreviewViewModel) — StateFlow<CameraPreviewUiState>
        │
        ▼
WebRTC Manager (WebRtcManager)
        │
        ├── PeerConnectionFactory
        ├── VideoSource
        ├── VideoTrack
        └── CameraVideoCapturer
```

`WebRtcManager` is the only class that imports `org.webrtc.*` types for
*creation and lifecycle* purposes. The ViewModel exposes just enough of that
(`eglBaseContext`, `attachSink`/`detachSink`, and UI state) for the Compose
layer to render a preview, without ever calling into `PeerConnectionFactory`
or the capturer directly.

## 3. Project structure

```
app/src/main/java/com/mrashidcit/project1_webrtccameraapp/
├── webrtc/
│   └── WebRtcManager.kt
├── presentation/
│   ├── CameraPreviewUiState.kt
│   ├── CameraPreviewViewModel.kt
│   └── CameraPreviewScreen.kt
├── ui/theme/                      (existing Compose theme, unchanged)
└── MainActivity.kt
```

## 4. Gradle dependencies

**Dependency chosen:** [`io.getstream:stream-webrtc-android`](https://central.sonatype.com/artifact/io.getstream/stream-webrtc-android)
(latest stable at time of writing: **1.3.10**).

Why this one and not `org.webrtc:google-webrtc`: Google stopped publishing
prebuilt WebRTC AARs to Maven Central years ago. `stream-webrtc-android` is
an actively maintained mirror that repackages Google's official WebRTC
native library (`libwebrtc`) with the same `org.webrtc.*` Kotlin/Java API
surface (`PeerConnectionFactory`, `VideoTrack`, `SurfaceViewRenderer`, camera
capturers, etc.), so everything in this guide maps directly onto the
"official" WebRTC API described in Google's own documentation. It's the
dependency most current Android WebRTC tutorials and open-source projects use.

**Before you build**, re-check
<https://central.sonatype.com/artifact/io.getstream/stream-webrtc-android>
for a newer stable release — WebRTC ships new versions frequently, and
`gradle/libs.versions.toml` should be bumped if one exists.

`gradle/libs.versions.toml` (relevant additions):

```toml
[versions]
lifecycle = "2.11.0"
webrtc = "1.3.10"

[libraries]
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
stream-webrtc-android = { group = "io.getstream", name = "stream-webrtc-android", version.ref = "webrtc" }
```

`app/build.gradle.kts` (relevant additions):

```kotlin
dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose)   // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel() in Compose
    implementation(libs.stream.webrtc.android)                // org.webrtc.* classes
    // ...existing Compose/AndroidX dependencies unchanged
}
```

No Hilt, Retrofit, Room, Firebase, or Accompanist were added. Permission
handling uses the built-in `androidx.activity.compose.rememberLauncherForActivityResult`
instead of `accompanist-permissions`, since that's all a single permission
needs.

## 5. AndroidManifest permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
```

`required="false"` is deliberate: it lets the app install on an emulator or
device without a camera (useful while learning) instead of being filtered
out by the Play Store. The app still handles "no camera found" gracefully
at runtime (see `WebRtcManager.pickCameraDeviceName`) rather than relying on
the manifest to prevent that case.

## 6. WebRTC initialization

Every WebRTC app, no matter how simple, starts the same way:

```kotlin
PeerConnectionFactory.initialize(
    PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
)

val factory = PeerConnectionFactory.builder()
    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
    .createPeerConnectionFactory()
```

`PeerConnectionFactory.initialize()` is a **static, process-wide, one-time**
call — it loads WebRTC's native (C++) libraries via JNI and sets process
options. `PeerConnectionFactory.builder().createPeerConnectionFactory()`
then creates the actual factory instance, which spins up WebRTC's internal
signaling/worker/network threads. See `WebRtcManager.ensurePeerConnectionFactoryInitialized()`.

## 7. WebRtcManager implementation

See `app/src/main/java/.../webrtc/WebRtcManager.kt`. Responsibilities:

- Owns a single `EglBase` (shared GL/EGL context) for the whole pipeline.
- `ensurePeerConnectionFactoryInitialized()` — step 6 above.
- `startCameraCapture(preferFrontCamera)` — builds
  `CameraVideoCapturer → VideoSource → VideoTrack` and returns a
  `Result<VideoTrack>` so failures (no camera, capturer creation failed)
  surface as data instead of exceptions crossing into the UI layer.
- `stopCameraCapture()` — stops the capturer without destroying the factory,
  so the camera can be restarted without re-running step 6.
- `release()` — full teardown, called once, in dependency order.

## 8. CameraPreviewViewModel

See `.../presentation/CameraPreviewViewModel.kt`. An `AndroidViewModel` that:

- Owns one `WebRtcManager` instance for its whole lifetime.
- Exposes `uiState: StateFlow<CameraPreviewUiState>` — the single source of
  truth Compose observes.
- Runs `ensurePeerConnectionFactoryInitialized()` + `startCameraCapture()` on
  `Dispatchers.Default` inside `viewModelScope`, since opening a camera talks
  to hardware and native code and must never block the main thread.
- Exposes `attachSink` / `detachSink` so Compose can wire a renderer to the
  live track without ever touching `WebRtcManager` or `VideoTrack` directly.
- Tears everything down in `onCleared()`.

## 9. CameraPreviewScreen

See `.../presentation/CameraPreviewScreen.kt`. Responsibilities:

- Checks/requests `android.permission.CAMERA` via
  `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`,
  distinguishing granted / denied / permanently-denied.
- Renders one of four states: asking for permission, permanently-denied
  (with a button to the app's system settings page), starting/loading,
  running (with a `Stop Camera` button), or error (with a `Retry` button).
- Bridges the classic View-based `SurfaceViewRenderer` into Compose with
  `AndroidView`, attaching it as a `VideoSink` in `factory` and detaching +
  releasing it in `onRelease` so a destroyed view is never written to.

## 10. MainActivity integration

`MainActivity` is intentionally thin — it just applies the app theme and
hosts `CameraPreviewScreen()`:

```kotlin
setContent {
    Project1WebRTCCameraAppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CameraPreviewScreen()
        }
    }
}
```

## 11. Complete execution flow

1. `MainActivity` composes `CameraPreviewScreen`.
2. The screen checks the camera permission. If already granted, it tells the
   ViewModel immediately; otherwise it shows a rationale screen with a
   **Grant Camera Permission** button.
3. Once permission is granted, `CameraPreviewViewModel.startCamera()` runs
   on a background dispatcher:
   - `WebRtcManager.ensurePeerConnectionFactoryInitialized()` creates the
     `PeerConnectionFactory` (once).
   - `WebRtcManager.startCameraCapture()` enumerates cameras, prefers the
     front camera, creates a `CameraVideoCapturer`, wires it to a new
     `VideoSource`, starts capturing at 1280×720@30fps, and creates a
     `VideoTrack` from that source.
4. UI state flips to `Running`. Compose now shows a `SurfaceViewRenderer`
   inside an `AndroidView`; on creation it calls `init(eglBaseContext, ...)`
   and `viewModel.attachSink(this)`, which calls `videoTrack.addSink(renderer)`.
5. From that point, every camera frame flows: Camera hardware → Camera2/
   Camera1 capture session → `CameraVideoCapturer` → `VideoSource.capturerObserver`
   → `VideoTrack` → the `SurfaceViewRenderer` sink → drawn on screen — with
   Compose only responsible for placing that `AndroidView` in the layout.
6. Tapping **Stop Camera** calls `stopCameraCapture()`; the pipeline can be
   restarted with **Start Camera**.
7. When the screen/activity is destroyed, `onCleared()` releases everything.

## 12. WebRTC concepts explained

### PeerConnectionFactory
The root factory object of the whole native WebRTC stack. It must be
initialized (`PeerConnectionFactory.initialize`) and built exactly once per
process before anything else — capturers, sources, tracks, or (in a later
project) `PeerConnection` instances — can be created. It owns WebRTC's
internal signaling/worker/network threads and is the thing that ultimately
creates every other WebRTC object in this codebase.

### CameraVideoCapturer
WebRTC's abstraction over the Android camera. Concretely it's either a
`Camera2Capturer` (modern `android.hardware.camera2` API, used when
`Camera2Enumerator.isSupported()` is true) or a `Camera1Capturer` (the
legacy `android.hardware.Camera` API, as a fallback). It opens the physical
camera, receives frames from the OS camera pipeline, and forwards each one
to whatever `CapturerObserver` it was initialized with — in this project,
that observer belongs to our `VideoSource`.

### VideoSource
The WebRTC-side receiving end of camera frames. `VideoSource.capturerObserver`
is what a capturer pushes frames into; internally the source turns that
stream of frames into something any number of `VideoTrack`s can share. You
only ever need one `VideoSource` per capturer, even if you later attach the
resulting track to more than one place.

### VideoTrack
A lightweight, shareable, named handle onto a `VideoSource`. Where a
`VideoSource` is "the pipe carrying frames," a `VideoTrack` is "a labeled
tap on that pipe" — you can add multiple sinks (renderers, or in a real
call, an outgoing `PeerConnection`) to the same track, enable/disable it, or
dispose it independently of the source's lifecycle bookkeeping. This is the
object that would be added to a `PeerConnection` in a video-calling project;
here it goes straight to a renderer instead.

### SurfaceViewRenderer
A classic Android `View` (specifically a `SurfaceView` subclass) that knows
how to receive `VideoFrame`s (as a `VideoSink`) and draw them using OpenGL
ES, using the same shared `EglBase.Context` the capture pipeline uses. It is
required because a `VideoTrack` on its own has no visual representation —
something has to implement `VideoSink.onFrame()` and actually render pixels,
and `SurfaceViewRenderer` is WebRTC's ready-made implementation of that.
Since it's a `View` and not a Composable, `AndroidView` is what lets Compose
host it.

### The full pipeline

```
Android Camera
      ↓  (hardware frames via Camera2/Camera1 API)
CameraVideoCapturer
      ↓  (capturer.startCapture → source.capturerObserver.onFrameCaptured)
VideoSource
      ↓  (factory.createVideoTrack(id, source))
VideoTrack
      ↓  (videoTrack.addSink(renderer))
SurfaceViewRenderer
      ↓  (AndroidView bridges the View into the composition)
Jetpack Compose
```

## 13. Resource / lifecycle cleanup

Cleanup order in `WebRtcManager.release()` matters and mirrors creation in
reverse:

1. **`stopCameraCapture()`** — stops the camera hardware first. Skipping
   this and disposing the capturer directly can leave the Camera2 session in
   a bad state for the next app that tries to open it.
2. **Dispose track → source → capturer → `SurfaceTextureHelper`** — each of
   these holds native resources and, in the capturer's case, a reference to
   the physical camera; disposing in this order releases dependents before
   what they depend on.
3. **Dispose `PeerConnectionFactory`** — releases native threads and memory
   backing every WebRTC object that came from it.
4. **Release `EglBase`** — only after everything that borrowed its GL
   context is gone, since releasing it earlier would leave the renderer/
   capturer holding a dangling GL context.

On the Compose side, `AndroidView`'s `onRelease` callback detaches the
renderer as a sink and calls `renderer.release()` the moment the
`SurfaceViewRenderer` leaves composition — so it's never possible to draw
into (or hold a sink pointing at) a destroyed view. `CameraPreviewViewModel.onCleared()`
is the single place the whole pipeline is torn down, which is what
Android guarantees runs when the owning screen is gone for good (not just
recomposed), so nothing is released too early or leaked.

## 14. Common mistakes this project avoids

- **Creating a new `PeerConnectionFactory` on every recomposition** — it's
  created once in `WebRtcManager` and guarded by a null-check in
  `ensurePeerConnectionFactoryInitialized()`.
- **Using a different `EglBase.Context` for the renderer than the one the
  capturer/encoder used** — there is exactly one `EglBase` per
  `WebRtcManager`, and its context is threaded through everywhere.
- **Calling WebRTC/camera APIs on the main thread** — capture start/stop
  runs via `viewModelScope.launch(Dispatchers.Default)`.
- **Leaking the camera** — every path (stop, error, ViewModel cleared)
  eventually calls into `release()`/`stopCameraCapture()`.
- **Rendering into a disposed `SurfaceViewRenderer`** — handled by
  `onRelease` in `AndroidView`.
- **Crashing on permission denial** — three distinct states (`Denied`,
  `PermanentlyDenied`, `Unknown`) are modeled and shown, none of them a crash.
- **Assuming every device has a front camera** — `pickCameraDeviceName`
  falls back to *any* available camera, and to a clear `Error` state if
  there is none.

## 15. How to test it on a physical Android device

1. Open the project in Android Studio (this repo already has a working
   Compose project scaffold — `MainActivity`, `webrtc/`, and `presentation/`
   packages are what this feature adds).
2. Plug in an Android phone (minSdk 24+) with USB debugging enabled, or
   pick it from the device dropdown.
3. Press **Run**. On first launch you'll see the permission screen — tap
   **Grant Camera Permission** and accept the system dialog.
4. You should see your own live camera feed (front camera by default,
   mirrored). Tap **Stop Camera** / **Start Camera** to test the pipeline
   restarting.
5. To exercise the denial paths: uninstall and reinstall (or clear app
   storage) and tap **Deny** on the permission dialog once to see the
   "Denied" screen, or deny it twice (or check "Don't ask again") to see the
   "permanently denied" screen and confirm the **Open App Settings** button
   takes you to the right settings page.
6. An emulator works too as long as it has a **virtual camera** configured
   (AVD Manager → your device → Front/Back Camera → *Emulated* or
   *Webcam0*); a "Webcam" backend lets you test with your computer's camera.

Since this dependency wasn't in the project before, the very first build
will download the WebRTC AAR (tens of MB) — make sure the machine running
the build has network access to Maven Central.

## 16. WebRTC concepts learned

- What `PeerConnectionFactory` is and why every WebRTC object traces back to it.
- How `EglBase` shares a single GL context across the capturer, encoder/
  decoder factories, and the renderer.
- How `CameraVideoCapturer` (Camera2 vs Camera1) turns physical camera
  hardware into frames.
- The relationship `CameraVideoCapturer → VideoSource → VideoTrack`, and why
  a track is a separate, shareable object from the source.
- Why `SurfaceViewRenderer` exists and how `VideoSink`/`addSink` connects a
  track to something that actually draws pixels.
- How to bridge a classic Android `View` into Jetpack Compose safely with
  `AndroidView`'s `factory`/`onRelease`.
- Modeling multi-state permission handling (`Denied` vs `PermanentlyDenied`)
  without crashing.
- Ordering WebRTC teardown correctly to avoid leaks.

## Practice exercises

1. **Camera switch button** *(Camera enumeration / capturer lifecycle)* —
   Add a button that calls `stopCameraCapture()`, then `startCameraCapture(preferFrontCamera = false)`
   (or toggle a stored boolean) to flip between front and back camera
   without restarting the whole `PeerConnectionFactory`. This tests whether
   you understand which parts of `WebRtcManager` can be torn down and
   rebuilt independently of the factory.

2. **Live capture-format switcher** *(CameraVideoCapturer capture
   parameters)* — Add UI (e.g. a dropdown: 640×480, 1280×720, 1920×1080) and
   call `videoCapturer.changeCaptureFormat(width, height, fps)` while
   running. This exercises the difference between *creating* a capturer and
   *reconfiguring* one that's already capturing.

3. **Frame counter / FPS overlay** *(VideoSink / VideoFrame)* — Instead of
   (or in addition to) attaching only the `SurfaceViewRenderer` as a sink,
   attach a second, custom `VideoSink` that just counts `onFrame()` calls
   per second and shows it as a Compose `Text` overlay. This is the most
   direct way to see that a `VideoTrack` can fan out to multiple sinks
   simultaneously, and that a sink is just "anything with an `onFrame`
   callback" — not something special about `SurfaceViewRenderer`.

4. **Local video recording to MP4** *(VideoSink → external consumer)* —
   Add a second sink that feeds frames into a `MediaRecorder`/`MediaCodec`
   pipeline (or a small third-party recorder) to save the camera preview to
   a file, entirely offline. This pushes you to understand `VideoFrame`'s
   raw buffer format (`I420`/texture) since you'll need to convert it for
   the encoder, deepening the "what actually is a frame" understanding.

5. **Two independent camera previews on one screen** *(Multiple
   captor/source/track sets)* — Extend `WebRtcManager` (or run two
   instances) to open two cameras at once (e.g. front + back, or two
   external USB cameras) and show both previews side-by-side in Compose.
   This is the closest this project gets to "multiple video tracks," which
   is exactly the shape a future multi-participant calling project will
   need — except still with zero networking.

---

*This document lives in `docs/WEBRTC_CAMERA_PREVIEW_GUIDE.md` in this
project for future reference.*
