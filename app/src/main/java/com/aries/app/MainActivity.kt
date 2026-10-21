package com.aries.app

import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var keyBox: EditText
    private lateinit var out: TextView
    private var tts: TextToSpeech? = null
    private val MIC = 101
    private val MODEL = "gemini-flash-latest"

    private fun prefs() = getSharedPreferences("aries", Context.MODE_PRIVATE)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 60, 40, 40)

        keyBox = EditText(this)
        keyBox.hint = "Gemini API key yahan paste karo"
        keyBox.setSingleLine(true)
        keyBox.setText(prefs().getString("key", ""))

        val save = Button(this)
        save.text = "Key save karo"
        save.setOnClickListener {
            prefs().edit().putString("key", keyBox.text.toString().trim()).apply()
            toast("Save ho gayi")
        }

        val mic = Button(this)
        mic.text = "Bolo (mic)"
        mic.textSize = 22f
        mic.setOnClickListener { listen() }

        out = TextView(this)
        out.textSize = 18f
        out.text = "Mic dabao aur bolo, jaise: kal subah 6 baje alarm lagao"
        val sv = ScrollView(this)
        sv.addView(out)

        root.addView(keyBox)
        root.addView(save)
        root.addView(mic)
        root.addView(sv)
        setContentView(root)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN")
        }
    }

    private fun listen() {
        if (prefs().getString("key", "").isNullOrEmpty()) {
            toast("Pehle API key save karo"); return
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye...")
        try {
            startActivityForResult(i, MIC)
        } catch (e: Exception) {
            toast("Phone me Google voice typing nahi mila")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MIC && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            out.text = "Aapne kaha: $said\n\nSoch raha hoon..."
            Thread { think(said) }.start()
        }
    }

    private fun show(s: String) = runOnUiThread { out.text = s }

    private fun reply(s: String) {
        out.append("\n\nAries: $s")
        tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "aries")
    }

    private fun think(said: String) {
        try {
            val prompt = "Tum Aries ho, ek personal voice assistant. User ka command: \"" + said + "\"\n" +
                "Sirf ek JSON object do, koi aur text nahi. Options:\n" +
                "{\"action\":\"alarm\",\"hour\":0-23,\"minute\":0-59,\"label\":\"...\"}\n" +
                "{\"action\":\"youtube\",\"query\":\"...\"}\n" +
                "{\"action\":\"search\",\"query\":\"...\"}\n" +
                "{\"action\":\"answer\",\"text\":\"user ki bhasha me poora jawab\"}\n" +
                "Alarm me 12 AM ka matlab hour 0. Jo samajh na aaye uske liye answer use karo."
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", prefs().getString("key", "") ?: "")
            c.doOutput = true
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (code !in 200..299) { show("Error $code:\n$resp"); return }
            val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val cmd = JSONObject(text.trim())
            runOnUiThread { act(cmd) }
        } catch (e: Exception) {
            show("Galti: " + e.message)
        }
    }

    private fun act(cmd: JSONObject) {
        try {
            val q = cmd.optString("query")
            when (cmd.optString("action")) {
                "alarm" -> {
                    val h = cmd.optInt("hour")
                    val m = cmd.optInt("minute")
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    i.putExtra(AlarmClock.EXTRA_HOUR, h)
                    i.putExtra(AlarmClock.EXTRA_MINUTES, m)
                    i.putExtra(AlarmClock.EXTRA_MESSAGE, cmd.optString("label", "Aries"))
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    startActivity(i)
                    reply("Alarm set ho gaya: %02d:%02d".format(h, m))
                }
                "youtube" -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                    reply("YouTube par dhoondh raha hoon: $q")
                }
                "search" -> {
                    val i = Intent(Intent.ACTION_WEB_SEARCH)
                    i.putExtra(SearchManager.QUERY, q)
                    startActivity(i)
                    reply("Search kar raha hoon: $q")
                }
                else -> reply(cmd.optString("text", "Samajh nahi aaya"))
            }
        } catch (e: Exception) {
            reply("Kaam nahi ho paya: " + e.message)
        }
    }
}
