package com.slipe.jarvis

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Ponte entre a interface (JavaScript) e o Android. Nome no JS: Android */
class Bridge(private val a: Activity) {
    private val c: Context = a.applicationContext

    @JavascriptInterface
    fun sync(json: String) {
        try {
            val o = JSONObject(json)
            val ed = Core.prefs(c).edit()
            ed.putString("sched", JSONObject().put("days", o.optJSONArray("days")).put("done", o.optJSONObject("done") ?: JSONObject()).toString())
            ed.putBoolean("sound", o.optBoolean("sound", true))
            ed.putBoolean("notif", o.optBoolean("notif", true))
            val w = o.optJSONObject("water")
            if (w != null) {
                ed.putInt("w_goal", w.optInt("goal", 16))
                ed.putInt("w_every", w.optInt("every", 90))
                ed.putBoolean("w_on", w.optBoolean("on", true))
            }
            ed.apply()
            Scheduler.scheduleNext(c)
            Water.scheduleNext(c)
        } catch (e: Exception) {
        }
    }

    @JavascriptInterface
    fun getState(): String {
        val p = Core.prefs(c)
        return JSONObject()
            .put("voice", Core.voice(c))
            .put("mode", p.getString("mode", "") ?: "")
            .put("ear", p.getString("ear", "") ?: "")
            .put("pair", Core.pair(c))
            .put("waterDate", p.getString("w_date", "") ?: "")
            .put("waterN", p.getInt("w_n", 0))
            .toString()
    }

    @JavascriptInterface
    fun pull(): String = Core.pullEvents(c)

    @JavascriptInterface
    fun setMode(m: String) {
        Core.prefs(c).edit().putString("mode", m).apply()
        Scheduler.scheduleNext(c)
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun setEar(e: String) {
        Core.prefs(c).edit().putString("ear", e).apply()
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun setPair(t: String) {
        Core.prefs(c).edit().putString("pair", t).apply()
        JarvisService.instance?.refresh()
    }

    @JavascriptInterface
    fun publish(json: String) {
        try { Core.publish(c, JSONObject(json)) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun startVoice() {
        Core.prefs(c).edit().putBoolean("voice", true).apply()
        val i = Intent(c, JarvisService::class.java)
        a.runOnUiThread {
            try { a.startForegroundService(i) } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun stopVoice() {
        Core.prefs(c).edit().putBoolean("voice", false).apply()
        JarvisService.instance?.stopAll()
        Watchdog.stop(c)
    }

    @JavascriptInterface
    fun openUrl(u: String) {
        a.runOnUiThread { Core.openUrl(c, u, "Link") }
    }

    @JavascriptInterface
    fun openApp(name: String, url: String) {
        a.runOnUiThread { Apps.open(c, name, url) }
    }

    @JavascriptInterface
    fun openSpotify(q: String) {
        a.runOnUiThread { Core.openSpotify(c, q) }
    }

    /* ---------- IA ---------- */
    @JavascriptInterface
    fun setAi(key: String, model: String) {
        Core.prefs(c).edit().putString("ai_key", key.trim()).putString("ai_model", model.trim().ifBlank { Gemini.DEFAULT }).apply()
    }

    @JavascriptInterface
    fun setEleven(key: String, voice: String) {
        Core.prefs(c).edit().putString("el_key", key.trim()).putString("el_voice", voice.trim()).apply()
    }

    @JavascriptInterface
    fun setKb(text: String) {
        Core.prefs(c).edit().putString("kb", text).apply()
    }

    /** Chama o Gemini fora da thread principal e devolve o resultado para window.aiDone(id, json). */
    @JavascriptInterface
    fun ai(id: String, key: String, model: String, body: String) {
        Thread {
            val res = try { Gemini.generate(key, model, body) } catch (e: Exception) { JSONObject().put("ok", false).put("err", "falha").toString() }
            val js = "window.aiDone&&window.aiDone(" + JSONObject.quote(id) + "," + JSONObject.quote(res) + ")"
            MainActivity.js(js)
        }.start()
    }

    /* ---------- voz e água ---------- */
    @JavascriptInterface
    fun speak(text: String) {
        Speaker.say(c, text)
    }

    @JavascriptInterface
    fun setVoice(rate: Float, pitch: Float, name: String) {
        Core.prefs(c).edit().putFloat("tts_rate", rate.coerceIn(0.5f, 2.5f)).putFloat("tts_pitch", pitch.coerceIn(0.5f, 2f)).putString("tts_voice", name).apply()
        Speaker.applyVoice(c)
    }

    @JavascriptInterface
    fun voices(): String = Speaker.voices(c).toString()

    @JavascriptInterface
    fun setWater(date: String, n: Int) {
        Core.setWater(c, date, n)
    }

    @JavascriptInterface
    fun notifyTest() {
        Notifier.notify(c, "Teste do Jarvis", "Se você viu isso, os avisos funcionam", true)
    }

    /* ---------- diagnóstico e permissões ---------- */
    @JavascriptInterface
    fun diag(): String {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = Core.prefs(c)
        val mic = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        return JSONObject()
            .put("mic", mic)
            .put("notif", notif)
            .put("overlay", Settings.canDrawOverlays(c))
            .put("exact", exact)
            .put("battery", pm.isIgnoringBatteryOptimizations(c.packageName))
            .put("service", JarvisService.instance != null)
            .put("recognizer", SpeechRecognizer.isRecognitionAvailable(c))
            .put("tts", Speaker.isReady())
            .put("ai", Brain.key(c).isNotBlank())
            .put("lastHeard", p.getString("last_heard", "") ?: "")
            .put("lastErr", p.getString("last_err", "") ?: "")
            .toString()
    }

    @JavascriptInterface
    fun askPerms() {
        a.runOnUiThread {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            a.requestPermissions(perms.toTypedArray(), 1)
        }
    }

    @JavascriptInterface
    fun askOverlay() {
        a.runOnUiThread {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName)))
        }
    }

    @JavascriptInterface
    fun askExact() {
        a.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 31)
                a.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.packageName)))
        }
    }

    @JavascriptInterface
    fun askBattery() {
        a.runOnUiThread {
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(c.packageName)) {
                a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
            }
        }
    }

    @JavascriptInterface
    fun openAppSettings() {
        a.runOnUiThread {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
        }
    }

    @JavascriptInterface
    fun openTtsSettings() {
        a.runOnUiThread {
            try {
                a.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                try { a.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) { }
            }
        }
    }
}
