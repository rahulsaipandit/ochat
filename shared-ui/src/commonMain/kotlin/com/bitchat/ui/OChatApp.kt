package com.bitchat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Root composable shared by the Android and iOS shells. Real screens replace the placeholder. */
@Composable
fun OChatApp() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text = APP_TITLE)
    }
}

internal const val APP_TITLE = "ochat"
