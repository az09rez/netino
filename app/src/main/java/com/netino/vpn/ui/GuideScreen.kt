package com.netino.vpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netino.vpn.R

/** Questions people actually have, in the order they meet them; one answer open at a time. */
private val TOPICS = listOf(
    R.string.guide_q_start to R.string.guide_a_start,
    R.string.guide_q_buttons to R.string.guide_a_buttons,
    R.string.guide_q_add to R.string.guide_a_add,
    R.string.guide_q_fail to R.string.guide_a_fail,
    R.string.guide_q_warp to R.string.guide_a_warp,
    R.string.guide_q_sites to R.string.guide_a_sites,
    R.string.guide_q_share to R.string.guide_a_share,
    R.string.guide_q_slow to R.string.guide_a_slow,
    R.string.guide_q_always to R.string.guide_a_always,
    R.string.guide_q_privacy to R.string.guide_a_privacy,
    R.string.guide_q_report to R.string.guide_a_report,
)

@Composable
fun GuideScreen(onBack: () -> Unit, onIntro: () -> Unit) {
    BackHandler(onBack = onBack)
    var open by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.guide), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(TOPICS) { i, (q, a) ->
                    Surface(
                        onClick = { open = if (open == i) -1 else i },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(q), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Icon(if (open == i) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AnimatedVisibility(open == i) {
                                Text(stringResource(a), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onIntro, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.guide_intro_again))
                    }
                }
            }
        }
    }
}
