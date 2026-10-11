package com.bitchat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Root composable shared by the Android and iOS shells. Real screens replace the placeholder. */
@Composable
fun OChatApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = APP_TITLE, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

internal const val APP_TITLE = "ochat"
