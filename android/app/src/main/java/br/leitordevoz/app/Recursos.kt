package br.leitordevoz.app

import android.content.Context
import br.leitordevoz.voz.EspeakNativo
import br.leitordevoz.voz.Fonemizador
import br.leitordevoz.voz.MotorKokoro
import org.json.JSONObject
import java.io.File

/** Vozes do Kokoro em português do Brasil. */
object Vozes {
    val LISTA = listOf("pf_dora" to "Dora", "pm_alex" to "Alex", "pm_santa" to "Santa")
    const val PADRAO = "pf_dora"
}

/**
 * Recursos locais: dados do espeak-ng (copiados dos assets para um caminho de arquivo,
 * que é o que a biblioteca nativa exige), estilos das vozes e o fonemizador.
 */
class Recursos(private val ctx: Context) {
    val fonemizador: Fonemizador by lazy {
        System.loadLibrary("espeak_jni")
        var pasta = copiarDadosEspeak()
        // O espeak-ng ENCERRA o app (exit) se os dados estiverem incompletos: conferir antes.
        if (!dadosCompletos(pasta)) { File(pasta, ".versao").delete(); pasta = copiarDadosEspeak() }
        check(dadosCompletos(pasta)) { "Os dados de pronúncia (espeak-ng) não foram copiados corretamente. Reinstale o app." }
        Fonemizador(EspeakNativo(pasta.absolutePath))
    }

    private fun dadosCompletos(pasta: File) =
        listOf("phontab", "phonindex", "phondata", "intonations", "pt_dict", "en_dict", "lang/roa/pt-BR")
            .all { File(pasta, it).let { f -> f.isFile && f.length() > 0 } }

    val estilos: Map<String, FloatArray> by lazy {
        Vozes.LISTA.associate { (id, _) -> id to MotorKokoro.lerEstilo(ctx.assets.open("vozes/$id.f32").use { it.readBytes() }) }
    }

    private fun copiarDadosEspeak(): File {
        val destino = File(ctx.filesDir, "espeak-ng-data")
        val marca = File(destino, ".versao")
        val versao = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
        }
        if (marca.exists() && marca.readText() == versao) return destino
        destino.deleteRecursively()
        copiarAsset("espeak-ng-data", destino)
        marca.writeText(versao)
        return destino
    }

    private fun copiarAsset(caminho: String, destino: File) {
        val filhos = ctx.assets.list(caminho).orEmpty()
        if (filhos.isEmpty()) {
            destino.parentFile?.mkdirs()
            ctx.assets.open(caminho).use { e -> destino.outputStream().use { e.copyTo(it) } }
        } else {
            destino.mkdirs()
            for (f in filhos) copiarAsset("$caminho/$f", File(destino, f))
        }
    }
}

/**
 * Dicionário de pronúncia (palavra → como falar), salvo em pronuncias.json no armazenamento
 * interno do app: sobrevive a atualizações do APK. Gravação atômica com cópia de segurança.
 */
class Pronuncias(ctx: Context) {
    private val arquivo = File(ctx.filesDir, "pronuncias.json")
    private val copia = File(ctx.filesDir, "pronuncias.bak.json")
    @Volatile var palavras: Map<String, String> = carregar()
        private set
    @Volatile var versao = 0
        private set

    private fun carregar(): Map<String, String> {
        for (f in listOf(arquivo, copia)) {
            try {
                if (f.exists()) {
                    val j = JSONObject(f.readText()).getJSONObject("palavras")
                    return j.keys().asSequence().associateWith { j.getString(it) }
                }
            } catch (e: Exception) { /* tenta a cópia */ }
        }
        return PADRAO
    }

    @Synchronized
    fun salvar(novas: Map<String, String>) {
        val limpas = novas.mapNotNull { (p, g) ->
            val pl = p.trim().lowercase(); val gl = g.trim()
            if (pl.isNotEmpty() && gl.isNotEmpty() && pl.length <= 60 && gl.length <= 120) pl to gl else null
        }.toMap()
        val json = JSONObject().put("palavras", JSONObject(limpas)).toString(2)
        val tmp = File(arquivo.parentFile, "pronuncias.tmp")
        tmp.writeText(json)
        if (arquivo.exists()) arquivo.copyTo(copia, overwrite = true)
        if (!tmp.renameTo(arquivo)) { arquivo.writeText(json); tmp.delete() }
        palavras = limpas
        versao++
    }

    fun caminho(): String = arquivo.absolutePath

    companion object {
        val PADRAO = mapOf("molho" to "môlho", "molhos" to "môlhos", "homem" to "hõmem", "homens" to "hõmens")
    }
}
