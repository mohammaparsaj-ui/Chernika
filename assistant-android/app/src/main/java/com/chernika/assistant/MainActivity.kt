package com.chernika.assistant

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Chernika AI v0.2. Cloud: OpenAI via user-managed HTTPS backend.
// Offline: explicitly a limited rules-based fallback, NOT a local language model.
class MainActivity : Activity() {
    private val backgroundColor = Color.rgb(16, 12, 35)
    private val textColor = Color.rgb(245, 240, 255)
    private val accent = Color.rgb(162, 139, 253)
    private val violet = Color.rgb(74, 52, 139)
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var send: Button
    private var mode = "AUTO"
    private var busy = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(15).toFloat()
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(textColor)
    }
    private fun button(value: String): Button = Button(this).apply {
        text = value; isAllCaps = false; textSize = 12f
        setTextColor(textColor); background = shape(violet)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(backgroundColor)
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("✦ CHERNIKA AI", 24f).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val settings = button("Settings")
        header.addView(settings, LinearLayout.LayoutParams(dp(98), dp(45)))
        settings.setOnClickListener { openSettings() }
        root.addView(header)
        status = text("", 12f).apply {
            setTextColor(accent); setPadding(0, 0, 0, dp(11))
        }
        root.addView(status)
        val modes = LinearLayout(this)
        for (m in listOf("AUTO", "OFFLINE", "ONLINE")) {
            val b = button(m)
            b.setOnClickListener { mode = m; refreshStatus() }
            val p = LinearLayout.LayoutParams(0, dp(43), 1f)
            p.setMargins(dp(3), 0, dp(3), 0)
            modes.addView(b, p)
        }
        root.addView(modes)
        refreshStatus()
        scroll = ScrollView(this).apply { isFillViewport = true }
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(conversation)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Message Chernika..."
            setHintTextColor(Color.LTGRAY); setTextColor(textColor)
            maxLines = 4; minLines = 1; textSize = 15f
            background = shape(Color.rgb(34, 27, 67))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
        send = button("Send")
        val sendParams = LinearLayout.LayoutParams(dp(74), dp(54))
        sendParams.leftMargin = dp(7)
        row.addView(send, sendParams)
        send.setOnClickListener { sendMessage() }
        root.addView(row)
        val clear = text("Clear chat", 12f).apply {
            setTextColor(accent); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(3))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete conversation?")
                    .setPositiveButton("Delete") { _, _ ->
                        prefs.edit().remove("history").apply()
                        conversation.removeAllViews()
                        showMessage("assistant", welcome(), false)
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        root.addView(clear)
        val saved = history()
        if (saved.length() == 0) showMessage("assistant", welcome(), false)
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            showMessage(item.optString("role"), item.optString("content"), false)
        }
    }

    private fun welcome() = "Hi! I'm Chernika 🍇. This version can connect to " +
        "a real AI through your private server. Open Settings to connect it. " +
        "Until then, the offline mode is a limited preview, not a full AI model."

    private fun refreshStatus() {
        val ready = !prefs.getString("backend_url", "").isNullOrBlank() &&
            prefs.contains("encrypted_token")
        status.text = when {
            mode == "OFFLINE" -> "OFFLINE · rules-based preview, not an LLM"
            mode == "ONLINE" && ready -> "ONLINE · private AI server configured"
            mode == "ONLINE" -> "ONLINE · configure server in Settings"
            ready -> "AUTO · online AI first, offline preview fallback"
            else -> "AUTO · offline preview until cloud is connected"
        }
    }

    private fun history() = try {
        JSONArray(prefs.getString("history", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    private fun showMessage(role: String, content: String, persist: Boolean = true) {
        val outgoing = role == "user"
        val bubble = text(content, 15f).apply {
            background = shape(if (outgoing) violet else Color.rgb(36, 29, 66))
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        val row = LinearLayout(this).apply {
            gravity = if (outgoing) Gravity.END else Gravity.START
        }
        val params = LinearLayout.LayoutParams(-2, -2)
        params.setMargins(0, dp(5), 0, dp(5))
        row.addView(bubble, params)
        conversation.addView(row)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        if (persist) {
            val h = history()
            val compact = JSONArray()
            for (i in (h.length() - 38).coerceAtLeast(0) until h.length()) {
                compact.put(h.get(i))
            }
            compact.put(JSONObject().put("role", role).put("content", content))
            prefs.edit().putString("history", compact.toString()).apply()
        }
    }

    private fun sendMessage() {
        if (busy) return
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        if (message.length > 2500) {
            Toast.makeText(this, "Message limit: 2500 characters", Toast.LENGTH_LONG).show()
            return
        }
        val prior = history()
        input.setText("")
        showMessage("user", message)
        if (mode == "OFFLINE") {
            showMessage("assistant", localPreview(message)); return
        }
        val url = prefs.getString("backend_url", "") ?: ""
        val token = try { retrieveToken() } catch (_: Exception) { null }
        if (url.isBlank() || token.isNullOrBlank()) {
            showMessage("assistant", if (mode == "ONLINE")
                "Open Settings and enter your private HTTPS server URL and " +
                "Chernika access token. Never enter your OpenAI API key in the app."
                else localPreview(message))
            return
        }
        busy = true; send.isEnabled = false; send.text = "..."
        Thread {
            val reply = try {
                val h = JSONArray()
                for (i in (prior.length() - 12).coerceAtLeast(0) until prior.length()) {
                    val item = prior.optJSONObject(i) ?: continue
                    if (item.optString("role") in listOf("user", "assistant")) {
                        h.put(item)
                    }
                }
                callBackend(url, token, message, h)
            } catch (e: Exception) {
                val error = (e.message ?: "Network error").take(120)
                if (mode == "AUTO")
                    "Cloud unavailable (" + error + ").\n\n" + localPreview(message)
                else "Cloud AI error: " + error +
                    ". Check your server URL, access token, and internet."
            }
            runOnUiThread {
                busy = false; send.isEnabled = true; send.text = "Send"
                showMessage("assistant", reply)
            }
        }.start()
    }

    private fun callBackend(base: String, secret: String, message: String, h: JSONArray): String {
        val u = URL(base.trim().trimEnd('/') + "/chat")
        require(u.protocol == "https" && !u.host.isNullOrBlank() && u.userInfo == null) {
            "Only secure HTTPS server URLs are supported"
        }
        val connection = u.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 12000
        connection.readTimeout = 45000
        connection.instanceFollowRedirects = false
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer " + secret)
        try {
            val payload = JSONObject()
                .put("message", message).put("history", h).toString()
            connection.outputStream.use {
                it.write(payload.toByteArray(StandardCharsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299)
                connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader()?.use { it.readText().take(20000) } ?: ""
            val json = try { JSONObject(result) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                throw IllegalStateException(json.optString("error", "HTTP " + code))
            }
            return json.optString("reply").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty server response")
        } finally {
            connection.disconnect()
        }
    }

    private fun localPreview(input: String): String {
        val s = input.lowercase(Locale.ROOT)
        return when {
            s == "/help" -> "Commands: /remember NOTE, /memory, /forget, /help."
            s.startsWith("/remember ") -> {
                val note = input.substringAfter(' ').trim().take(500)
                prefs.edit().putString("local_note", note).apply()
                "Saved this note locally. Use /memory to read it."
            }
            s == "/memory" -> prefs.getString("local_note", null)?.let {
                "Your saved note: " + it
            } ?: "No saved note yet."
            s == "/forget" -> {
                prefs.edit().remove("local_note").apply(); "Note deleted."
            }
            s == "hi" || "hello" in s || "hey" in s ->
                "Hello! I'm Chernika 🍇. Local preview is ready."
            "name" in s || "who are you" in s ->
                "I'm Chernika AI, your evolving Android assistant."
            else -> "I saved your message. This offline preview cannot answer " +
                "open questions yet. Connect your private online AI in Settings " +
                "for real language-model replies. Type /help for local commands."
        }
    }

    private fun openSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(6))
        }
        val info = text("HTTPS Worker URL (without /chat):", 13f)
        info.setTextColor(Color.DKGRAY)
        panel.addView(info)
        val endpoint = EditText(this).apply {
            hint = "https://example.workers.dev"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("backend_url", ""))
        }
        panel.addView(endpoint)
        val description = text("Chernika access token (NOT OpenAI API key):", 13f)
        description.setTextColor(Color.DKGRAY)
        panel.addView(description)
        val tokenInput = EditText(this).apply {
            hint = if (prefs.contains("encrypted_token")) "Saved; blank keeps existing token"
                else "Your private Chernika access token"
            setSingleLine(true)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        panel.addView(tokenInput)
        AlertDialog.Builder(this).setTitle("Cloud settings").setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val value = endpoint.text.toString().trim().trimEnd('/')
                val valid = try {
                    val test = URL(value)
                    test.protocol == "https" && !test.host.isNullOrBlank() &&
                        test.userInfo == null && test.query == null && test.ref == null
                } catch (_: Exception) { false }
                if (value.isNotBlank() && !valid) {
                    Toast.makeText(this, "Enter a valid HTTPS URL.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                try {
                    if (tokenInput.text.isNotBlank()) {
                        saveToken(tokenInput.text.toString())
                    }
                    prefs.edit().putString("backend_url", value).apply()
                    refreshStatus()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Could not securely save settings", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Disconnect") { _, _ ->
                prefs.edit().remove("backend_url").remove("encrypted_token").apply()
                refreshStatus()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = store.getKey("chernika_cloud_token_v1", null) as? SecretKey
        if (old != null) return old
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder("chernika_cloud_token_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return generator.generateKey()
    }

    private fun saveToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = Base64.encodeToString(cipher.iv +
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("encrypted_token", encoded).apply()
    }

    private fun retrieveToken(): String? {
        val value = prefs.getString("encrypted_token", null) ?: return null
        val encoded = Base64.decode(value, Base64.NO_WRAP)
        if (encoded.size < 29) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(128, encoded.copyOfRange(0, 12)))
        return String(cipher.doFinal(
            encoded.copyOfRange(12, encoded.size)), StandardCharsets.UTF_8)
    }
}
