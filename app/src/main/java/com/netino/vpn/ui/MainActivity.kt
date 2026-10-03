package com.netino.vpn.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.netino.vpn.Locales
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server
import com.netino.vpn.service.VpnController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var afterPermission: (() -> Unit)? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) afterPermission?.invoke()
        afterPermission = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val qrScanner = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { importScanned(it) }
    }
    private val qrImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { QrImage.decode(this@MainActivity, uri) }
            if (text == null) Toast.makeText(this@MainActivity, R.string.qr_not_found, Toast.LENGTH_SHORT).show()
            else importScanned(text)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }

    /** WireGuard .conf / .zip / text files: picked (multi-select) or shared into the app. */
    private fun importFiles(uris: List<android.net.Uri>) = lifecycleScope.launch {
        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ConfigFiles.import(this@MainActivity, uris) }
        Toast.makeText(this@MainActivity, ConfigFiles.message(this@MainActivity, outcome), Toast.LENGTH_LONG).show()
    }

    /** A QR may hold config links, JSON, a WireGuard config or a subscription URL. */
    private fun importScanned(text: String) { smartImport(this, text) }

    /** Ask for VPN permission once, then run [action]. */
    private fun withVpnPermission(action: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) action() else { afterPermission = action; vpnPermission.launch(intent) }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Not again after rotation / theme change, or the shared file would be imported twice
        if (savedInstanceState == null) handleShare(intent)
        refreshDueSubscriptions()
        lifecycleScope.launch { com.netino.vpn.service.Updater.check(this@MainActivity) }

        setContent {
            val settings by Repository.settings.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme)
            LaunchedEffect(dark) {
                // Full-screen: transparent bars, icon colour follows the active theme
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            LaunchedEffect(settings.hideInRecents) {
                if (settings.hideInRecents) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            AppTheme(settings.theme) { AppRoot() }
        }
    }

    @Composable
    private fun AppRoot() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var splitOpen by rememberSaveable { mutableStateOf(false) }
        var addOpen by rememberSaveable { mutableStateOf(false) }

        val connect: (Server?) -> Unit = { s -> withVpnPermission { if (s == null) VpnController.toggle() else VpnController.connect(s) } }
        val fastest = {
            withVpnPermission {
                VpnController.connectFastest(onNone = {
                    runOnUiThread { Toast.makeText(this, R.string.fastest_none, Toast.LENGTH_SHORT).show() }
                })
            }
        }

        AnimatedContent(splitOpen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { split ->
            if (split) {
                SplitTunnelScreen(onBack = { splitOpen = false })
                return@AnimatedContent
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavItem(0, tab, Icons.Filled.Home, Icons.Outlined.Home, R.string.tab_home) { tab = it }
                        NavItem(1, tab, Icons.Filled.Dns, Icons.Outlined.Dns, R.string.tab_servers) { tab = it }
                        NavItem(2, tab, Icons.Filled.BarChart, Icons.Outlined.BarChart, R.string.tab_usage) { tab = it }
                        NavItem(3, tab, Icons.Filled.Settings, Icons.Outlined.Settings, R.string.tab_settings) { tab = it }
                    }
                },
            ) { pad ->
                val m = Modifier.fillMaxSize().padding(pad)
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        0 -> HomeScreen(m, onToggle = { connect(null) }, onPick = { connect(it) }, onFastest = fastest,
                            onAddServer = { addOpen = true })
                        1 -> ServersScreen(m, onPick = { s -> if (VpnController.isActive) connect(s) else Repository.select(s.id) },
                            onAdd = { addOpen = true })
                        2 -> StatsScreen(m)
                        else -> SettingsScreen(m, openSplit = { splitOpen = true })
                    }
                }
            }
        }
        if (addOpen) AddServerSheet(onDismiss = { addOpen = false }, onScanQr = { scanQr() }, onQrImage = { pickQrImage() },
            onFiles = { filePicker.launch(arrayOf("*/*")) })
    }

    @Composable
    private fun RowScope.NavItem(index: Int, selected: Int, on: ImageVector, off: ImageVector, label: Int, onSelect: (Int) -> Unit) =
        NavigationBarItem(
            selected = index == selected,
            onClick = { onSelect(index) },
            icon = { Icon(if (index == selected) on else off, contentDescription = null) },
            label = { Text(stringResource(label)) },
        )

    private fun scanQr() = qrScanner.launch(
        ScanOptions().setPrompt(getString(R.string.scan_prompt)).setBeepEnabled(false)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
            .setOrientationLocked(true),
    )

    private fun pickQrImage() = qrImagePicker.launch(
        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
    )

    /** Subscriptions whose auto-update period has elapsed are refreshed quietly on start. */
    private fun refreshDueSubscriptions() = lifecycleScope.launch { Repository.refreshDueSubscriptions() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Links and files shared into the app (e.g. from Telegram or a file manager) are imported directly. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                if (stream != null) importFiles(listOf(stream))
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { smartImport(this, it) }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    ?.takeIf { it.isNotEmpty() }?.let { importFiles(it) }
            Intent.ACTION_VIEW -> intent.data?.let { importFiles(listOf(it)) }
        }
    }
}
