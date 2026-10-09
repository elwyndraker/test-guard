package com.diu.privacyguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diu.privacyguard.ui.PrivacyGuardApp
import com.diu.privacyguard.ui.theme.PrivacyGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PrivacyGuardTheme {
                PrivacyGuardApp()
            }
        }
    }
}
