package com.kivan.tether

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kivan.tether.ui.ChatScreen

/** The chat with the laptop, one tap from home: the "Laptop" shortcut and its message notification open it. */
class LaptopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Move.forward(this)) return
        setContent { ChatScreen() }
    }

    override fun onResume() {
        super.onResume()
        Core.setVisible(true)
    }

    override fun onPause() {
        Core.setVisible(false)
        super.onPause()
    }
}
