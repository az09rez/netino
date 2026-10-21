package com.netino.vpn.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.vpn.Locales
import com.netino.vpn.R
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 4

/**
 * First-run introduction: welcome + language, how it helps, notifications (asked before Android's own dialog
 * appears), and "ready". [needsNotifications]: Android 13+ and not granted yet; [onNotifications] asks for it.
 */
@Composable
fun IntroScreen(needsNotifications: Boolean, onNotifications: () -> Unit, onDone: () -> Unit, reopened: Boolean = false) {
    val pager = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val last = pager.currentPage == PAGE_COUNT - 1
    fun go(page: Int) { scope.launch { pager.animateScrollToPage(page) } }
    // Back: the previous page; on the first page, a reopened introduction closes (the first-run one leaves the app)
    BackHandler(enabled = pager.currentPage > 0 || reopened) {
        if (pager.currentPage > 0) go(pager.currentPage - 1) else onDone()
    }

    Surface(Modifier.fillMaxSize(), color = cs.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { go(PAGE_COUNT - 1) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.intro_skip), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), fontWeight = FontWeight.Medium)
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> HelpPage()
                        2 -> Centered(
                            icon = { IconTile(Icons.Outlined.Notifications, cs.primaryContainer, cs.onPrimaryContainer, RoundedCornerShape(40.dp)) },
                            title = R.string.intro_notif_title, body = R.string.intro_notif_body,
                        ) {
                            if (needsNotifications) FilledTonalButton(onClick = { onNotifications(); go(3) }, shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.heightIn(min = ActionHeight)) {
                                Text(stringResource(R.string.intro_allow_notif), style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                        else -> Centered(
                            icon = {
                                Box(Modifier.size(120.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(60.dp), tint = Color.White)
                                }
                            },
                            title = R.string.intro_ready_title, body = R.string.intro_ready_body,
                        ) {
                            // Android asks for the VPN permission on the first connect: said here so the dialog isn't a surprise
                            Text(stringResource(R.string.intro5_vpn_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            // Page dots, then the main button
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(PAGE_COUNT) { i ->
                        val on = i == pager.currentPage
                        val label = stringResource(R.string.intro_page_n, localDigits("${i + 1}"))
                        Box(Modifier.animateContentSize(LocalMotion.current.spec(Motion.Medium)).width(if (on) 28.dp else 10.dp).height(10.dp)
                            .clip(CircleShape).background(if (on) cs.primary else cs.outlineVariant)
                            .semantics { role = Role.Tab; selected = on; contentDescription = label })
                    }
                }
                Button(onClick = { if (last) onDone() else go(pager.currentPage + 1) }, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.heightIn(min = ActionHeight)) {
                    Text(stringResource(if (last) R.string.intro_start else R.string.intro_next),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, bg: Color, fg: Color, shape: androidx.compose.ui.graphics.Shape) {
    Box(Modifier.size(120.dp).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(60.dp), tint = fg)
    }
}

@Composable
private fun Centered(icon: @Composable () -> Unit, title: Int, body: Int, extra: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        icon()
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp), fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
    }
}

@Composable
private fun WelcomePage() {
    val ctx = LocalContext.current
    var lang by remember { mutableStateOf(Locales.current(ctx)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Image(painterResource(R.drawable.logo), stringResource(R.string.cd_logo), Modifier.size(140.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp), fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text(stringResource(R.string.intro_welcome_body), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The first thing to get right: the rest of the introduction is then read in that language
        val options = listOf("fa" to "فارسی", "en" to "English")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { i, (code, label) ->
                val selected = lang == code || (lang.isEmpty() && code == java.util.Locale.getDefault().language)
                SegmentedButton(selected = selected, onClick = {
                    lang = code
                    if (Locales.set(ctx, code)) (ctx as? Activity)?.recreate()
                }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label) }
            }
        }
    }
}

private class Tip(val icon: ImageVector, val title: Int, val body: Int, val light: Pair<Long, Long>, val dark: Pair<Long, Long>)

private val TIPS = listOf(
    Tip(Icons.Outlined.TouchApp, R.string.intro_tip1, R.string.intro_tip1_body, 0xFFD6E7FF to 0xFF0A3A73, 0xFF0D3F78 to 0xFFD6E7FF),
    Tip(Icons.AutoMirrored.Outlined.ListAlt, R.string.intro_tip2, R.string.intro_tip2_body, 0xFFDDF3C8 to 0xFF2C5A08, 0xFF22420A to 0xFFC8F09E),
    Tip(Icons.Outlined.Shield, R.string.intro_tip3, R.string.intro_tip3_body, 0xFFFEF3DC to 0xFF5C3700, 0xFF3A2A06 to 0xFFFBBF24),
)

@Composable
private fun HelpPage() {
    val dark = isDarkSurface()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.intro_help_title), style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp), fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))
        TIPS.forEach { t ->
            val (bg, fg) = (if (dark) t.dark else t.light)
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(bg)), contentAlignment = Alignment.Center) {
                        Icon(t.icon, null, Modifier.size(30.dp), tint = Color(fg))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(t.title), style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(t.body), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
