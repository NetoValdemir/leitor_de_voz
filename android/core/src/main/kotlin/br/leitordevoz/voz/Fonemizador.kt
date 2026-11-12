package br.leitordevoz.voz

import br.leitordevoz.texto.Rx

/**
 * Acesso ao espeak-ng (biblioteca nativa). No Android, a biblioteca é carregada
 * pelo app; nos testes, pelo próprio teste.
 */
object EspeakJni {
    @JvmStatic external fun iniciar(pastaDados: ByteArray, voz: ByteArray): Int
    @JvmStatic external fun fonemas(texto: ByteArray): ByteArray?
}

/** Conversão "crua" do espeak (um trecho de texto sem pontuação → fonemas IPA). */
fun interface Espeak {
    fun textoParaFonemas(texto: String): String
}

/** Implementação que usa a biblioteca nativa (já iniciada). */
class EspeakNativo(pastaDados: String, voz: String = "roa/pt-BR") : Espeak {
    init {
        val r = EspeakJni.iniciar(pastaDados.toByteArray(Charsets.UTF_8), voz.toByteArray(Charsets.UTF_8))
        require(r > 0) { "Não foi possível iniciar o espeak-ng (código $r, dados em $pastaDados)" }
    }

    override fun textoParaFonemas(texto: String): String = synchronized(TRAVA) {
        // o espeak-ng tem estado global: uma conversão por vez
        EspeakJni.fonemas(texto.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8) ?: ""
    }

    private companion object { val TRAVA = Any() }
}

/**
 * Texto → fonemas no formato do treino do Kokoro.
 *
 * Tradução fiel de:
 *  - phonemizer 3.x: EspeakBackend(language="pt-br", preserve_punctuation=True,
 *    with_stress=True, tie="^", language_switch="remove-flags").phonemize([texto])
 *    (classes Punctuation.preserve/restore e EspeakBackend._postprocess_line);
 *  - misaki (Apache-2.0) espeak.EspeakG2P.__call__ (mapeamento E2M e parênteses).
 */
class Fonemizador(private val espeak: Espeak) {

    fun fonemas(texto: String): String {
        // misaki: aspas angulares viram curvas; parênteses viram aspas angulares
        val t = texto.replace("«", "“").replace("»", "”").replace("(", "«").replace(")", "»")
        var ps = phonemize(t).firstOrNull() ?: return ""
        ps = ps.trim()
        for ((antigo, novo) in E2M) ps = ps.replace(antigo, novo)
        ps = ps.replace("^", "").replace("-", "")
        return ps.replace("«", "(").replace("»", ")")
    }

    // ------------------------------------------------------------------ phonemizer

    private data class Marca(val indice: Int, val marca: String, val posicao: Char)

    /** BaseBackend.phonemize([linha]) com preserve_punctuation=True, strip=False. */
    internal fun phonemize(linha: String): List<String> {
        val (trechos, marcas) = preservar(linha)
        val fonemizados = trechos.map { posProcessarLinha(espeak.textoParaFonemas(it)) }
        return restaurar(fonemizados.toMutableList(), marcas.toMutableList())
    }

    /** Punctuation._preserve_line (uma linha, num = 0) + filtro de trechos vazios. */
    private fun preservar(linha: String): Pair<List<String>, List<Marca>> {
        val ocorrencias = MARCAS_RE.findAll(linha).toList()
        if (ocorrencias.isEmpty()) return listOf(linha).filter { it.isNotEmpty() } to emptyList()
        if (ocorrencias.size == 1 && ocorrencias[0].value == linha) {
            return emptyList<String>() to listOf(Marca(0, linha, 'A'))
        }
        val marcas = ocorrencias.mapIndexed { i, m ->
            val posicao = when {
                i == 0 && linha.startsWith(m.value) -> 'B'
                i == ocorrencias.lastIndex && linha.endsWith(m.value) -> 'E'
                else -> 'I'
            }
            Marca(0, m.value, posicao)
        }
        val partes = mutableListOf<String>()
        var resto = linha
        for (marca in marcas) {
            // Python: split = line.split(mark); prefix = split[0]; suffix = mark.join(split[1:])
            val i = resto.indexOf(marca.marca)
            if (i < 0) { partes.add(resto); resto = ""; continue }
            partes.add(resto.substring(0, i))
            resto = resto.substring(i + marca.marca.length)
        }
        partes.add(resto)
        return partes.filter { it.isNotEmpty() } to marcas
    }

