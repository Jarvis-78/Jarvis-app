package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(48, 96, 48, 48)

        val titulo = TextView(this)
        titulo.text = "J.A.R.V.I.S."
        titulo.textSize = 28f

        val campo = EditText(this)
        campo.hint = "Cole a chave do Groq"
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (!prefs.getString("chave", "").isNullOrEmpty()) {
            campo.hint = "Chave salva (cole outra para trocar)"
        }

        status = TextView(this)
        status.text = "Aguardando, senhor."
        status.textSize = 16f

        val salvar = Button(this)
        salvar.text = "Salvar chave"
        salvar.setOnClickListener {
            val c = campo.text.toString().trim()
            if (c.isNotEmpty()) {
                prefs.edit().putString("chave", c).apply()
                campo.setText("")
                status.text = "Chave salva, senhor."
            } else {
                status.text = "Cole a chave primeiro, senhor."
            }
        }

        val ativar = Button(this)
        ativar.text = "Ativar Jarvis"
        ativar.setOnClickListener { pedirPermissoes() }

        val parar = Button(this)
        parar.text = "Desativar"
        parar.setOnClickListener {
            stopService(Intent(this, JarvisService::class.java))
            status.text = "Jarvis desativado."
        }

        raiz.addView(titulo)
        raiz.addView(campo)
        raiz.addView(salvar)
        raiz.addView(ativar)
        raiz.addView(parar)
        raiz.addView(status)
        setContentView(raiz)
    }

    private fun pedirPermissoes() {
        val faltam = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltam.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (faltam.isEmpty()) iniciar() else requestPermissions(faltam.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciar()
        } else {
            status.text = "Sem permissão de microfone, senhor."
        }
    }

    private fun iniciar() {
        val i = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        status.text = "Jarvis ativado. Diga: Jarvis."
    }
}
