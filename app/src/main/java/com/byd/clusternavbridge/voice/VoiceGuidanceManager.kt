package com.byd.clusternavbridge.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * VoiceGuidanceManager v1.5.0
 *
 * Gestisce la sintesi vocale TBT (Text-To-Speech) veicolare per BYD DiLink.
 * - Utilizza org.woheller69.ttsengine (Sherpa-ONNX Piper VITS neurale) o il default di sistema.
 * - Canale audio veicolare: USAGE_ASSISTANCE_NAVIGATION_GUIDANCE per audio ducking automatico.
 * - Filtro anti-spam con throttling a 3 stadi (Avviso iniziale, Avvicinamento a ~200m, Svolta a <40m).
 */
class VoiceGuidanceManager(
    private val context: Context,
    private val onMuteStateChanged: ((Boolean) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "BydVoiceGuidance"
        private const val PREFS_NAME = "byd_voice_guidance_prefs"
        private const val KEY_MUTED = "is_voice_muted"
        private const val PREFERRED_ENGINE = "org.woheller69.ttsengine"

        private const val UTTERANCE_NAV_GUIDANCE = "byd_nav_guidance_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var isReady = false

    var isMuted: Boolean = prefs.getBoolean(KEY_MUTED, false)
        private set

    // Tracking della manovra corrente per anti-spam
    private var lastManeuverKey: String = ""
    private var lastSpokenStage: Int = 0 // 0: nessuno, 1: preavviso (>400m), 2: avvicinamento (150-300m), 3: imminente (<50m)
    private var lastSpokenTimestamp: Long = 0L

    init {
        initTtsEngine()
    }

    private fun initTtsEngine() {
        Log.w(TAG, "Initializing TTS Engine (attempting preferred: $PREFERRED_ENGINE)")
        try {
            tts = TextToSpeech(context.applicationContext, this, PREFERRED_ENGINE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed initializing with $PREFERRED_ENGINE, falling back to default engine", e)
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e2: Exception) {
                Log.e(TAG, "Critical failure initializing fallback TTS", e2)
            }
        }
    }

    override fun onInit(status: Int) {
        Log.w(TAG, "TTS onInit callback received with status: $status")
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            Log.w(TAG, "TTS Engine initialized successfully with engine: ${engine.defaultEngine}")

            // Imposta lingua italiana
            val langResult = engine.setLanguage(Locale.ITALIAN)
            Log.w(TAG, "setLanguage Locale.ITALIAN result: $langResult")
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale.ITALIAN missing or not supported, trying Locale.ITALY")
                val langResult2 = engine.setLanguage(Locale.ITALY)
                Log.w(TAG, "setLanguage Locale.ITALY result: $langResult2")
            }

            // Canale audio veicolare di navigazione (fornisce ducking automatico a DiLink)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()
            engine.setAudioAttributes(audioAttributes)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.w(TAG, "TTS utterance started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.w(TAG, "TTS utterance finished: $utteranceId")
                }

                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS utterance error: $utteranceId")
                }
            })

            isReady = true
            Log.w(TAG, "VoiceGuidanceManager is READY for Italian TBT speech")
        } else {
            Log.e(TAG, "TTS initialization failed with code: $status")
            isReady = false
        }
    }

    /**
     * Elabora l'istruzione di navigazione TBT in arrivo
     */
    fun onNavInstruction(instruction: String, distanceStr: String) {
        if (isMuted || !isReady || instruction.isEmpty()) return

        val normalizedManeuver = normalizeInstruction(instruction)
        val distanceMeters = parseDistanceToMeters(distanceStr)
        val currentStage = determineStage(distanceMeters)

        // Se la manovra è cambiata (es. da svolta a destra a rotonda), resettiamo lo stage
        if (normalizedManeuver != lastManeuverKey) {
            lastManeuverKey = normalizedManeuver
            lastSpokenStage = 0
        }

        // Condizione di pronuncia: non parlare se abbiamo già pronunciato questo stage o uno più avanzato
        val now = System.currentTimeMillis()
        val shouldSpeak = when {
            // Nuova manovra appena comparsa
            lastSpokenStage == 0 && (now - lastSpokenTimestamp > 3000L) -> true
            // Transizione a stage più vicino (es. da stage 1 a stage 2, o da stage 2 a stage 3)
            currentStage > lastSpokenStage && (now - lastSpokenTimestamp > 4000L) -> true
            else -> false
        }

        if (shouldSpeak) {
            val speechText = buildSpeechPhrase(instruction, distanceMeters, currentStage)
            speak(speechText)
            lastSpokenStage = currentStage
            lastSpokenTimestamp = now
        }
    }

    /**
     * Pronuncia forzata immediata (es. per test o avviso specifico)
     */
    fun speak(text: String, force: Boolean = false) {
        Log.w(TAG, "speak() called with text='$text', force=$force, isReady=$isReady, isMuted=$isMuted")
        if (!isReady) {
            Log.w(TAG, "speak skipped: TTS is not ready yet (requested: '$text')")
            return
        }
        if (isMuted && !force) {
            Log.w(TAG, "speak skipped: voice guidance is muted")
            return
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        }
        val utteranceId = "$UTTERANCE_NAV_GUIDANCE${System.currentTimeMillis()}"

        Log.w(TAG, "Speaking navigation instruction: '$text' (utteranceId: $utteranceId)")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        Log.w(TAG, "tts.speak returned result code: $result")
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        lastManeuverKey = ""
        lastSpokenStage = 0
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        prefs.edit().putBoolean(KEY_MUTED, isMuted).apply()
        if (isMuted) {
            stop()
        } else {
            speak("Guida vocale attivata", force = true)
        }
        onMuteStateChanged?.invoke(isMuted)
        Log.i(TAG, "Voice guidance mute toggled: isMuted=$isMuted")
        return isMuted
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isReady = false
    }

    private fun determineStage(distanceMeters: Int): Int {
        return when {
            distanceMeters <= 50 -> 3 // Svolta imminente
            distanceMeters in 51..300 -> 2 // Avvicinamento
            distanceMeters > 300 -> 1 // Preavviso
            else -> 1
        }
    }

    private fun buildSpeechPhrase(instruction: String, distanceMeters: Int, stage: Int): String {
        val cleanInstr = instruction
            .replace(Regex("^(Tra\\s+\\d+\\s*(m|km|metri|chilometri)\\s*)", RegexOption.IGNORE_CASE), "")
            .trim()

        return when (stage) {
            3 -> {
                if (cleanInstr.startsWith("tra", ignoreCase = true)) {
                    cleanInstr
                } else {
                    "Ora, $cleanInstr"
                }
            }
            2 -> {
                val roundDist = (distanceMeters / 50) * 50
                if (roundDist > 0) "Tra $roundDist metri, $cleanInstr" else cleanInstr
            }
            1 -> {
                if (distanceMeters >= 1000) {
                    val km = String.format(Locale.ITALY, "%.1f", distanceMeters / 1000.0)
                    "Tra $km chilometri, $cleanInstr"
                } else {
                    val roundDist = (distanceMeters / 100) * 100
                    if (roundDist > 0) "Tra $roundDist metri, $cleanInstr" else cleanInstr
                }
            }
            else -> cleanInstr
        }
    }

    private fun normalizeInstruction(text: String): String {
        return text.lowercase(Locale.ITALIAN)
            .replace(Regex("\\b\\d+\\s*(m|km|metri)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseDistanceToMeters(distanceStr: String): Int {
        if (distanceStr.isEmpty()) return 9999
        try {
            val clean = distanceStr.lowercase(Locale.ITALIAN).trim()
            return when {
                clean.contains("km") -> {
                    val num = clean.replace("km", "").replace(",", ".").trim().toFloatOrNull() ?: 1.0f
                    (num * 1000).toInt()
                }
                clean.contains("m") -> {
                    clean.replace("m", "").replace(".", "").trim().toIntOrNull() ?: 500
                }
                else -> 500
            }
        } catch (_: Exception) {
            return 500
        }
    }
}
