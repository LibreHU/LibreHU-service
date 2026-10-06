package org.librehu.service

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.librehu.service.display.DisplayController
import org.librehu.service.touch.TouchCalibrationActivity
import org.librehu.service.ui.AppActions
import org.librehu.service.ui.AppScreen
import org.librehu.service.ui.CarColors
import org.librehu.service.ui.CarPalette
import org.librehu.service.ui.CarTheme
import org.librehu.service.ui.ServiceClient
import org.librehu.service.ui.Tab
import org.librehu.service.ui.ThemeFollower
import org.librehu.service.ui.exportCanVehicle
import org.librehu.service.ui.exportProfile
import org.librehu.service.ui.importCanVehicle
import org.librehu.service.ui.importProfile
import org.librehu.service.ui.profileFileName

/**
 * LibreHU settings, Android Auto style: one tab per function (audio, Bluetooth, OBD, display, GPS / clock, touch
 * panel, MCU protocol) plus
 * diagnostics. Talks to the service through its public API.
 */
class MainActivity : ComponentActivity() {
    private lateinit var client: ServiceClient
    private lateinit var theme: ThemeFollower
    private val tab = mutableStateOf(Tab.HOME)
    private var exporting: String? = null

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) toast(importProfile(this, uri))
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = exporting
            exporting = null
            if (uri != null && id != null) toast(exportProfile(this, id, uri))
        }

    private var exportingVehicle: String? = null

    private val canImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) toast(importCanVehicle(this, uri))
        }

    private val canExportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = exportingVehicle
            exportingVehicle = null
            if (uri != null && id != null) toast(exportCanVehicle(this, id, uri))
        }

    private val logLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(ServiceState.dump().toByteArray()) }
                toast(getString(R.string.diag_exported))
            } catch (e: Exception) {
                toast(getString(R.string.mcu_import_error, e.message ?: e.javaClass.simpleName))
            }
        }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { client.refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = ServiceClient(this)
        theme =
            ThemeFollower(this) { dark, accent ->
                CarColors.palette = CarPalette.of(dark, if (accent != 0) Color(accent) else null)
            }
        theme.start()
        savedInstanceState?.getString(STATE_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name }?.let { tab.value = it } }
        openTab(intent)

        val actions =
            AppActions(
                importProfile = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                exportProfile = { id ->
                    exporting = id
                    exportLauncher.launch(profileFileName(id))
                },
                requestBtPermissions = {
                    permissions.launch(
                        arrayOf(
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                },
                openWriteSettings = { startActivity(DisplayController.get(this).writeSettingsIntent()) },
                restartLink = { LibreHuService.start(this, restart = true) },
                openLocationSettings = { openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
                openDateSettings = { openSettings(Settings.ACTION_DATE_SETTINGS) },
                calibrateTouch = { startActivity(Intent(this, TouchCalibrationActivity::class.java)) },
                importCanVehicle = { canImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                exportCanVehicle = { id ->
                    exportingVehicle = id
                    canExportLauncher.launch("$id.json")
                },
                exportLog = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
                    logLauncher.launch("librehu-$stamp.log")
                },
            )
        setContent {
            CarTheme {
                AppScreen(tab.value, { tab.value = it }, client, actions)
            }
        }
        client.bind()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTab(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.value.name)
    }

    override fun onDestroy() {
        client.unbind()
        theme.stop()
        super.onDestroy()
    }

    private fun openTab(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        Tab.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { tab.value = it }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: android.content.ActivityNotFoundException) {
            toast(e.message ?: action)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        /** Tab to open: a [Tab] name (`OBD`, `BLUETOOTH`…). */
        const val EXTRA_TAB = "tab"
        const val TAB_OBD = "OBD"
        private const val STATE_TAB = "tab"
    }
}
