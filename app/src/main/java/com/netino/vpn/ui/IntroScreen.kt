package com.netino.vpn.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.netino.vpn.Locales
import com.netino.vpn.R
import kotlinx.coroutines.launch

/** One point on an intro page: an icon in a tinted circle, a title and a line of explanation. */
private data class Point(val icon: ImageVector, val tint: Color, val title: Int, val body: Int)

private val PAGES = listOf(
    R.string.intro2_title to listOf(
        Point(Icons.Outlined.Cloud, BrandBlue, R.string.intro2_warp, R.string.intro2_warp_body),
        Point(Icons.Outlined.Bolt, BrandGreenDeep, R.string.intro2_fastest, R.string.intro2_fastest_body),
        Point(Icons.AutoMirrored.Outlined.PlaylistAdd, Warn, R.string.intro2_own, R.string.intro2_own_body),
    ),
    R.string.intro3_title to listOf(
        Point(Icons.Outlined.Shield, BrandBlue, R.string.intro3_firewall, R.string.intro3_firewall_body),
        Point(Icons.Outlined.Autorenew, BrandGreenDeep, R.string.intro3_auto, R.string.intro3_auto_body),
        Point(Icons.Outlined.SignalCellularAlt, Warn, R.string.intro3_net, R.string.intro3_net_body),
    ),
    R.string.intro4_title to listOf(
        Point(Icons.Outlined.Lock, BrandBlue, R.string.intro4_nolog, R.string.intro4_nolog_body),
        Point(Icons.Outlined.Fingerprint, BrandGreenDeep, R.string.intro4_lock, R.string.intro4_lock_body),
    ),
    R.string.intro5_title to listOf(
        Point(Icons.Outlined.VpnKey, BrandBlue, R.string.intro5_vpn, R.string.intro5_vpn_body),
        Point(Icons.Outlined.Notifications, BrandGreenDeep, R.string.intro5_notif, R.string.intro5_notif_body),
    ),
)

/**
 * First-run introduction: welcome + language, the three ways to connect, what it does against filtering,
 * privacy, and the two permissions it will ask for (explained before Android's own dialogs appear).
 * [needsNotifications]: Android 13+ and not granted yet; [onNotifications] asks for it.
 */
@Composable
fun IntroScreen(needsNotifications: Boolean, onNotifications: () -> Unit, onDone: () -> Unit, reopened: Boolean = false) {
    val count = PAGES.size + 1
    val pager = rememberPagerState { count }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == count - 1
    // Back: the previous page; on the first page, a reopened introduction closes (the first-run one leaves the app)
    BackHandler(enabled = pager.currentPage > 0 || reopened) {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.intro_skip)) }
                else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (page == 0) WelcomePage()
                    else {
                        val (title, points) = PAGES[page - 1]
                        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(28.dp))
                        points.forEach { PointRow(it); Spacer(Modifier.height(20.dp)) }
                        if (page == count - 1 && needsNotifications) FilledTonalButton(onClick = onNotifications,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Outlined.Notifications, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.intro_allow_notif))
                        }
                    }
                }
            }
            // Page dots, then the main button
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(count) { i ->
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(stringResource(if (last) R.string.intro_start else R.string.intro_next), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun WelcomePage() {
    val ctx = LocalContext.current
    var lang by remember { mutableStateOf(Locales.current(ctx)) }
    Image(painterResource(R.drawable.logo), null, Modifier.size(150.dp))
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.intro1_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    // The first thing to get right: the rest of the introduction is then read in that language
    val options = listOf("fa" to "فارسی", "en" to "English")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (code, label) ->
            val selected = lang == code || (lang.isEmpty() && code == java.util.Locale.getDefault().language)
            SegmentedButton(selected = selected, onClick = {
                lang = code
                if (Locales.set(ctx, code)) (ctx as? Activity)?.recreate()
            }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
        }
    }
}

@Composable
private fun PointRow(p: Point) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(p.tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(p.icon, null, tint = p.tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(p.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(p.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
