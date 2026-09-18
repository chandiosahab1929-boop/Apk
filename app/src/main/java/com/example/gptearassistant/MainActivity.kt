package com.example.gptearassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.gptearassistant.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        val DEFAULT_PROMPT = (
            "Answer every question according to the laws of Pakistan. " +
            "Keep each answer to 5-6 lines maximum. Be precise."
        )
        private const val REQ_PERMS = 10
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var busy = false
    private var lastBase64: String? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.apiKeyInput.setText(prefs.getString("api_key", ""))
        binding.promptInput.setText(prefs.getString("prompt", DEFAULT_PROMPT))

        binding.captureButton.setOnClickListener { captureAndAsk() }
        binding.resetButton.setOnClickListener { resetAll() }
        binding.replayButton.setOnClickListener { replaySavedSession() }

        // If a previous session was saved on this phone, offer to replay it
        if (AppState.loadSession(this)) {
            setStatus("Saved session found: ${AppState.qaList.size} question(s). " +
                    "Tap REPLAY SAVED ANSWERS to hear them again (no internet needed), " +
                    "or RESET to wipe everything.")
        } else {
            setStatus("Ready. Enter API key, point at a question paper, tap CAPTURE & ASK.")
        }

        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else setStatus("Camera permission is required.")
    }

    // ---------- camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }
            imageCapture = ImageCapture.Builder().build()
            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAsk() {
        if (busy) return
        val apiKey = binding.apiKeyInput.text.toString().trim()
        if (apiKey.isEmpty()) { toast("Enter your OpenAI API key first"); return }
        prefs.edit()
            .putString("api_key", apiKey)
            .putString("prompt", binding.promptInput.text.toString())
            .apply()

        val capture = imageCapture ?: return
        busy = true
        binding.captureButton.isEnabled = false
        setStatus("Taking picture...")

        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bytes = photoFile.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                runOnUiThread { setStatus("Sending to ChatGPT...") }
                GptClient.extractAndAnswer(
                    apiKey = apiKey,
                    base64Image = b64,
                    userRules = binding.promptInput.text.toString()
                ) { list -> onAnswersReady(list, b64) }
            }

            override fun onError(exc: ImageCaptureException) {
                busy = false
                runOnUiThread {
                    binding.captureButton.isEnabled = true
                    setStatus("Capture failed: ${exc.message}")
                }
            }
        })
    }

    private fun onAnswersReady(list: List<QaPair>?, base64Image: String) {
        busy = false
        binding.captureButton.isEnabled = true
        if (list.isNullOrEmpty()) {
            // Keep the photo so a 4-tap retry can still use it later
            AppState.saveLastImage(this, base64Image)
            lastBase64 = base64Image
            setStatus("Could not reach ChatGPT or no questions found. " +
                    "Photo is saved - use FOUR earbud taps to retry once you have signal.")
            toast("No answer - photo saved for retry")
            return
        }
        // Success: save answers AND the photo (for possible 4-tap retry)
        AppState.set(list)
        AppState.saveSession(this)
        AppState.saveLastImage(this, base64Image)
        lastBase64 = base64Image
        setStatus("Found ${list.size} question(s). Answers SAVED on this phone. " +
                "Pocket your phone now. Double-tap = next, triple-tap = repeat, " +
                "4 taps = retry, 5 taps = reset.")

        val svc = Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_START_SESSION)
        ContextCompat.startForegroundService(this, svc)
    }

    // ---------- replay / reset ----------

    private fun replaySavedSession() {
        if (AppState.isEmpty()) { toast("No saved answers yet"); return }
        setStatus("Replaying saved answers (${AppState.qaList.size} questions) - no internet needed.")
        val svc = Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_START_SESSION)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun resetAll() {
        AppState.clearAll(this)
        lastBase64 = null
        setStatus("Reset complete. All saved answers and the captured image are removed. " +
                "The app is ready for your next photo.")
        toast("Everything wiped - ready for fresh use")
    }

    // ---------- helpers ----------

    private fun setStatus(msg: String) { binding.statusText.text = msg }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
