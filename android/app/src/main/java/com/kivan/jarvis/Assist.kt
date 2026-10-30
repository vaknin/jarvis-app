package com.kivan.jarvis

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Jarvis as Android's digital assistant (Settings > Apps > Default apps > Digital assistant app): holding the power
 * button, or any assist gesture, calls Jarvis, as the tile does. The system shows [Session]; it opens the call screen
 * with START and gets out of the way. Without the role, CallActivity's ACTION_ASSIST filter does the same.
 */
class Assistant : VoiceInteractionService()

class AssistantSessions : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    private class Session(service: AssistantSessions) : VoiceInteractionSession(service) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            context.startActivity(CallActivity.intent(context, start = true))
            hide()
        }
    }
}

/**
 * The assistant role takes a speech recognizer with it; Jarvis listens on calls, not through this, so it says so.
 * Apps that ask Android's default recognizer get "busy" while Jarvis is the assistant.
 */
class AssistantRecognizer : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback) = listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
