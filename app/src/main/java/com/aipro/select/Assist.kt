package com.aipro.select

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        startAssistantActivity(Intent(context, CaptureActivity::class.java))
        finish()
    }
}

/** Stub recognizer - required so the system accepts AI Pro as an assistant app. We never listen to the mic. */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
