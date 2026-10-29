package dev.abode.app.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * An InputMethodService cannot call requestPermissions(): it has no Activity to
 * receive the result. The mic key launches this transparent activity instead,
 * and on grant the keyboard resumes listening in the same field.
 *
 * Same app UID, so the grant covers the service.
 *
 * The launcher contract rather than overriding onRequestPermissionsResult: the
 * platform's callback signature has moved under androidx.activity, and the
 * contract is the shape that has not.
 */
class MicrophonePermissionActivity : ComponentActivity() {

    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            finishWith(if (granted) RESULT_OK else RESULT_CANCELED)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        if (granted) {
            finishWith(RESULT_OK)
            return
        }

        requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun finishWith(result: Int) {
        setResult(result)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
