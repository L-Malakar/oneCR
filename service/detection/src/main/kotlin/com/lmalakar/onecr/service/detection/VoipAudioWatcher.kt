package com.lmalakar.onecr.service.detection

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Feeds VoIP heuristic input (2): another app is actively PLAYING voice-communication
 * audio (the far-end leg of a WhatsApp/Meet/... call routed through the speaker path).
 *
 * Uses AudioManager#registerAudioPlaybackCallback — the configs of other apps are
 * visible without any permission; we only look at usage + active flags, never at
 * content or client identity.
 */
class VoipAudioWatcher(
    context: Context,
    private val onPlaybackActive: (Boolean) -> Unit,
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var registered = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            onPlaybackActive(hasActiveVoicePlayback(configs.orEmpty()))
        }
    }

    /** @return true when registration succeeded AND there is an initial signal. */
    fun start(): Boolean {
        val am = audioManager ?: return false
        return try {
            am.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
            registered = true
            onPlaybackActive(hasActiveVoicePlayback(am.activePlaybackConfigurations))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
        registered = false
    }

    private fun hasActiveVoicePlayback(configs: List<AudioPlaybackConfiguration>): Boolean {
        // The public SDK exposes no per-player isActive()/state — the config list
        // only ever contains *active* players (AudioManager#getActivePlaybackConfigurations),
        // so usage is the discriminator. False positives are absorbed by the
        // heuristic's "notification AND audio" rule + debounce.
        return configs.any { config ->
            runCatching {
                config.audioAttributes?.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
            }.getOrDefault(false)
        }
    }
}
