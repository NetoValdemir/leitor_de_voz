package br.leitordevoz.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Modelo de voz Kokoro-82M (Apache-2.0), baixado no primeiro uso.
 * "completo" (fp32, 325 MB): melhor qualidade. "compacto" (int8, 92 MB): menor e
 * possivelmente mais rápido em celulares — o app mostra a velocidade medida.
 */
class Modelo(ctx: Context) {
    data class Opcao(val id: String, val nome: String, val arquivo: String, val tamanho: Long)

    private val pasta = File(ctx.filesDir, "modelos").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("leitor", Context.MODE_PRIVATE)

    @Volatile var baixando = false
        private set
    @Volatile private var cancelar = false

    var escolhido: String
        get() = prefs.getString("modelo", "completo") ?: "completo"
        set(v) { prefs.edit().putString("modelo", v).apply() }

    fun opcao(id: String = escolhido) = OPCOES.first { it.id == id }
    fun arquivo(id: String = escolhido) = File(pasta, opcao(id).arquivo)
    fun pronto(id: String = escolhido) = arquivo(id).let { it.exists() && it.length() == opcao(id).tamanho }
    fun instalados() = OPCOES.filter { pronto(it.id) }.map { it.id }

    /** Baixa (retomando um download interrompido); `progresso(baixado, total)`. */
    fun baixar(id: String, progresso: (Long, Long) -> Unit) {
        val op = opcao(id)
        val final = arquivo(id)
        val parcial = File(pasta, op.arquivo + ".parcial")
        baixando = true; cancelar = false
        try {
            var url = URL(BASE + op.arquivo)
            var tentativas = 0
            while (true) {
                val ja = if (parcial.exists()) parcial.length() else 0L
                if (ja >= op.tamanho) break
                val con = url.openConnection() as HttpURLConnection
                con.instanceFollowRedirects = false
                con.connectTimeout = 20000; con.readTimeout = 60000
                con.setRequestProperty("User-Agent", "LeitorDeVoz/1.0")
                if (ja > 0) con.setRequestProperty("Range", "bytes=$ja-")
                val codigo = con.responseCode
                if (codigo in 300..399) { url = URL(url, con.getHeaderField("Location")); con.disconnect(); continue }
                if (codigo !in listOf(200, 206)) { con.disconnect(); throw IllegalStateException("O servidor respondeu com erro $codigo.") }
                val inicio = if (codigo == 206) ja else 0L
                try {
                    RandomAccessFile(parcial, "rw").use { saida ->
                        saida.setLength(inicio); saida.seek(inicio)
                        con.inputStream.use { entrada ->
                            val buf = ByteArray(256 * 1024)
                            var total = inicio; var ultimo = 0L
                            while (true) {
                                if (cancelar) throw InterruptedException("cancelado")
                                val n = entrada.read(buf); if (n < 0) break
                                saida.write(buf, 0, n); total += n
                                val agora = System.currentTimeMillis()
                                if (agora - ultimo > 300) { progresso(total, op.tamanho); ultimo = agora }
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    if (++tentativas > 5) throw e   // conexão caiu: retoma de onde parou
                    Thread.sleep(2000L * tentativas)
                } finally { con.disconnect() }
            }
            if (parcial.length() != op.tamanho) throw IllegalStateException("Download incompleto. Tente de novo.")
            progresso(op.tamanho, op.tamanho)
            if (!parcial.renameTo(final)) throw IllegalStateException("Não foi possível salvar o modelo.")
            escolhido = id
            // libera espaço: remove o outro modelo, se houver
            OPCOES.filter { it.id != id }.forEach { File(pasta, it.arquivo).delete() }
        } finally {
            baixando = false
        }
    }

    fun cancelarDownload() { cancelar = true }

    companion object {
        const val BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
        val OPCOES = listOf(
            Opcao("completo", "Completo (melhor qualidade)", "kokoro-v1.0.onnx", 325_532_387L),
            Opcao("compacto", "Compacto (menor)", "kokoro-v1.0.int8.onnx", 92_361_271L),
        )
    }
}
