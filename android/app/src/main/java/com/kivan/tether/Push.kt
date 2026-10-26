package com.kivan.tether

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kivan.tether.core.TetherNode

/**
 * FCM is used only to wake the phone: the laptop sends a content-free data message and the phone
 * dials it. Firebase is initialised by hand from the BuildConfig values, so the app builds and
 * works without a Firebase project, just without wakes.
 */
object Push {
    val configured get() = BuildConfig.FCM_APP_ID.isNotEmpty()

    fun init(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build(),
        )
    }

    /** Sends the registration token on every connect; it is tiny and the laptop may have lost it. */
    fun sendToken(node: TetherNode) {
        if (!configured) return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> runCatching { node.sendPushToken(token) } }
            .addOnFailureListener { Log.w("Tether", "no FCM token", it) }
    }
}

class WakeMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] == "wake") Core.wake("push")
    }

    // A live link gets the new token now; otherwise the next connect sends it (Push.sendToken).
    override fun onNewToken(token: String) {
        Core.sendLive { it.sendPushToken(token) }
    }
}
