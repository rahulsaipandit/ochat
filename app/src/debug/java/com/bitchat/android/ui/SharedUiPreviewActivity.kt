package com.bitchat.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bitchat.ui.OChatApp

/** Debug-only host for the Compose Multiplatform UI in `:shared-ui`. */
class SharedUiPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OChatApp() }
    }
}
