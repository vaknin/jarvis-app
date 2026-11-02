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
 * Jarvis's ring as a call Android knows about, two ways:
 * - The phone's own incoming call (Aviv, 2026-11-02: "make it look EXACTLY like a regular Android phone
 *   call ... the real phone API"): a managed calling account, so the dialer shows its screen and rings.
 *   Android rings it only once the account is on, and his Pixel (Android 16, Google's Phone app) has no
 *   switch for it (thread t81); adb can turn it on: `telecom set-phone-account-enabled
 *   'com.kivan.jarvis/com.kivan.jarvis.RingLine$Service' jarvis-ring 0`, as the shell user, no root.
 * - Until then, a self-managed account, as WhatsApp's: no switch. Telecom carries only the ring, so the
 *   AirPods' or a car's answer and decline reach [Ring]; what he sees is [Callback]'s incoming-call
 *   notification (CallStyle: a heads-up with Decline and Answer over home, full screen on the lock
 *   screen), and its channel rings, since Telecom doesn't ring for a self-managed call.
 *
 * Either way answering hands the call to [CallActivity], as the notification's Answer does, and the
 * ring's call ends, so a call on the AirPods stays as it is (speakers, the phone's mic).
 */
object RingLine {
    private const val TAG = "JarvisCall"
    /** The managed account: the dialer's own screen, once it's on. */
    private const val ID = "jarvis-ring"
    /** The self-managed one: the notification is the screen. */
    private const val SELF_ID = "jarvis-ring-self"
    const val TITLE = "title"
    const val TEXT = "text"

    fun handle(context: Context) = PhoneAccountHandle(ComponentName(context, Service::class.java), ID)
    fun selfHandle(context: Context) = PhoneAccountHandle(ComponentName(context, Service::class.java), SELF_ID)

    /** Registers both accounts; Android keeps them (and whether the managed one is on) across restarts. */
    fun register(context: Context) {
        runCatching {
            tm(context).registerPhoneAccount(
                PhoneAccount.builder(handle(context), "Jarvis")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setShortDescription("Jarvis calling you")
                    .setIcon(Icon.createWithResource(context, R.drawable.lucide_phone))
                    .setSupportedUriSchemes(listOf(SCHEME))
                    .setAddress(Uri.fromParts(SCHEME, "jarvis", null))
                    .build(),
            )
            tm(context).registerPhoneAccount(
                PhoneAccount.builder(selfHandle(context), "Jarvis")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .setShortDescription("Jarvis calling you")
                    .setIcon(Icon.createWithResource(context, R.drawable.lucide_phone))
                    .build(),
            )
        }.onFailure { Log.w(TAG, "calling account not registered", it) }
    }

    /**
     * Rings on the dialer's own screen; false when it can't: Telecom refuses a managed account that's off
     * (reading the switch takes a phone permission, so the refusal tells).
     */
    fun ringPhone(context: Context, title: String, text: String): Boolean =
        runCatching { tm(context).addNewIncomingCall(handle(context), extras(title, text)); true }
            .onFailure { Log.i(TAG, "the phone's call screen isn't on (${it.javaClass.simpleName}): the notification rings") }
            .getOrDefault(false)
            .also { prefs(context).edit().putString("last-ring", if (it) "call screen" else "notification").apply() }

    /** How the last ring showed, for [PhoneReport]: "call screen", "notification", or "none yet". */
    fun lastRing(context: Context) = prefs(context).getString("last-ring", null) ?: "none yet"

    /** Tells Telecom the notification's ring is a call; false when it won't take one now (another call, or it refused). */
    fun ringSelf(context: Context, title: String, text: String): Boolean {
        val tm = tm(context)
        val h = selfHandle(context)
        if (!runCatching { tm.isIncomingCallPermitted(h) }.getOrDefault(false)) {
            Log.i(TAG, "telecom won't take a ring now: the notification alone")
            return false
        }
        return runCatching { tm.addNewIncomingCall(h, extras(title, text)); true }
            .onFailure { Log.w(TAG, "telecom refused the ring: ${it.message}") }
            .getOrDefault(false)
    }

    private fun extras(title: String, text: String) = Bundle().apply {
        putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, Bundle().apply {
            putString(TITLE, title)
            putString(TEXT, text)
        })
    }

    /** The ring is over (answered in the app, declined there, or missed): Telecom's call goes. */
    fun end(cause: Int) {
        main.post {
            ringing?.let {
                ringing = null
                it.setDisconnected(DisconnectCause(cause))
                it.destroy()
            }
        }
    }

    /** Once per install, on an app open: if Android took it away, the permission for full-screen calls. */
    fun ask(activity: Activity) {
        val prefs = prefs(activity)
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent() || prefs.getBoolean("asked-full-screen", false)) return
        prefs.edit().putBoolean("asked-full-screen", true).apply()
        Log.i(TAG, "full-screen calls not allowed: asking")
        Toast.makeText(activity, "Allow Jarvis's calls to show full screen", Toast.LENGTH_LONG).show()
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", activity.packageName, null)))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private const val SCHEME = "jarvis"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ringing: Ring? = null
    private fun tm(context: Context) = context.getSystemService(TelecomManager::class.java)

    /** The one incoming call: answer opens the call screen, reject declines, as the notification's buttons. */
    private class Ring(private val context: Context, why: String, self: Boolean) : Connection() {
        init {
            // The dialer (or a headset, a car) shows the name big and why he calls where a number would be.
            setAddress(Uri.fromParts(SCHEME, why, null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("Jarvis", TelecomManager.PRESENTATION_ALLOWED)
            if (self) connectionProperties = PROPERTY_SELF_MANAGED
            setAudioModeIsVoip(true)
            setRinging()
        }

        // Self-managed: the notification is already up ([Callback.ring] posts it before this call), nothing to show.
        override fun onShowIncomingCallUi() {}

        override fun onAnswer() = onAnswer(0)

        override fun onAnswer(videoState: Int) {
            Log.i(TAG, "answered through telecom")
            val i = CallActivity.intent(context, start = true)
            Callback.pending()?.let { i.putExtra(Callback.CALL, it) }
            val at = SystemClock.elapsedRealtime()
            // Telecom's bind may let the call screen open from the background; if not, the call starts below.
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
            Log.i(TAG, "declined through telecom")
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
            val self = manager?.id == SELF_ID
            val c = Ring(applicationContext, title.takeUnless { it.isEmpty() || it == "Jarvis" } ?: x?.getString(TEXT).orEmpty().ifEmpty { "Calling" }, self)
            ringing?.let { it.setDisconnected(DisconnectCause(DisconnectCause.REJECTED)); it.destroy() }
            ringing = c
            Log.i(TAG, if (self) "ringing through telecom, the notification its screen" else "ringing on the phone's call screen")
            return c
        }

        override fun onCreateIncomingConnectionFailed(manager: PhoneAccountHandle?, request: ConnectionRequest?) {
            if (manager?.id == SELF_ID) {
                Log.w(TAG, "telecom refused the incoming call: the notification alone")
            } else {
                Log.w(TAG, "telecom refused the phone's call screen: the notification rings instead")
                Callback.telecomFailed(applicationContext)
            }
        }
    }
}
