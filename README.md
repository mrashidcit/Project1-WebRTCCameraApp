# WebRTC Camera Preview

An Android learning project that captures the device camera through WebRTC and
shows a live local preview in Jetpack Compose.

## Summary

This project demonstrates the foundation of an Android WebRTC video pipeline.
After camera permission is granted, it opens the preferred device camera,
captures frames with WebRTC, creates a local `VideoTrack`, and renders that
track on screen.

```text
Android Camera
    -> CameraVideoCapturer
    -> VideoSource
    -> VideoTrack
    -> SurfaceViewRenderer
    -> Jetpack Compose UI
```

## Purpose

The purpose is to learn WebRTC's local media building blocks before adding the
complex parts of a real video call.

It focuses on:

- Requesting and handling Android camera permission.
- Capturing frames through `CameraVideoCapturer`.
- Connecting a `VideoSource` to a `VideoTrack`.
- Rendering the track with `SurfaceViewRenderer` inside Compose.
- Managing WebRTC and camera lifecycles cleanly through a ViewModel and a
  dedicated `WebRtcManager`.
- Sharing one `EglBase` graphics context so frames can move efficiently between
  the camera and renderer on the GPU.

## What this project is not

This is deliberately **not** a video-calling application. It does not include:

- A signaling server.
- SDP offer/answer exchange.
- STUN or TURN servers.
- A remote `PeerConnection`.
- Network transmission or remote video playback.

The `VideoTrack` stays on the device and is attached directly to the local
preview renderer. In a future calling project, that same track could also be
added to a `PeerConnection` and sent to another participant.

## Architecture

```text
CameraPreviewScreen (Compose UI)
        |
        v
CameraPreviewViewModel (UI state and lifecycle)
        |
        v
WebRtcManager (WebRTC camera pipeline)
        |
        +-- PeerConnectionFactory
        +-- EglBase
        +-- CameraVideoCapturer
        +-- VideoSource
        +-- VideoTrack
```

`WebRtcManager` owns WebRTC object creation and cleanup. The ViewModel starts
and stops capture on a background dispatcher, exposes UI state, and connects
the renderer to the video track. The Compose screen requests permission and
hosts the native `SurfaceViewRenderer` using `AndroidView`.

## Main flow

1. The app displays `CameraPreviewScreen`.
2. The user grants camera permission.
3. The ViewModel initializes `PeerConnectionFactory` and starts camera capture.
4. `WebRtcManager` creates `CameraVideoCapturer -> VideoSource -> VideoTrack`.
5. Compose creates a `SurfaceViewRenderer` and attaches it as a `VideoSink`.
6. Camera frames are rendered as the local preview.
7. Stopping the camera stops capture; clearing the ViewModel releases WebRTC
   resources.

## Tech stack

- Kotlin
- Jetpack Compose and Material 3
- Android ViewModel, StateFlow, and lifecycle-aware state collection
- WebRTC through `io.getstream:stream-webrtc-android`
- Camera2 with Camera1 fallback through WebRTC's camera enumerators

## Run the project

1. Open the project in Android Studio.
2. Sync Gradle dependencies.
3. Run on an Android device or emulator with a usable camera.
4. Grant camera permission when prompted.
5. The local WebRTC camera preview should appear.

## Documentation

- [Detailed camera preview guide](docs/WEBRTC_CAMERA_PREVIEW_GUIDE.md)
- [Interview cheat sheet and pipeline diagram](docs/WEBRTC_CAMERA_INTERVIEW_CHEATSHEET.md)
