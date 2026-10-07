package com.netino.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.netino.vpn.R
import com.netino.vpn.core.NetReport
import com.netino.vpn.data.NetKey
import com.netino.vpn.service.CrashReporter
import com.netino.vpn.service.VpnController
import com.netino.vpn.service.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-network report (firewall method, WARP account route, endpoint scans, WARP in WARP MTU, connection checks)
 * for comparing networks: shown, copied or shared as plain text. No keys or config links are in it.
 */
@Composable
fun NetReportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val state = VpnController.state.value
        val conn = when (state) {
            is VpnState.Connected -> {
                val ms = VpnController.tunnelDelay()
                "Connected: ${state.server.name}" + (if (state.pool > 1) " (auto, ${state.pool} servers)" else "") +
                    ", tunnel " + if (ms > 0) "$ms ms" else "doesn't answer"
            }
            is VpnState.Connecting -> "Connecting: ${state.server.name}"
            else -> "Not connected"
        }
        val header = listOf(
            CrashReporter.deviceLine(),
            "Report time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " • network ${NetKey.current}",
            conn,
        )
        text = withContext(Dispatchers.IO) { NetReport.text(header) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.net_report)) },
        text = {
            Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text ?: stringResource(R.string.net_report_wait), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text != null, onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Netino network report", text))
                Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
        },
        dismissButton = {
            TextButton(enabled = text != null, onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(R.string.share)) }
        },
    )
}
