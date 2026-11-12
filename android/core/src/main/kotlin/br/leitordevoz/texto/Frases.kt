package br.leitordevoz.texto

/** Divisão em frases e pausas (porte de extracao.py: dividir_frases / estruturar). */
object Frases {
    const val MAX_FRASE = 280

    private val ABREVIACOES = setOf(
        "sr", "sra", "srta", "dr", "dra", "prof", "profa", "exmo", "exma", "ilmo", "ilma",
        "art", "arts", "inc", "cap", "pág", "pag", "págs", "p", "pp", "fl", "fls", "vol",
        "n", "nº", "no", "ed", "etc", "ex", "obs", "av", "r", "tel", "cf", "op", "cit",
        "jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez",
        "sto", "sta", "gen", "cel", "ten", "sgt", "min", "máx", "mín", "aprox", "séc",
        "ltda", "cia", "mr", "mrs", "vs", "fig", "tab", "eq", "id", "ibid", "s.a", "a.c", "d.c",
        "dept", "depto", "univ", "coord", "org", "orgs", "trad", "rev", "reimpr", "aux",
    )

    private val FIM = Rx.u("([.!?…]+[\"'”»)\\]]*)\\s+(?=[\"'“«(\\[]?[A-ZÁÉÍÓÚÂÊÔÃÕÀÇ0-9¿¡—–-])")
    private val ULTIMA = Regex("(\\S+)\\.$")
    private val SIGLA = Rx.u("(\\w\\.)+\\w")
    private val NUMERACAO = Regex("\\d+(\\.\\d+)*")

    fun dividir(paragrafo: String): List<String> {
        val frases = mutableListOf<String>()
        var inicio = 0
        for (m in FIM.findAll(paragrafo)) {
            val fimGrupo = m.groups[1]!!.range.last + 1
            val trecho = paragrafo.substring(inicio, fimGrupo)
            val ultima = ULTIMA.find(trecho)
            if (m.groupValues[1].startsWith(".") && ultima != null) {
                val palavra = ultima.groupValues[1].lowercase().trimStart('(', '"', '\'', '“', '«')
                if (palavra in ABREVIACOES || palavra.length == 1 || SIGLA.matches(palavra)) continue
                if (NUMERACAO.matches(palavra) && trecho.trim() == ultima.value) continue
            }
            frases.add(trecho.trim())
            inicio = m.range.last + 1
        }
        val resto = paragrafo.substring(inicio).trim()
        if (resto.isNotEmpty()) frases.add(resto)
        return juntarCurtas(frases.flatMap { quebrarLonga(it) }.filter { it.isNotEmpty() })
    }

    /** Fragmentos muito curtos ("Sim.", "Fig. 2") soam picotados sozinhos. */
    private fun juntarCurtas(frases: List<String>): List<String> {
        val saida = mutableListOf<String>()
        for (f in frases) {
            if (saida.isNotEmpty() && f.length < 12 && saida.last().length + f.length < MAX_FRASE) {
                saida[saida.lastIndex] = saida.last() + " " + f
            } else {
                saida.add(f)
            }
        }
        return saida
    }

    /** Frases muito longas atrasam o início do áudio; quebra em pausas naturais. */
    private fun quebrarLonga(frase: String): List<String> {
        if (frase.length <= MAX_FRASE) return listOf(frase)
        val meio = frase.length / 2
        var melhores = Rx.u("[;:,—–]\\s").findAll(frase).map { it.range.last + 1 }.toList()
        if (melhores.isEmpty()) {
            melhores = Rx.u("\\s(?=(e|ou|mas|porém|que|quando|onde|pois)\\s)").findAll(frase).map { it.range.last + 1 }.toList()
        }
        if (melhores.isEmpty()) melhores = Rx.u("\\s").findAll(frase).map { it.range.last + 1 }.toList()
        if (melhores.isEmpty()) return listOf(frase)
        val corte = melhores.minBy { kotlin.math.abs(it - meio) }
        return quebrarLonga(frase.substring(0, corte).trim()) + quebrarLonga(frase.substring(corte).trim())
    }

    /** Pausa (s, na velocidade 1×) depois do fim de cada bloco. */
    val PAUSAS = mapOf("titulo" to 1.1, "h" to 0.95, "p" to 0.8, "item" to 0.45, "citacao" to 0.8, "nota" to 0.5)

    fun pausaFimDeFrase(frase: String): Double {
        val f = frase.trimEnd('"', '\'', '”', '»', ')', ']', ' ')
        return when {
            f.endsWith("…") || f.endsWith("...") -> 0.6
            f.endsWith("?") || f.endsWith("!") -> 0.55
            f.endsWith(".") -> 0.45
            f.endsWith(":") || f.endsWith(";") -> 0.4
            else -> 0.35
        }
    }

    data class BlocoLeitura(val tipo: String, val nivel: Int, val frases: List<String>, val pausas: MutableList<Double>)
    data class Estrutura(val titulo: String, val blocos: List<BlocoLeitura>, val totalFrases: Int, val palavras: Int)

    fun estruturar(doc: Documento): Estrutura {
        val saida = mutableListOf<BlocoLeitura>()
        for (b in doc.blocos) {
            val frases = if ((b.tipo == "titulo" || b.tipo == "h") && b.texto.length <= MAX_FRASE) listOf(b.texto) else dividir(b.texto)
            if (frases.isEmpty()) continue
            val pausas = frases.map { pausaFimDeFrase(it) }.toMutableList()
            pausas[pausas.lastIndex] = PAUSAS[b.tipo] ?: 0.8
            saida.add(BlocoLeitura(b.tipo, b.nivel, frases, pausas))
        }
        // pausa maior antes de títulos e ao sair de uma lista
        for (i in 0 until saida.size - 1) {
            val atual = saida[i]; val proximo = saida[i + 1]
            if (proximo.tipo == "h" || proximo.tipo == "titulo") {
                atual.pausas[atual.pausas.lastIndex] = maxOf(atual.pausas.last(), 1.0)
            } else if (atual.tipo == "item" && proximo.tipo != "item") {
                atual.pausas[atual.pausas.lastIndex] = 0.7
            }
        }
        val total = saida.sumOf { it.frases.size }
        val palavras = saida.sumOf { b -> b.frases.sumOf { Texto.palavras(it) } }
        return Estrutura(doc.titulo, saida, total, palavras)
    }
}
