package com.slipe.jarvis

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import java.util.Locale

/** Voz do Jarvis no celular: escolhe a melhor voz pt-BR instalada e fala mais rápido que o padrão. */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: String? = null

    fun init(c: Context) {
        if (tts != null) return
        val app = c.applicationContext
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                ready = true
                applyVoice(app)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { JarvisService.instance?.onSpeakDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { JarvisService.instance?.onSpeakDone() }
                })
                val p = pending
                pending = null
                if (p != null) say(app, p)
            } else {
                ready = false
                tts = null
            }
        }
    }

    fun applyVoice(c: Context) {
        val t = tts ?: return
        val p = Core.prefs(c)
        try {
            t.setLanguage(Locale("pt", "BR"))
            val want = p.getString("tts_voice", "") ?: ""
            val all = try { t.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
            val pt = all.filter { it.locale.language == "pt" && !it.features.contains("notInstalled") }
            val chosen = pt.firstOrNull { it.name == want }
                ?: pt.filter { it.locale.country == "BR" }.maxByOrNull { it.quality }
                ?: pt.maxByOrNull { it.quality }
            if (chosen != null) t.setVoice(chosen)
            t.setSpeechRate(p.getFloat("tts_rate", 1.3f))
            t.setPitch(p.getFloat("tts_pitch", 1.0f))
        } catch (e: Exception) {
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null

    private fun stopMp() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null
    }

    /** Voz da ElevenLabs (opcional). Se falhar por qualquer motivo, usa a voz do Android. */
    private fun sayEleven(c: Context, text: String, key: String) {
        val app = c.applicationContext
        Thread {
            try {
                val p = Core.prefs(app)
                val voice = (p.getString("el_voice", "") ?: "").ifBlank { "21m00Tcm4TlvDq8ikWAM" }
                val speed = p.getFloat("tts_rate", 1.0f).coerceIn(0.7f, 1.2f).toDouble()
                val body = JSONObject().put("text", text).put("model_id", "eleven_flash_v2_5")
                    .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75).put("speed", speed))
                val cn = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_64").openConnection() as HttpURLConnection
                try {
                    cn.requestMethod = "POST"
                    cn.doOutput = true
                    cn.connectTimeout = 10000
                    cn.readTimeout = 30000
                    cn.setRequestProperty("xi-api-key", key)
                    cn.setRequestProperty("Content-Type", "application/json")
                    cn.setRequestProperty("Accept", "audio/mpeg")
                    cn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (cn.responseCode !in 200..299) throw Exception("http " + cn.responseCode)
                    val f = File(app.cacheDir, "jv_el.mp3")
                    cn.inputStream.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
                    main.post { playFile(app, f, text) }
                } finally {
                    cn.disconnect()
                }
            } catch (e: Exception) {
                Core.prefs(app).edit().putString("last_err", "ElevenLabs: " + (e.message ?: "falha")).apply()
                main.post { sayLocal(app, text) }
            }
        }.start()
    }

    private fun playFile(c: Context, f: File, text: String) {
        try {
            stopMp()
            val m = MediaPlayer()
            m.setDataSource(f.absolutePath)
            m.setOnCompletionListener {
                stopMp()
                JarvisService.instance?.onSpeakDone()
            }
            m.setOnErrorListener { _, _, _ ->
                stopMp()
                sayLocal(c, text)
                true
            }
            m.setOnPreparedListener { it.start() }
            mp = m
            m.prepareAsync()
        } catch (e: Exception) {
            stopMp()
            sayLocal(c, text)
        }
    }

    fun say(c: Context, text: String) {
        if (text.isBlank()) return
        val key = Core.prefs(c).getString("el_key", "") ?: ""
        if (key.length > 10 && text.length <= 400) {
            stop()
            sayEleven(c, text, key)
            return
        }
        sayLocal(c, text)
    }

    private fun sayLocal(c: Context, text: String) {
        if (text.isBlank()) return
        if (tts == null) { pending = text; init(c); return }
        if (!ready) { pending = text; return }
        applyVoice(c)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "jv" + System.currentTimeMillis())
    }

    fun stop() {
        stopMp()
        try { tts?.stop() } catch (e: Exception) { }
    }

    fun voices(c: Context): JSONArray {
        val a = JSONArray()
        val t = tts
        if (t == null) { init(c); return a }
        try {
            t.voices?.filter { it.locale.language == "pt" }?.sortedByDescending { it.quality }?.forEach { a.put(it.name) }
        } catch (e: Exception) {
        }
        return a
    }

    fun isReady(): Boolean = ready
}
