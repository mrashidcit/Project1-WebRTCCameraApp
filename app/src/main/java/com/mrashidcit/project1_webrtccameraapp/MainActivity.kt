package com.mrashidcit.project1_webrtccameraapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mrashidcit.project1_webrtccameraapp.presentation.CameraPreviewScreen
import com.mrashidcit.project1_webrtccameraapp.ui.theme.Project1WebRTCCameraAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Project1WebRTCCameraAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraPreviewScreen()
                }
            }
        }
    }
}