    /** EspeakBackend._postprocess_line com with_stress=True, tie="^", separador de palavra " ". */
    private fun posProcessarLinha(bruta: String): String {
        var linha = bruta.trim().replace("\n", " ").replace("  ", " ")
        linha = linha.replace(Regex("_+"), "_").replace("_ ", " ")
        if (FLAGS_IDIOMA.containsMatchIn(linha)) linha = FLAGS_IDIOMA.replace(linha, "")  // remove-flags
        if (linha.isEmpty()) return ""
        val saida = StringBuilder()
        for (palavra in linha.split(" ")) {
            saida.append(palavra.trim().replace("͡", "^")).append(' ')
        }
        return saida.toString()
    }

    /** Punctuation.restore com separador de palavra " " e strip=False. */
    private fun restaurar(texto: MutableList<String>, marcas: MutableList<Marca>): List<String> {
        val sep = " "
        val saida = mutableListOf<String>()
        var pos = 0
        while (texto.isNotEmpty() || marcas.isNotEmpty()) {
            if (marcas.isEmpty()) {
                for (l in texto) saida.add(if (!l.endsWith(sep)) l + sep else l)
                texto.clear()
            } else if (texto.isEmpty()) {
                saida.add(marcas.joinToString("") { it.marca }.replace(" ", sep))
                marcas.clear()
            } else {
                val atual = marcas[0]
                if (atual.indice == pos) {
                    marcas.removeAt(0)
                    val m = atual.marca.replace(" ", sep)
                    if (texto[0].endsWith(sep)) texto[0] = texto[0].dropLast(sep.length)
                    when (atual.posicao) {
                        'B' -> texto[0] = m + texto[0]
                        'E' -> {
                            saida.add(texto[0] + m + (if (m.endsWith(sep)) "" else sep))
                            texto.removeAt(0); pos++
                        }
                        'A' -> { saida.add(m + (if (m.endsWith(sep)) "" else sep)); pos++ }
                        else -> if (texto.size == 1) texto[0] = texto[0] + m else {
                            val primeira = texto.removeAt(0)
                            texto[0] = primeira + m + texto[0]
                        }
                    }
                } else {
                    saida.add(texto.removeAt(0)); pos++
                }
            }
        }
        return saida
    }

    companion object {
        /** misaki EspeakG2P.E2M, na mesma ordem (sorted por chave). */
        val E2M: List<Pair<String, String>> = listOf(
            "a^ɪ" to "I", "a^ʊ" to "W",
            "d^z" to "ʣ", "d^ʒ" to "ʤ",
            "e^ɪ" to "A",
            "o^ʊ" to "O", "ə^ʊ" to "Q",
            "s^s" to "S",
            "t^s" to "ʦ", "t^ʃ" to "ʧ",
            "ɔ^ɪ" to "Y",
        ).sortedBy { it.first }

        /** Punctuation(';:,.!?¡¿—…"«»“”(){}[]') — vírgula e ponto entre dígitos não contam. */
        private val MARCAS_RE = Rx.u("(\\s*(?:[;:!?¡¿—…\"«»“”()\\{\\}\\[\\]]|(?<![0-9])[,.]|[,.](?![0-9]))+\\s*)+"
        )
        private val FLAGS_IDIOMA = Regex("\\(.+?\\)")
    }
}
