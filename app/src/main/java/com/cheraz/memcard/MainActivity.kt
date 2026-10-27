package com.cheraz.memcard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cheraz.memcard.ui.MemCardApp
import com.cheraz.memcard.ui.theme.MemcardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemcardTheme {
                MemCardApp()
            }
        }
    }
}
