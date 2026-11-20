package com.chernika.assistant

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : Activity() {
    private val night = Color.rgb(15, 12, 34)
    private val ink = Color.rgb(246, 243, 255)
    private val lavender = Color.rgb(168, 136, 255)
    private val violet = Color.rgb(76, 54, 140)
    private lateinit var historyView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var mode = "AUTO"

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = night
        window.navigationBarColor = night

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(night)
        }
        setContentView(root)

        val title = TextView(this).apply {
            text = "✦ CHERNIKA AI"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        }
        root.addView(title)

        status = TextView(this).apply {
            text = "OFFLINE PREVIEW  ·  Online AI coming later"
            textSize = 12f
            setTextColor(lavender)
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(status)

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (item in listOf("AUTO", "OFFLINE", "ONLINE")) {
            val button = Button(this).apply {
                text = item
                textSize = 11f
                isAllCaps = false
                setTextColor(ink)
                background = rounded(violet, 12)
                setOnClickListener {
                    mode = item
                    status.text = when (mode) {
                        "ONLINE" -> "ONLINE SELECTED · Backend not connected"
                        "OFFLINE" -> "OFFLINE PREVIEW · No downloaded LLM yet"
                        else -> "AUTO · Offline preview until cloud is configured"
                    }
                }
            }
            val params = LinearLayout.LayoutParams(0, dp(43), 1f)
            params.setMargins(dp(3), 0, dp(3), 0)
            modes.addView(button, params)
        }
        root.addView(modes)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(12), 0, dp(6))
        }
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(historyView)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Message Chernika..."
            setHintTextColor(Color.rgb(153, 147, 180))
            setTextColor(ink)
            textSize = 15f
            minLines = 1
            maxLines = 4
            background = rounded(Color.rgb(35, 28, 67), 16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))

        val send = Button(this).apply {
            text = "Send"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(violet, 14)
            setOnClickListener { sendMessage() }
        }
        val sendParams = LinearLayout.LayoutParams(dp(75), dp(54))
        sendParams.leftMargin = dp(8)
        composer.addView(send, sendParams)
        root.addView(composer)

        val clear = TextView(this).apply {
            text = "Clear conversation"
            textSize = 12f
            setTextColor(lavender)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                getPreferences(MODE_PRIVATE).edit().remove("history").apply()
                historyView.removeAllViews()
                addMessage("assistant", welcome())
            }
        }
        root.addView(clear)

        restoreMessages()
    }

    private fun welcome(): String =
        "Hello! I'm Chernika 🍇. This is the first Android prototype. " +
        "My local chat and conversation history work now, but a real offline " +
        "language model and online AI backend are not connected yet."

    private fun restoreMessages() {
        val saved = getPreferences(MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val arr = try { JSONArray(saved) } catch (_: Exception) { JSONArray() }
        if (arr.length() == 0) {
            addMessage("assistant", welcome())
            return
        }
        for (index in 0 until arr.length()) {
            val item = arr.optJSONObject(index) ?: continue
            addMessage(item.optString("role"), item.optString("text"), false)
        }
    }

    private fun addMessage(role: String, message: String, persist: Boolean = true) {
        val fromUser = role == "user"
        val bubble = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(ink)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(
                if (fromUser) violet else Color.rgb(36, 30, 66), 16)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromUser) Gravity.END else Gravity.START
        }
        val margins = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        margins.setMargins(0, dp(5), 0, dp(5))
        row.addView(bubble, margins)
        historyView.addView(row)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        if (persist) {
            val prefs = getPreferences(MODE_PRIVATE)
            val stored = try {
                JSONArray(prefs.getString("history", "[]"))
            } catch (_: Exception) { JSONArray() }
            stored.put(JSONObject().put("role", role).put("text", message))
            prefs.edit().putString("history", stored.toString()).apply()
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addMessage("user", text)
        val reply = if (mode == "ONLINE") {
            "The online model isn't configured yet. Next we'll connect a " +
            "secure backend; no API secrets will be stored in this APK."
        } else localReply(text)
        addMessage("assistant", reply)
    }

    private fun localReply(input: String): String {
        val s = input.lowercase(Locale.ROOT)
        return when {
            "hello" in s || "hi" == s || "hey" in s ->
                "Hello again! I am Chernika, your violet-themed AI-in-progress. 🍇"
            "name" in s || "who are you" in s ->
                "I'm Chernika AI. We're building my intelligence step by step."
            "offline" in s ->
                "Offline preview is active. A genuine small language model " +
                "will be added after we test this application on your device."
            "memory" in s || "remember" in s ->
                "This prototype saves conversation messages in private app storage. " +
                "Long-term semantic memory will come in a later stage."
            else ->
                "I received your message and saved it locally. " +
                "This first build tests the chat interface, not full AI reasoning. " +
                "Next we'll connect the online and offline brains."
        }
    }
}
