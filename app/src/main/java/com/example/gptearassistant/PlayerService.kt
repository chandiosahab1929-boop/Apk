package com.example.gptearassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import java.util.Locale

/**
 * Background service that:
 *  - speaks question/answers through TTS (routed to Bluetooth earbuds)
 *  - listens to earbud media buttons:
 *      DOUBLE tap  -> next question
 *      TRIPLE tap  -> repeat current answer
 *      QUAD tap    -> RETRY (re-send last photo to ChatGPT)
 *      5 taps      -> RESET (delete all saved answers + image)
 *  Keeps working with the phone in your pocket.
 *  Answers are saved on disk -> replay works with no internet at all.
 */
class PlayerService : Service() {

    companion object {
        const val ACTION_START_SESSION = "com.example.gptearassistant.START_SESSION"
        const val ACTION_STOP = "com.example.gptearassistant.STOP"
        private const val CHANNEL_ID = "player"
        private const val NOTIF_ID = 1
        private const val TAP_WINDOW_MS = 700L
    }

    private lateinit var tts: TextToSpeech
    private lateinit var mediaSession: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())

    private var tapCount = 0
    private var lastSpoken: String = ""
    private var ttsReady = false
    private var retrying = false

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Session ready"))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.setSpeechRate(0.95f)
                ttsReady = true
            }
        }

        mediaSession = MediaSessionCompat(this, "GptEarAssistant").apply {
            setCallback(mediaCallback)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> speakCurrent(announce = true)
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession.release()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- media buttons (earbuds) ----------

    private val mediaCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() = handleTap()
        override fun onPause() = handleTap()

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> handleTap()
                }
            }
            return true
        }
    }

    private fun handleTap() {
        tapCount++
        handler.removeCallbacks(resolveTaps)
        handler.postDelayed(resolveTaps, TAP_WINDOW_MS)
    }

    private val resolveTaps = Runnable {
        val count = tapCount
        tapCount = 0
        when (count) {
            2 -> nextQuestion()
            3 -> repeatAnswer()
            4 -> retryFromNetwork()
            5 -> resetAll()
            else -> repeatAnswer()   // single tap also repeats (safe default)
        }
    }

    // ---------- speaking ----------

    private fun speakCurrent(announce: Boolean) {
        if (!ttsReady) { handler.postDelayed({ speakCurrent(announce) }, 300); return }
        val item = AppState.current()
        if (item == null) {
            speak("No questions found in that image.")
            return
        }
        lastSpoken = buildString {
            if (announce) append("I found ${AppState.qaList.size} question" +
                (if (AppState.qaList.size > 1) "s. " else ". "))
            append("Question ${AppState.index + 1}. ")
            append(item.question)
            append(". Answer. ")
            append(item.answer)
        }
        speak(lastSpoken)
        updateNotification("Playing Q${AppState.index + 1} of ${AppState.qaList.size}")
    }

    private fun nextQuestion() {
        if (AppState.isEmpty()) { speak("No session loaded."); return }
        if (!AppState.hasNext()) {
            speak("That was the last question.")
            updateNotification("Session finished")
            return
        }
        AppState.next()
        speakCurrent(announce = false)
    }

    private fun repeatAnswer() {
        // Works fully offline - answers are saved on the phone.
        if (lastSpoken.isEmpty()) { speakCurrent(announce = true); return }
        speak(lastSpoken)
    }

    // ---------- retry (needs internet; re-sends the SAVED photo) ----------

    private fun retryFromNetwork() {
        if (retrying) { speak("Already retrying, please wait."); return }
        val image = AppState.loadLastImage(this)
        if (image == null) { speak("No saved photo to retry with."); return }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val prompt = prefs.getString("prompt",
            "Answer every question according to the laws of Pakistan. Keep each answer to 5-6 lines maximum.")
        if (apiKey.isEmpty()) { speak("API key is missing. Open the app once to set it."); return }

        retrying = true
        speak("Retrying. Sending your photo again.")
        updateNotification("Retrying with ChatGPT...")

        GptClient.extractAndAnswer(apiKey, image, prompt ?: "") { list ->
            retrying = false
            if (list.isNullOrEmpty()) {
                speak("Still no connection or no answer. I will keep the saved answers. Try four taps again later.")
                updateNotification("Retry failed - saved answers still available")
            } else {
                AppState.set(list)
                AppState.saveSession(this)
                speak("Got new answers. Playing the first one.")
                speakCurrent(announce = true)
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
        }
    }

    // ---------- reset ----------

    private fun resetAll() {
        AppState.clearAll(this)
        lastSpoken = ""
        speak("Reset complete. All saved answers and the captured image have been removed. The app is ready for your next photo.")
        updateNotification("Ready - capture a new photo")
    }

    // ---------- notification ----------

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GPT Ear Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
