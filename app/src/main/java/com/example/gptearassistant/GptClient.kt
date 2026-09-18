package com.example.gptearassistant

import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GptClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends ONE image to GPT and gets back a JSON array of
     * {question, answer} pairs (1 to 7 questions supported).
     */
    fun extractAndAnswer(
        apiKey: String,
        base64Image: String,
        userRules: String,
        onResult: (List<QaPair>?) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val systemPrompt = (
                    "You will receive a photo that may contain 1 to 7 questions " +
                    "(typed or handwritten). Extract EVERY question. Answer each one " +
                    "following the rules given by the user. " +
                    "Reply with ONLY a JSON array of objects, each with keys " +
                    ""question" and "answer". No markdown, no commentary."
                )

                val userText = "Answer rules: $userRules"

                val imagePart = JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))

                val userContent = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", userText))
                    .put(imagePart)

                val body = JSONObject()
                    .put("model", "gpt-4o-mini")
                    .put("max_tokens", 2000)
                    .put("messages", JSONArray()
                        .put(JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt))
                        .put(JSONObject()
                            .put("role", "user")
                            .put("content", userContent)))

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onResult(null) }
                        return@Thread
                    }
                    val raw = resp.body!!.string()
                    val content = JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val arr = JSONArray(content)
                    val list = mutableListOf<QaPair>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(QaPair(
                            question = o.optString("question", "Question ${i + 1}"),
                            answer = o.optString("answer", "")
                        ))
                    }
                    main.post { onResult(list) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                main.post { onResult(null) }
            }
        }.start()
    }
}
