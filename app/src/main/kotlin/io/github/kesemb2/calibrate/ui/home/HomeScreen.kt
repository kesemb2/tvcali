package io.github.kesemb2.calibrate.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kesemb2.calibrate.BuildConfig
import io.github.kesemb2.calibrate.R
import io.github.kesemb2.calibrate.ui.components.FocusCard
import io.github.kesemb2.calibrate.ui.components.Screen
import io.github.kesemb2.calibrate.ui.theme.GlassColors
import io.github.kesemb2.calibrate.ui.theme.LocalGlassType
import io.github.kesemb2.calibrate.ui.theme.Spacing

@Composable
fun HomeScreen(onOpenChain: () -> Unit, onOpenPatterns: () -> Unit) {
    val type = LocalGlassType.current
    val first = remember { FocusRequester() }
    Screen(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BasicText(stringResource(R.string.app_name), style = type.title1.copy(color = GlassColors.TextPrimary))
            BasicText(stringResource(R.string.home_subtitle), style = type.body.copy(color = GlassColors.TextSecondary))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XL), verticalAlignment = Alignment.Top) {
                HomeTile(
                    title = stringResource(R.string.home_chain),
                    description = stringResource(R.string.home_chain_desc),
                    badge = null,
                    modifier = Modifier.weight(1f).focusRequester(first),
                    onClick = onOpenChain,
                )
                HomeTile(
                    title = stringResource(R.string.home_patterns),
                    description = stringResource(R.string.home_patterns_desc),
                    badge = null,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenPatterns,
                )
                HomeTile(
                    title = stringResource(R.string.home_wizard),
                    description = stringResource(R.string.home_wizard_desc),
                    badge = stringResource(R.string.coming_in_stage, 3),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                stringResource(R.string.version, "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"),
                style = type.caption.copy(color = GlassColors.TextTertiary),
            )
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

@Composable
private fun HomeTile(
    title: String,
    description: String,
    badge: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val type = LocalGlassType.current
    FocusCard(modifier = modifier.height(190.dp), enabled = badge == null, onClick = onClick) { focused ->
        val ink = if (focused) GlassColors.OnFocus else GlassColors.TextPrimary
        val inkSoft = if (focused) GlassColors.OnFocus.copy(alpha = 0.7f) else GlassColors.TextSecondary
        BasicText(title, style = type.title2.copy(color = ink))
        BasicText(description, style = type.callout.copy(color = inkSoft))
        if (badge != null) {
            Spacer(Modifier.weight(1f))
            BasicText(badge, style = type.caption.copy(color = GlassColors.TextTertiary))
        }
    }
}
