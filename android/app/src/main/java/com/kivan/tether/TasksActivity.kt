package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.kivan.tether.ui.TasksScreen

/** The list of Aviv's tracked tasks, opened from home; a notification's tap opens one task ([SLUG]). */
class TasksActivity : ComponentActivity() {
    private val open = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        open.value = intent.getStringExtra(SLUG)
        setContent { TasksScreen(open, onClose = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open.value = intent.getStringExtra(SLUG)
    }

    companion object {
        private const val SLUG = "slug"

        fun intent(context: Context, slug: String? = null) = Intent(context, TasksActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SLUG, slug)
    }
}
