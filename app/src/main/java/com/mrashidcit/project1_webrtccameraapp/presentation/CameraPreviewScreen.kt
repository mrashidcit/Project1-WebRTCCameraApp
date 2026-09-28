package com.mrashidcit.project1_webrtccameraapp.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrashidcit.project1_webrtccameraapp.R
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Top-level screen: owns permission handling and switches between the
 * permission UI and the live camera pipeline. All WebRTC specifics stay
 * inside [CameraPreviewViewModel] / [org.webrtc] classes referenced here
 * only for the renderer AndroidView — no PeerConnectionFactory, capturer,
 * or track is ever touched directly by this Composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPreviewScreen(
    viewModel: CameraPreviewViewModel = viewModel(factory = CameraPreviewViewModel.Factory)
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Tracks whether we have asked for the permission at least once, so we
    // can tell "first-time denial" apart from "denied forever" — Android
    // reports the same shouldShowRequestPermissionRationale = false signal
    // both before the first request and after a permanent denial.
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            val canAskAgain = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
            } ?: false
            val permanentlyDenied = hasRequestedPermission && !canAskAgain
            viewModel.onPermissionDenied(permanently = permanentlyDenied)
        }
        hasRequestedPermission = true
    }

    // On first composition, check the permission we might already have
    // instead of blindly showing a request screen — the user may have
    // granted it in a previous session.
    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            viewModel.onPermissionGranted()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("WebRTC Camera Preview") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (uiState.permissionStatus) {
                CameraPermissionStatus.Granted -> CameraPipelineContent(
                    uiState = uiState,
                    viewModel = viewModel
                )

                CameraPermissionStatus.PermanentlyDenied -> PermissionPermanentlyDeniedContent(context)

                CameraPermissionStatus.Denied -> PermissionRationaleContent(
                    message = "Camera permission was denied. This app needs the camera to " +
                        "demonstrate the WebRTC capture pipeline.",
                    onRequestClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )

                CameraPermissionStatus.Unknown -> PermissionRationaleContent(
                    message = "This app captures your camera locally with WebRTC — no network " +
                        "calls, no other participants — purely to show how the capture pipeline works.",
                    onRequestClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
        }
    }
}

@Composable
private fun PermissionRationaleContent(message: String, onRequestClick: () -> Unit) {
    Text(text = message, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onRequestClick) {
        Text("Grant Camera Permission")
    }
}

@Composable
private fun PermissionPermanentlyDeniedContent(context: Context) {
    Text(
        text = "Camera permission was permanently denied. Enable it from the app's system " +
            "settings to continue.",
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }) {
        Text("Open App Settings")
    }
}

@Composable
private fun ColumnScope.CameraPipelineContent(
    uiState: CameraPreviewUiState,
    viewModel: CameraPreviewViewModel
) {

    var selectedCamera by remember {
        mutableStateOf(Camera.Front)
    }

    when (val status = uiState.pipelineStatus) {
        CameraPipelineStatus.Idle, CameraPipelineStatus.Starting -> {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Starting camera…")
        }

        is CameraPipelineStatus.Error -> {
            Text("Camera error: ${status.message}", textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { viewModel.startCamera() }) {
                Text("Retry")
            }
        }

        CameraPipelineStatus.Stopped -> {
            Row {
                Tab(
                    selected = selectedCamera.isFront,
                    onClick = {
                        selectedCamera = Camera.Front
                    },
                    modifier = Modifier
                        .height(50.dp)
                        .weight(1f),
                    selectedContentColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("Font")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Tab(
                    selected = selectedCamera.isBack,
                    onClick = {
                        selectedCamera = Camera.Back
                    },
                    modifier = Modifier
                        .height(50.dp)
                        .weight(1f),
                    selectedContentColor = MaterialTheme.colorScheme.primary
                ) {
                    Text("Back")
                }
            }


            Spacer(modifier = Modifier.height(8.dp))
            Text("Camera stopped.")
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { viewModel.startCamera(selectedCamera) }) {
                Text("Start Camera")
            }
        }

        CameraPipelineStatus.Running -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                CameraSurfaceRenderer(viewModel = viewModel)

                IconButton(
                    onClick = {
                        viewModel.switchCamera()
                    },
                    modifier = Modifier
                        .padding(
                            horizontal = 4.dp,
                            vertical = 4.dp
                        )
                        .align(Alignment.TopStart)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.outline_cameraswitch_24),
                        contentDescription = null,
                    )
                }

            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.stopCamera() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Camera")
            }
        }
    }
}

/**
 * Bridges the classic-View [SurfaceViewRenderer] into Compose via
 * [AndroidView]. The renderer is created once (factory), attached as a
 * [org.webrtc.VideoSink] of the live track so it starts receiving frames,
 * and cleanly detached + released when the composable leaves composition
 * (onRelease) so we never render into, or hold a sink reference to, a
 * destroyed view.
 */
@Composable
private fun CameraSurfaceRenderer(viewModel: CameraPreviewViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(viewModel.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setMirror(true) // Front camera preview reads naturally when mirrored.
                viewModel.attachSink(this)
            }
        },
        onRelease = { renderer ->
            viewModel.detachSink(renderer)
            renderer.release()
        }
    )
}
