package com.example.gptearassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds the extracted questions + answers for the current session,
 * and PERSISTS them (plus the last captured photo) to internal storage
 * so answers survive app restarts and work with zero internet.
 */
object AppState {
    private const val SESSION_FILE = "session.json"
    private const val IMAGE_FILE = "last_image.b64"

    var qaList: List<QaPair> = emptyList()
    var index: Int = 0

    fun set(list: List<QaPair>) {
        qaList = list
        index = 0
    }

    fun current(): QaPair? = qaList.getOrNull(index)

    fun hasNext(): Boolean = index < qaList.size - 1

    fun next(): QaPair? {
        if (hasNext()) index++
        return current()
    }

    fun isEmpty(): Boolean = qaList.isEmpty()

    /** Reset: wipe all saved answers AND the last captured image. */
    fun clearAll(ctx: Context) {
        qaList = emptyList()
        index = 0
        File(ctx.filesDir, SESSION_FILE).delete()
        File(ctx.filesDir, IMAGE_FILE).delete()
    }

    // ---------- persistence ----------

    fun saveSession(ctx: Context) {
        try {
            val arr = JSONArray()
            qaList.forEach { qa ->
                arr.put(JSONObject().put("q", qa.question).put("a", qa.answer))
            }
            val rootObj = JSONObject().put("index", index).put("items", arr)
            File(ctx.filesDir, SESSION_FILE).writeText(rootObj.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    /** Returns true if a saved session was loaded. */
    fun loadSession(ctx: Context): Boolean {
        return try {
            val f = File(ctx.filesDir, SESSION_FILE)
            if (!f.exists()) return false
            val rootObj = JSONObject(f.readText())
            index = rootObj.optInt("index", 0)
            val arr = rootObj.getJSONArray("items")
            val list = mutableListOf<QaPair>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(QaPair(o.optString("q"), o.optString("a")))
            }
            qaList = list
            qaList.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveLastImage(ctx: Context, base64: String) {
        try { File(ctx.filesDir, IMAGE_FILE).writeText(base64) }
        catch (e: Exception) { e.printStackTrace() }
    }

    fun loadLastImage(ctx: Context): String? {
        return try {
            val f = File(ctx.filesDir, IMAGE_FILE)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) { null }
    }
}
