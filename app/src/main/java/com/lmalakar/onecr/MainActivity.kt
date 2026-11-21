package com.lmalakar.onecr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lmalakar.onecr.core.designsystem.theme.OneCRTheme
import com.lmalakar.onecr.ui.OneCRApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OneCRApplication).container
        setContent {
            OneCRTheme {
                OneCRApp(appInfo = container.appInfo)
            }
        }
    }
}
