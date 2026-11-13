package it.agrilloit.turegistri.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import it.agrilloit.turegistri.record.RecordingEngine
import it.agrilloit.turegistri.ui.theme.YouRecTheme

/**
 * Activity unica di YouRec (Jetpack Compose): gestisce permessi e navigazione
 * tra Record (camera), Projects (dashboard) ed Editor (timeline).
 */
class YouRecActivity : ComponentActivity() {

    val engine by lazy { RecordingEngine.get(applicationContext) }

    private var permissionsGranted by mutableStateOf(false)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = it.getOrDefault(Manifest.permission.CAMERA, false) &&
                it.getOrDefault(Manifest.permission.RECORD_AUDIO, false)
            if (permissionsGranted) onPermissionsReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = hasPermissions()
        setContent {
            YouRecTheme {
                YouRecApp(activity = this, permissionsGranted = permissionsGranted, onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    )
                })
            }
        }
        if (permissionsGranted) onPermissionsReady()
        else permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun onPermissionsReady() {
        engine.ensureStarted()
        engine.startCameras()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Il motore sopravvive: qui non spegniamo nulla (la registrazione continua).
    }
}
