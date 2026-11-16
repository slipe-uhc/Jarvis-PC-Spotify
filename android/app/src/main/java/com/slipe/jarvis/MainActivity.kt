package com.slipe.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    companion object {
        @Volatile var visible = false
        @Volatile private var current: MainActivity? = null

        fun js(code: String) {
            val a = current ?: return
            a.runOnUiThread { try { a.web.evaluateJavascript(code, null) } catch (e: Exception) { } }
        }
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        window.statusBarColor = Color.parseColor("#000000")
        window.navigationBarColor = Color.parseColor("#000000")
        Notifier.ensure(this)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#000000"))
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(this), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("file:")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, r.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onPageFinished(v: WebView, url: String) {
                handleTab(intent)
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
        Scheduler.scheduleNext(this)
        Water.scheduleNext(this)
        Speaker.init(this)
        // se a escuta estava ligada, religa agora (o app está visível, então o Android permite)
        if (Core.voice(this) && JarvisService.instance == null) {
            try { startForegroundService(Intent(this, JarvisService::class.java)) } catch (e: Exception) { }
        }
    }

    private fun handleTab(i: Intent?) {
        val t = i?.getStringExtra("tab") ?: return
        web.evaluateJavascript("window.goTab&&goTab('$t')", null)
        i?.removeExtra("tab")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTab(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        web.onResume()
        web.evaluateJavascript("window.pullNative&&pullNative()", null)
        if (Core.voice(this) && JarvisService.instance == null) {
            try { startForegroundService(Intent(this, JarvisService::class.java)) } catch (e: Exception) { }
        }
    }

    override fun onPause() {
        visible = false
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
