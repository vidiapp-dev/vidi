package com.example.ytclient.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ytclient.core.settings.PlaybackMode

/**
 * Settings tab. Follows the other top-level tabs (History/Playlists): a plain [Column] with a
 * headline row rather than a Scaffold/TopAppBar — AppRoot already owns the Scaffold and the bottom
 * navigation bar, so a second Scaffold here would only add a redundant inset layer.
 *
 * Only one setting so far: the streaming transport ([PlaybackMode]). It lives on a settings SCREEN
 * rather than in the in-player gear menu on purpose — the gear menu's items (quality, audio track,
 * captions) all act on the session currently playing, whereas this one decides how the NEXT video is
 * fetched in the first place, and is meaningful with no video open at all.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val mode by viewModel.playbackMode.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Text(
            "Streaming",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        // selectableGroup(): tells accessibility services these two rows are ONE single-choice group
        // (so TalkBack announces "1 of 2" rather than reading two unrelated radio buttons), and is
        // what the per-row `selectable` modifiers below are expected to be nested under.
        Column(Modifier.selectableGroup()) {
            PlaybackModeOption(
                selected = mode == PlaybackMode.DASH,
                title = "DASH (recommended)",
                description = "Direct, no attestation, fixed quality selection",
                onSelect = { viewModel.setPlaybackMode(PlaybackMode.DASH) },
            )
            PlaybackModeOption(
                selected = mode == PlaybackMode.SABR,
                title = "SABR (experimental)",
                description = "Adaptive, may require re-attestation — not reliable yet",
                onSelect = { viewModel.setPlaybackMode(PlaybackMode.SABR) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Applies to the next video you open. DASH is not a fallback: picking it means videos are " +
                "never streamed over SABR, so no session is attested and none can be rejected " +
                "mid-playback — at the cost of choosing a quality up front instead of adapting to " +
                "the connection segment by segment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One row of the transport radio group. The whole row is the touch target (not just the button) —
 * `selectable` on the Row with `onClick = null` on the [RadioButton] is the Material 3 idiom for
 * that: the row carries the click and the accessibility role, and the button becomes a pure
 * indicator instead of a second, competing tap target announcing itself separately.
 */
@Composable
private fun PlaybackModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
