package com.slipe.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Chamada à API da Groq (chave grátis, começa com gsk_). Recebe o mesmo corpo do Gemini e converte. */
object Compat {

    private fun post(url: String, key: String, body: String): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        try {
            cn.requestMethod = "POST"
            cn.doOutput = true
            cn.connectTimeout = 15000
            cn.readTimeout = 40000
            cn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            cn.setRequestProperty("Authorization", "Bearer $key")
            cn.setRequestProperty("User-Agent", "Jarvis/2.0")
            cn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = cn.responseCode
            val stream = if (code in 200..299) cn.inputStream else cn.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, txt)
        } finally {
            cn.disconnect()
        }
    }

    private fun errMsg(s: String): String =
        try { JSONObject(s).optJSONObject("error")?.optString("message") ?: s.take(120) } catch (e: Exception) { s.take(120) }

    private fun partsText(o: JSONObject?): String {
        val parts = o?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    fun generate(key: String, gbody: String, url: String, models: List<String>, json: Boolean): String {
        val g = JSONObject(gbody)
        val msgs = JSONArray()
        val sys = partsText(g.optJSONObject("systemInstruction"))
        if (sys.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", sys))
        val cs = g.optJSONArray("contents")
        if (cs != null) for (i in 0 until cs.length()) {
            val c = cs.optJSONObject(i) ?: continue
            msgs.put(JSONObject().put("role", if (c.optString("role") == "model") "assistant" else "user").put("content", partsText(c)))
        }
        val gc = g.optJSONObject("generationConfig")
        var err = "sem resposta"
        for (m in models) {
            try {
                val req = JSONObject().put("model", m).put("messages", msgs)
                    .put("temperature", gc?.optDouble("temperature", 0.5) ?: 0.5)
                    .put("max_tokens", gc?.optInt("maxOutputTokens", 900) ?: 900)
                if (json && gc?.optString("responseMimeType") == "application/json") req.put("response_format", JSONObject().put("type", "json_object"))
                val r = post(url, key, req.toString())
                if (r.first in 200..299) {
                    val t = JSONObject(r.second).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                    if (t.isNotBlank()) return JSONObject().put("ok", true).put("text", t).put("model", m).toString()
                    err = "resposta vazia"
                    continue
                }
                err = "HTTP " + r.first + ": " + errMsg(r.second)
                if (r.first == 401 || r.first == 403) break
            } catch (e: Exception) {
                err = "sem internet ou falha de rede"
                break
            }
        }
        return JSONObject().put("ok", false).put("err", err).toString()
    }
}
