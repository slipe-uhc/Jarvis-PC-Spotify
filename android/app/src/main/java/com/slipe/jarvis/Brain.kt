package com.slipe.jarvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cérebro de IA do Jarvis em segundo plano (o app fechado). A interface tem o mesmo cérebro em JavaScript. */
object Brain {
    private val hist = ArrayList<Pair<String, String>>()

    private val PROMPT = """
Você é o Jarvis, assistente pessoal por voz de um adolescente brasileiro. Fale em português do Brasil, informal, direto e curto, sem emoji e sem markdown, porque sua resposta será falada em voz alta.
Objetivo dele: chegar a 70 kg com shape estético até 01/01/2028, ficar fluente em inglês, ter estilo e rotina organizada.
Responda SEMPRE e somente com um JSON neste formato:
{"say":"o que falar","actions":[],"target":null}
"say": 1 ou 2 frases para comandos; até 5 frases para dúvidas. Nunca vazio.
"target": "pc" se o usuário pediu para fazer no PC/computador; senão null. Se target for "pc", coloque as ações normalmente (elas serão executadas no PC).
"actions" aceitas (use só o que foi pedido, e só diga que fez algo se incluiu a ação):
{"type":"open_app","name":"Spotify","url":"https://open.spotify.com"} abre app instalado pelo nome; sempre inclua url do site equivalente quando existir.
{"type":"open_url","url":"https://..."}
{"type":"youtube","q":"busca"}
{"type":"spotify","q":"busca ou vazio"}
{"type":"search","q":"busca no Google"}
{"type":"mode","v":"normal|silencioso|estudo|treino"}
{"type":"tab","v":"hoje|treino|semana|guia|jarvis|ajustes"} abre o app nessa aba
{"type":"done","id":"id do item (vazio = item atual)"}
{"type":"snooze","id":"id (vazio = item atual)","min":30}
{"type":"water","n":1}
{"type":"ear","v":"pc|celular|ambos"}
{"type":"stop_listening"}
Você está rodando em segundo plano no celular: NÃO consegue editar a rotina nem usar o chat de itens aqui. Se ele pedir para mudar a rotina, diga para abrir o Jarvis e pedir no chat (e use a ação tab com "jarvis").
Perguntas sobre dieta, treino, inglês, rosto, cabelo e estilo: responda com base no GUIA abaixo e no contexto. Qualquer outro assunto: responda normalmente como assistente geral.
"""

    fun key(c: Context): String = Core.prefs(c).getString("ai_key", "") ?: ""
    fun model(c: Context): String = Core.prefs(c).getString("ai_model", Gemini.DEFAULT) ?: Gemini.DEFAULT

    /** Comandos exatos que não precisam de IA (mais rápido). */
    fun quick(n: String): Boolean =
        Regex("^modo (silencioso|estudo|treino|normal)$").containsMatchIn(n) || Regex("^(para|pare|parar) (de )?ouvir$").containsMatchIn(n)

