package com.kivan.jarvis

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast

/**
 * Jarvis's ring as a real phone call (Aviv, 2026-11-02: "make it look EXACTLY like a regular Android phone
 * call ... the real phone API"): a managed calling account ("Jarvis", a call provider, not self-managed), so
 * the phone's own dialer shows its incoming-call screen over home and lock screen, rings and vibrates as for
 * any call, and the AirPods' or car's answer reaches [Ring.onAnswer]. Android lets an app's calling account
 * ring only once Aviv turns it on (Settings, Calling accounts), so the app opens that screen once ([ask]);
 * until then [Callback] rings with its own incoming-call notification.
 *
 * The account carries only the ring: answering hands the call to [CallActivity], as the notification's
 * Answer does, and the managed call ends, so a call on the AirPods stays as it is (speakers, the phone's mic).
 */
object RingLine {
    private const val TAG = "JarvisCall"
    private const val ID = "jarvis-ring"
    const val TITLE = "title"
    const val TEXT = "text"

    fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, Service::class.java), ID)

    /** Registers the account; Android keeps it (and whether it's on) across restarts. */
    fun register(context: Context) {
        runCatching {
            val account = PhoneAccount.builder(handle(context), "Jarvis")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setShortDescription("Jarvis calling you")
                .setIcon(Icon.createWithResource(context, R.drawable.lucide_phone))
                .setSupportedUriSchemes(listOf(SCHEME))
                .setAddress(Uri.fromParts(SCHEME, "jarvis", null))
                .build()
            tm(context).registerPhoneAccount(account)
        }.onFailure { Log.w(TAG, "calling account not registered", it) }
    }

    /**
     * Rings through Telecom; false when it can't (the account off, or Telecom refused). Reading whether the
     * account is on takes a phone permission, so the ring itself tells: Telecom refuses one that's off.
     */
    fun ring(context: Context, title: String, text: String): Boolean {
        val extras = Bundle().apply {
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply {
                putString(TITLE, title)
                putString(TEXT, text)
            })
        }
        val prefs = prefs(context)
        return runCatching { tm(context).addNewIncomingCall(handle(context), extras); true }
            .onSuccess { prefs.edit().putBoolean(ON, true).apply() }
            .onFailure {
                Log.w(TAG, "telecom refused the ring: ${it.message}")
                // Off after all (or turned off): the next app open asks once more.
                if (it is SecurityException && prefs.getBoolean(ON, true)) prefs.edit().putBoolean(ON, false).putBoolean(ASKED, false).apply()
            }.getOrDefault(false)
    }

    /** The ring is over (answered in the app, declined there, or missed): the dialer's screen goes. */
    fun end(cause: Int) {
        main.post {
            ringing?.let {
                ringing = null
                it.setDisconnected(DisconnectCause(cause))
                it.destroy()
            }
        }
    }

    /**
     * Once per install (and once more after a ring finds it off), on an app open: Android's calling-accounts screen with a line on what to turn on, and,
     * if Android took it away, the permission for full-screen calls (the notification's fallback ring).
     */
    fun ask(activity: Activity) {
        val prefs = prefs(activity)
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (!nm.canUseFullScreenIntent() && !prefs.getBoolean("asked-full-screen", false)) {
            prefs.edit().putBoolean("asked-full-screen", true).apply()
            Log.i(TAG, "full-screen calls not allowed: asking")
            Toast.makeText(activity, "Allow Jarvis's calls to show full screen", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", activity.packageName, null)))
            }
            return
        }
        if (prefs.getBoolean(ON, false) || prefs.getBoolean(ASKED, false)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        Log.i(TAG, "calling account off: asking")
        Toast.makeText(activity, "Turn on Jarvis under \"All calling accounts\", so he rings like a phone call", Toast.LENGTH_LONG).show()
        runCatching { activity.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
            .onFailure { Log.w(TAG, "no calling accounts screen", it) }
    }

    private const val SCHEME = "jarvis"
    /** A ring went through: the account is on. */
    private const val ON = "calling-account-on"
    private const val ASKED = "asked-calling-account"
    private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ringing: Ring? = null
    private fun tm(context: Context) = context.getSystemService(TelecomManager::class.java)

    /** The one incoming call: answer opens the call screen, reject declines, as the notification's buttons. */
    private class Ring(private val context: Context, why: String) : Connection() {
        init {
            // The dialer shows the name big and the address under it, where a number would be: why he calls.
            setAddress(Uri.fromParts(SCHEME, why, null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("Jarvis", TelecomManager.PRESENTATION_ALLOWED)
            setAudioModeIsVoip(true)
            setRinging()
        }

        override fun onAnswer() = onAnswer(0)

        override fun onAnswer(videoState: Int) {
            Log.i(TAG, "answered on the phone's call screen")
            val i = CallActivity.intent(context, start = true)
            Callback.pending()?.let { i.putExtra(Callback.CALL, it) }
            val at = SystemClock.elapsedRealtime()
            // While the dialer's screen is in front: Telecom's bind lets the call screen open over it.
            runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "call screen not opened", it) }
            end(DisconnectCause.ANSWERED_ELSEWHERE)
            // If Android kept the call screen from opening, the call starts anyway: its notification shows it.
            main.postDelayed({
                if (MediaKeys.screenAt < at && !Voice.ui.value.live && CallService.micGranted(context)) {
                    Log.w(TAG, "the call screen didn't open: the call starts without it")
                    Voice.start(context, Callback.answered(context, null))
                }
            }, 3000)
        }

        override fun onReject() {
            Log.i(TAG, "declined on the phone's call screen")
            ringing = null
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            Callback.decline(context)
        }

        override fun onDisconnect() {
            if (ringing !== this) return
            ringing = null
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            Callback.decline(context)
        }

        override fun onSilence() {
            Log.i(TAG, "ring silenced")
        }
    }

    class Service : ConnectionService() {
        override fun onCreateIncomingConnection(manager: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
            val x = request?.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: request?.extras
            val title = x?.getString(TITLE).orEmpty()
            val c = Ring(applicationContext, title.takeUnless { it.isEmpty() || it == "Jarvis" } ?: x?.getString(TEXT).orEmpty().ifEmpty { "Calling" })
            ringing?.let { it.setDisconnected(DisconnectCause(DisconnectCause.REJECTED)); it.destroy() }
            ringing = c
            Log.i(TAG, "ringing on the phone's call screen")
            return c
        }

        override fun onCreateIncomingConnectionFailed(manager: PhoneAccountHandle?, request: ConnectionRequest?) {
            Log.w(TAG, "telecom refused the incoming call: the notification rings instead")
            Callback.telecomFailed(applicationContext)
        }
    }
}
