package com.slipe.jarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Chamada simples à API do Gemini (chave grátis do Google AI Studio). */
object Gemini {
    const val DEFAULT = "gemini-flash-latest"

    private fun models(pref: String): List<String> =
        listOf(if (pref.contains("gemini", true)) pref else "", "gemini-flash-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite").filter { it.isNotBlank() }.distinct()

    private fun post(key: String, model: String, body: String): Pair<Int, String> {
        val cn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            cn.requestMethod = "POST"
            cn.doOutput = true
            cn.connectTimeout = 15000
            cn.readTimeout = 40000
            cn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            cn.setRequestProperty("x-goog-api-key", key)
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

    private fun extract(s: String): String {
        return try {
            val parts = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun withoutThinking(body: String): String {
        return try {
            val o = JSONObject(body)
            o.optJSONObject("generationConfig")?.remove("thinkingConfig")
            o.toString()
        } catch (e: Exception) {
            body
        }
    }

    /** Devolve um JSON: {"ok":true,"text":"..."} ou {"ok":false,"err":"..."} */
    private fun generateGemini(key: String, pref: String, body0: String): String {
        var err = "sem resposta"
        for (m in models(pref)) {
            try {
                var body = body0
                var r = post(key, m, body)
                if (r.first == 400 && errMsg(r.second).contains("thinking", true)) {
                    body = withoutThinking(body)
                    r = post(key, m, body)
                }
                if (r.first in 200..299) {
                    val t = extract(r.second)
                    if (t.isNotBlank()) return JSONObject().put("ok", true).put("text", t).put("model", m).toString()
                    err = "resposta vazia"
                    continue
                }
                err = "HTTP " + r.first + ": " + errMsg(r.second)
                if (r.first == 429 || r.first == 401 || r.first == 403) break
                if (r.first == 400 && errMsg(r.second).contains("API key", true)) break
            } catch (e: Exception) {
                err = "sem internet ou falha de rede"
                break
            }
        }
        return JSONObject().put("ok", false).put("err", err).toString()
    }

    private val PROVS = listOf(
        Triple("Groq", "gsk_", "https://api.groq.com/openai/v1/chat/completions"),
        Triple("OpenRouter", "sk-or-", "https://openrouter.ai/api/v1/chat/completions"),
        Triple("Cerebras", "csk-", "https://api.cerebras.ai/v1/chat/completions"),
        Triple("OpenAI", "sk-", "https://api.openai.com/v1/chat/completions")
    )

    private fun modelsOf(name: String): List<String> = when (name) {
        "Groq" -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        "OpenRouter" -> listOf("openrouter/free", "openai/gpt-oss-120b:free")
        "Cerebras" -> listOf("gpt-oss-120b", "llama3.1-8b")
        else -> listOf("gpt-4o-mini")
    }

    /** Aceita várias chaves (uma por linha). Tenta na ordem e usa a primeira que funcionar. */
    fun generate(keys: String, pref: String, body0: String): String {
        val list = keys.split(Regex("[\\s,;]+")).filter { it.length > 10 }
        var err = "sem chave"
        for (k in list) {
            val prov = PROVS.firstOrNull { k.startsWith(it.second) }
            val name = prov?.first ?: "Gemini"
            val res = try {
                if (prov == null) generateGemini(k, pref, body0)
                else Compat.generate(k, body0, prov.third, modelsOf(name), name != "OpenRouter")
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("err", "falha").toString()
            }
            val o = JSONObject(res)
            if (o.optBoolean("ok")) return o.put("prov", name).toString()
            err = (if (list.size > 1 && err != "sem chave") "$err | " else "") + name + ": " + o.optString("err")
        }
        return JSONObject().put("ok", false).put("err", err).toString()
    }
}
