package br.leitordevoz.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda o último erro (inclusive os que fecham o app) num arquivo, para a página
 * mostrar na próxima abertura e o usuário poder copiar e enviar.
 */
object RegistroDeFalhas {
    @Volatile private var arquivo: File? = null

    fun instalar(ctx: Context) {
        arquivo = File(ctx.filesDir, "ultima-falha.txt")
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            registrar(e, "linha de execução \"${t.name}\" (o app fechou)")
            anterior?.uncaughtException(t, e)
        }
    }

    fun registrar(e: Throwable, onde: String) {
        val f = arquivo ?: return
        try {
            val pilha = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
            val data = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date())
            f.writeText("Leitor de Voz — falha em $onde\n$data · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n${pilha.take(6000)}")
        } catch (x: Throwable) { /* não há o que fazer */ }
    }

    fun lerEApagar(ctx: Context): String? {
        val f = arquivo ?: File(ctx.filesDir, "ultima-falha.txt")
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }
}
