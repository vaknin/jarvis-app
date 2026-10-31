package com.kivan.jarvis.tabs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.kivan.jarvis.AppOpen
import com.kivan.jarvis.CallService
import com.kivan.jarvis.Dictation
import com.kivan.jarvis.Updater
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The wall of tabs, the app's front door. The link to the server lives only while this is on screen.
 * It opens silent: the mic is on only while he dictates, and leaving stops that, keeping the words.
 */
class TabsActivity : ComponentActivity() {
    private lateinit var deck: Deck

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deck = (lastNonConfigurationInstance as? Deck) ?: trialDeck(intent) ?: RealDeck.get(this)
        setContent { TabsTheme { Stage(deck) } }
        (deck as? RealDeck)?.let { real ->
            real.asks.onEach(::ask).launchIn(lifecycleScope)
            if (savedInstanceState == null) AppOpen.asks(this)
        }
        opening(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening(intent)
    }

    private fun opening(intent: Intent) {
        intent.getStringExtra(TAB)?.let { (deck as? RealDeck)?.landed(it) }
        intent.removeExtra(TAB)
    }

    override fun onStart() {
        super.onStart()
        (deck as? RealDeck)?.up()
    }

    override fun onResume() {
        super.onResume()
        if (deck !is RealDeck) return
        AppOpen.resumed(this)
        Updater.onOpen(this, tapped = intent.getBooleanExtra(Updater.EXTRA_INSTALL, false))
        intent.removeExtra(Updater.EXTRA_INSTALL)
    }

    override fun onPause() {
        if (deck is RealDeck) AppOpen.paused()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        val real = deck as? RealDeck ?: return
        if (isChangingConfigurations) return
        Dictation.stop()
        real.down()
    }

    private fun ask(what: RealDeck.Ask) = when (what) {
        RealDeck.Ask.MIC -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
        RealDeck.Ask.PICK -> startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            PICK,
        )
    }

    // The plain APIs, as CallActivity: the activity-result one needs a newer Fragment than core-telecom brings.
    @Deprecated("plain permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC && CallService.micGranted(this)) deck.dictate()
    }

    @Deprecated("plain activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val real = deck as? RealDeck ?: return
        if (requestCode != PICK || resultCode != Activity.RESULT_OK || data == null) return
        val clip = data.clipData
        val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull<Uri>(data.data)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { real.attach(uris) }
    }

    @Deprecated("A Deck outlives a rotation")
    override fun onRetainCustomNonConfigurationInstance(): Any = deck

    companion object {
        private const val TAB = "tab"
        private const val MIC = 1
        private const val PICK = 2

        /** The wall, or tab [tab] on it. */
        fun opening(context: Context, tab: String? = null) =
            Intent(context, TabsActivity::class.java).putExtra(TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
