package com.netino.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.netino.vpn.R
import com.netino.vpn.data.Repository
import com.netino.vpn.data.Server

/**
 * QR code of a server, to scan with another phone. WireGuard: the wg-quick text (what the WireGuard app scans);
 * WARP in WARP: one code per hop, the outer one works alone as plain WARP. Others: the share link.
 */
@Composable
fun QrDialog(s: Server, onDismiss: () -> Unit) {
    val items = remember(s.id) {
        val inner = s.wgConf
        when {
            inner != null && s.wgOuter != null -> listOf<Pair<Int?, String>>(R.string.qr_outer to s.wgOuter, R.string.qr_inner to inner)
            inner != null -> listOf<Pair<Int?, String>>(null to inner)
            else -> listOf<Pair<Int?, String>>(null to Repository.serverLink(s))
        }.map { (label, text) -> label to QrImage.encode(text.trim())?.asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(s.name, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((label, img) in items) {
                    label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp)) }
                    if (img != null) Image(img, null, Modifier.fillMaxWidth().aspectRatio(1f))
                    else Text(stringResource(R.string.qr_too_long))
                }
            }
        },
    )
}
