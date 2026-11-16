package com.slipe.jarvis

import android.content.Context
import android.content.Intent
import java.net.URLEncoder

/** Abre qualquer app instalado pelo nome; se não achar, abre o site. */
object Apps {
    val SITES = mapOf(
        "github" to "https://github.com",
        "youtube" to "https://www.youtube.com",
        "spotify" to "https://open.spotify.com",
        "instagram" to "https://www.instagram.com",
        "tiktok" to "https://www.tiktok.com",
        "whatsapp" to "https://web.whatsapp.com",
        "discord" to "https://discord.com/app",
        "gmail" to "https://mail.google.com",
        "drive" to "https://drive.google.com",
        "netflix" to "https://www.netflix.com",
        "twitter" to "https://x.com",
        "x" to "https://x.com",
        "chatgpt" to "https://chatgpt.com",
        "claude" to "https://claude.ai",
        "google" to "https://www.google.com",
        "maps" to "https://maps.google.com",
        "canva" to "https://www.canva.com",
        "capcut" to "https://www.capcut.com",
        "anki" to "https://ankiweb.net",
        "tradutor" to "https://translate.google.com",
        "twitch" to "https://www.twitch.tv",
        "reddit" to "https://www.reddit.com",
        "behance" to "https://www.behance.net"
    )

    private val ALIAS = mapOf(
        "zap" to "whatsapp", "zapzap" to "whatsapp", "insta" to "instagram", "yt" to "youtube",
        "face" to "facebook", "config" to "configuracoes", "ajustes" to "configuracoes",
        "camera" to "camera", "galeria" to "galeria", "fotos" to "fotos", "relogio" to "relogio"
    )

    private fun clean(s: String): String =
        Core.norm(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(o|a|os|as|app|aplicativo|site|o app|o site|pagina)\\s+"), "")

    fun open(c: Context, name: String, url: String): Boolean {
        val q0 = clean(name)
        if (q0.isBlank()) return false
        val q = ALIAS[q0] ?: q0
        val pm = c.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try { pm.queryIntentActivities(main, 0) } catch (e: Exception) { emptyList() }
        var bestPkg: String? = null
        var bestLabel = ""
        var bs = 0
        for (ri in list) {
            val label = clean(try { ri.loadLabel(pm).toString() } catch (e: Exception) { "" })
            if (label.isBlank()) continue
            var s = 0
            if (label == q) s = 100
            else if (label.startsWith(q)) s = 80
            else if (label.contains(q)) s = 60
            else if (q.contains(label) && label.length >= 3) s = 50
            if (s > bs) { bs = s; bestPkg = ri.activityInfo.packageName; bestLabel = label }
        }
        if (bestPkg != null && bs >= 50) {
            val i = pm.getLaunchIntentForPackage(bestPkg)
            if (i != null) { Core.launch(c, i, bestLabel); return true }
        }
        val u = if (url.isNotBlank()) url else (SITES[q] ?: SITES[q0])
        if (u != null) { Core.openUrl(c, u, name); return true }
        Core.openUrl(c, "https://www.google.com/search?q=" + URLEncoder.encode(name, "UTF-8"), name)
        return true
    }
}
