package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myapplication.fft.FftController
import com.example.myapplication.ui.FftScreen
import com.example.myapplication.ui.ScreenCapture
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: FftController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = FftController(applicationContext)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                FftScreen(
                    controller = controller,
                    onCaptureScreen = { ScreenCapture.capture(this@MainActivity) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onHostStart()
    }

    override fun onStop() {
        controller.onHostStop()
        super.onStop()
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
