package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service(), RecognitionListener {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var vosk: SpeechService? = null
    private var reconhecedor: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ocupado = false
    private val historico = JSONArray()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarNotificacao()
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                tts?.setPitch(0.85f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { voltarAEscutar() } }
                    override fun onError(id: String?) { main.post { voltarAEscutar() } }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (model == null) {
            StorageService.unpack(this, "model", "model",
                { m: Model ->
                    model = m
                    main.post { iniciarVosk() }
                },
                { e: java.io.IOException -> e.printStackTrace() })
        } else {
            iniciarVosk()
        }
        return START_STICKY
    }

    private fun criarNotificacao() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "jarvis") else Notification.Builder(this)
        b.setContentTitle("Jarvis escutando")
        b.setContentText("Diga: Jarvis")
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
        val notif = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
    }

    private fun iniciarVosk() {
        val m = model ?: return
        try {
            vosk?.shutdown()
            val rec = Recognizer(m, 16000.0f, "[\"jarvis\", \"[unk]\"]")
            vosk = SpeechService(rec, 16000.0f)
            vosk?.startListening(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun voltarAEscutar() {
        ocupado = false
        try { reconhecedor?.destroy() } catch (e: Exception) {}
        reconhecedor = null
        main.postDelayed({ iniciarVosk() }, 400)
    }

    private fun verificar(h: String) {
        if (!ocupado && h.lowercase().contains("jarvis")) {
            ocupado = true
            try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            } catch (e: Exception) {}
            main.postDelayed({
                try { vosk?.shutdown() } catch (e: Exception) {}
                vosk = null
                escutarPedido()
            }, 700)
        }
    }

    override fun onPartialResult(hypothesis: String?) { if (hypothesis != null) verificar(hypothesis) }
    override fun onResult(hypothesis: String?) { if (hypothesis != null) verificar(hypothesis) }
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {}
    override fun onTimeout() {}

    private fun escutarPedido() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            falar("O reconhecimento de voz está indisponível, senhor.")
            return
        }
        try { reconhecedor?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        reconhecedor = r
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { voltarAEscutar() }
            override fun onResults(results: Bundle?) {
                val lista = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val txt = lista?.firstOrNull()
                if (txt.isNullOrBlank()) voltarAEscutar() else perguntar(txt)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        r.startListening(i)
    }

    private fun perguntar(txt: String) {
        Thread {
            val resposta = try {
                chamarGroq(txt)
            } catch (e: Exception) {
                "Falha de conexão, senhor."
            }
            main.post { falar(resposta) }
        }.start()
    }

    private fun chamarGroq(txt: String): String {
        val chave = getSharedPreferences("jarvis", MODE_PRIVATE).getString("chave", "") ?: ""
        if (chave.isEmpty()) return "A chave do Groq não foi configurada, senhor."
        historico.put(JSONObject().put("role", "user").put("content", txt))
        val data = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        val sistema = JSONObject().put("role", "system").put("content",
            "Você é J.A.R.V.I.S., assistente pessoal educado, formal e de humor seco. Chame o usuário de senhor. Responda em português do Brasil, em no máximo duas frases curtas. Data e hora atuais: " + data + ".")
        val msgs = JSONArray()
        msgs.put(sistema)
        val ini = maxOf(0, historico.length() - 10)
        for (k in ini until historico.length()) msgs.put(historico.get(k))
        val corpo = JSONObject().put("model", "openai/gpt-oss-20b").put("messages", msgs)
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer " + chave)
        c.setRequestProperty("User-Agent", "jarvis-app/1.0")
        c.doOutput = true
        c.outputStream.use { it.write(corpo.toString().toByteArray()) }
        val cod = c.responseCode
        if (cod != 200) {
            historico.remove(historico.length() - 1)
            return if (cod == 429) "Atingimos o limite do plano gratuito, senhor." else "Erro " + cod + ", senhor."
        }
        val bruto = c.inputStream.bufferedReader().readText()
        val r = JSONObject(bruto).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        historico.put(JSONObject().put("role", "assistant").put("content", r))
        return r
    }

    private fun falar(t: String) {
        val limpo = t.replace(Regex("[*#_`]"), "")
        val res = tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "resp")
        if (res != TextToSpeech.SUCCESS) voltarAEscutar()
    }

    override fun onDestroy() {
        try { vosk?.shutdown() } catch (e: Exception) {}
        try { reconhecedor?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        model?.close()
        super.onDestroy()
    }
}
