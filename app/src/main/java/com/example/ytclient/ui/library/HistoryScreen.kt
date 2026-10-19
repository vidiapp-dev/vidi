package com.example.ytclient.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ytclient.App
import com.example.ytclient.core.local.toSearchVideo
import com.example.ytclient.ui.components.SearchField
import com.example.ytclient.ui.components.VideoList
import com.example.ytclient.youtube.models.SearchVideo

@Composable
fun HistoryScreen(onVideoClick: (SearchVideo) -> Unit) {
    val library = (LocalContext.current.applicationContext as App).library
    val history by library.history.collectAsState()
    var query by remember { mutableStateOf("") }

    val filtered = remember(history, query) {
        if (query.isBlank()) history
        else history.filter {
            it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = { library.clearHistory() }) { Text("Clear") }
            }
        }
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = { },
            onClear = { query = "" },
            placeholder = "Search history",
        )
        Spacer(Modifier.height(8.dp))

        when {
            history.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing watched yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> VideoList(
                videos = filtered.map { it.toSearchVideo() },
                onVideoClick = onVideoClick,
            )
        }
    }
}