    private fun system(c: Context): String {
        val sb = StringBuilder(PROMPT)
        sb.append("\nCONTEXTO ATUAL\n")
        sb.append("Agora: ").append(SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())).append("\n")
        sb.append("Modo: ").append(Core.mode(c)).append("\n")
        sb.append("Água hoje: ").append(Core.waterN(c)).append(" de ").append(Water.goal(c)).append(" copos de 250 ml\n")
        val arr = Core.items(c, Core.dayIdx())
        val date = Core.today()
        sb.append("Itens de hoje:\n")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val done = Core.isDone(c, date, o.optString("id"))
            sb.append(o.optString("t")).append(" ").append(o.optString("title")).append(" ")
                .append(if (done) "[feito]" else "[pendente]").append(" id=").append(o.optString("id")).append("\n")
        }
        val kb = Core.prefs(c).getString("kb", "") ?: ""
        if (kb.isNotBlank()) sb.append("\nGUIA\n").append(kb.take(16000))
        return sb.toString()
    }

    private fun parse(t0: String): JSONObject {
        var t = t0.trim()
        if (t.startsWith("```")) t = t.replace(Regex("^```[a-zA-Z]*"), "").replace(Regex("```$"), "").trim()
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) {
            try { return JSONObject(t.substring(a, b + 1)) } catch (e: Exception) { }
        }
        return JSONObject().put("say", t.take(400)).put("actions", JSONArray())
    }

    /** Bloqueante: chame fora da thread principal. Devolve null se falhar. */
    fun ask(c: Context, text: String): JSONObject? {
        val key = key(c)
        if (key.isBlank()) return null
        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(c)))))
        val contents = JSONArray()
        synchronized(hist) {
            for ((r, t) in hist.takeLast(6)) {
                contents.put(JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", t))))
            }
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        body.put("contents", contents)
        body.put(
            "generationConfig",
            JSONObject().put("responseMimeType", "application/json").put("temperature", 0.5).put("maxOutputTokens", 900)
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        )
        val res = JSONObject(Gemini.generate(key, model(c), body.toString()))
        if (!res.optBoolean("ok")) {
            Core.prefs(c).edit().putString("last_err", "IA: " + res.optString("err")).apply()
            return null
        }
        val o = parse(res.optString("text"))
        synchronized(hist) {
            hist.add(Pair("user", text))
            hist.add(Pair("model", o.optString("say")))
            while (hist.size > 12) hist.removeAt(0)
        }
        return o
    }

    /** Executa o que a IA pediu e devolve a fala. */
    fun run(c: Context, o: JSONObject): String {
        val say = o.optString("say")
        val acts = o.optJSONArray("actions")
        if (o.optString("target") == "pc" && acts != null && acts.length() > 0) {
            if (Core.pair(c).isBlank()) return "Faça a ligação entre PC e celular nos Ajustes primeiro."
            Core.publish(c, JSONObject().put("type", "actions").put("to", "pc").put("actions", acts))
            return if (say.isBlank()) "Mandei para o PC" else say
        }
        exec(c, acts)
        return say
    }

    fun exec(c: Context, acts: JSONArray?) {
        if (acts == null) return
        for (i in 0 until acts.length()) {
            val a = acts.optJSONObject(i) ?: continue
            try {
                when (a.optString("type")) {
                    "open_app" -> Apps.open(c, a.optString("name"), a.optString("url"))
                    "open_url" -> if (a.optString("url").startsWith("http")) Core.openUrl(c, a.optString("url"), "Link")
                    "youtube" -> Core.openYoutube(c, a.optString("q"))
                    "spotify" -> Core.openSpotify(c, a.optString("q"))
                    "search" -> Core.openUrl(c, "https://www.google.com/search?q=" + URLEncoder.encode(a.optString("q"), "UTF-8"), "Busca")
                    "mode" -> {
                        val v = a.optString("v")
                        if (v in listOf("normal", "silencioso", "estudo", "treino")) {
                            Core.setMode(c, v)
                            Core.publish(c, JSONObject().put("type", "mode").put("v", v))
                        }
                    }
                    "tab" -> Core.openApp(c, a.optString("v"))
                    "done" -> {
                        var id = a.optString("id")
                        var imp = false
                        if (id.isBlank()) {
                            val (cur, nxt) = Core.curNext(c)
                            val it = cur ?: nxt
                            if (it != null) { id = it.optString("id"); imp = it.optBoolean("imp") }
                        }
                        if (id.isNotBlank()) {
                            val xp = if (imp) 20 else 10
                            Core.markDone(c, id, xp)
                            Core.publish(c, JSONObject().put("type", "done").put("date", Core.today()).put("id", id).put("xp", xp))
                        }
                    }
                    "snooze" -> Core.moveItem(c, a.optString("id"), a.optInt("min", 30))
                    "water" -> Core.addWater(c, a.optInt("n", 1))
                    "ear" -> {
                        val v = a.optString("v")
                        Core.setEar(c, v)
                        Core.publish(c, JSONObject().put("type", "ear").put("v", v))
                    }
                    "stop_listening" -> {
                        Core.prefs(c).edit().putBoolean("voice", false).apply()
                        Core.queueEvent(c, JSONObject().put("type", "voice").put("v", false))
                        JarvisService.instance?.stopAll()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
}
