package br.leitordevoz.extracao

import br.leitordevoz.texto.Bloco
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import br.leitordevoz.texto.Texto
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Regras de estilo do Word, comuns ao DOCX e ao DOC (porte de extracao_word.py). */
object EstilosWord {
    val TITULO = Regex("(?i)^(heading|t[íi]tulo|überschrift|titre)\\s*(\\d)$")
    val LISTA = Regex("(?i)list|lista|marcador|bullet|numera")
    val CITACAO = Regex("(?i)quote|cita")
    val IGNORAR = Regex("(?i)^(toc|sum[áa]rio)\\s*\\d*$|caption|legenda|footnote|nota de rodap")
    val TITULO_DOC = Regex("(?i)title|t[íi]tulo")
    val SUBTITULO = Regex("(?i)subtitle|subt[íi]tulo")

    /** Título final do documento do Word (mesma regra para DOCX e DOC). */
    fun definirTitulo(blocos: MutableList<Bloco>, tituloMeta: String, nome: String, usarPrimeiroH: Boolean): Pair<String, MutableList<Bloco>> {
        blocos.firstOrNull { it.tipo == "titulo" }?.let { return it.texto to blocos }
        if (tituloMeta.isNotEmpty()) return tituloMeta to Texto.marcarTitulo(tituloMeta, blocos)
        if (usarPrimeiroH) {
            blocos.take(5).firstOrNull { it.tipo == "h" }?.let { it.tipo = "titulo"; it.nivel = 1; return it.texto to blocos }
        }
        return Texto.tituloDeNome(nome) to blocos
    }
}

/**
 * DOCX: lê word/document.xml na ordem real (parágrafos e tabelas), usando os
 * estilos (Título, Título 1/2/3…, listas, citações), nível de tópico, numeração
 * e formatação (títulos "manuais" em negrito ou fonte maior). Cabeçalhos,
 * rodapés, notas, caixas de texto e campos ocultos não são lidos.
 */
object LeitorDocx {

    fun ehDocx(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return try { arquivosDoZip(bytes, setOf("word/document.xml")).isNotEmpty() } catch (e: Exception) { false }
    }

    private fun arquivosDoZip(bytes: ByteArray, nomes: Set<String>): Map<String, String> {
        val saida = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name in nomes) saida[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return saida
    }

    private class Estilo(val nome: String, val negrito: Boolean, val nivelTopico: Int?, val numerado: Boolean)

    fun extrair(bytes: ByteArray, nome: String): Documento {
        val arquivos = try {
            arquivosDoZip(bytes, setOf("word/document.xml", "word/styles.xml", "docProps/core.xml"))
        } catch (e: Exception) {
            throw ErroExtracao("Não foi possível abrir o documento do Word (arquivo corrompido ou protegido).")
        }
        val documento = arquivos["word/document.xml"]
            ?: throw ErroExtracao("Não foi possível abrir o documento do Word (arquivo corrompido ou protegido).")
        val xml = Jsoup.parse(documento, "", Parser.xmlParser())
        val corpoXml = xml.getElementsByTag("w:body").firstOrNull() ?: throw ErroExtracao("O documento do Word não tem texto para ler.")

        val (estilos, padrao) = lerEstilos(arquivos["word/styles.xml"])

        val itens = corpoXml.children().filter { it.tagName() == "w:p" || it.tagName() == "w:tbl" }

        // Tamanho de fonte do corpo do texto (para reconhecer títulos "manuais")
        val tamanhos = LinkedHashMap<Float, Int>()
        for (p in itens.filter { it.tagName() == "w:p" }) for (r in runs(p)) {
            val t = textoDoRun(r)
            val tam = tamanhoDoRun(r)
            if (tam != null && t.isNotBlank()) tamanhos[tam] = (tamanhos[tam] ?: 0) + t.length
        }
        val corpo = tamanhos.maxByOrNull { it.value }?.key

        val blocos = mutableListOf<Bloco>()
        for (item in itens) {
            if (item.tagName() == "w:tbl") blocos.addAll(tabela(item))
            else paragrafo(item, estilos, padrao, corpo)?.let { blocos.add(it) }
        }
        val filtrados = blocos.filter { Texto.temLetraOuNumero(it.texto) }.toMutableList()
        if (filtrados.sumOf { it.texto.length } < 5) throw ErroExtracao("O documento do Word não tem texto para ler.")

        val tituloMeta = arquivos["docProps/core.xml"]?.let { core ->
            Jsoup.parse(core, "", Parser.xmlParser()).getElementsByTag("dc:title").firstOrNull()?.text()?.trim()
        }.orEmpty()
        val (titulo, finais) = EstilosWord.definirTitulo(filtrados, tituloMeta, nome, usarPrimeiroH = true)
        return Documento(titulo, finais)
    }

    private fun lerEstilos(xml: String?): Pair<Map<String, Estilo>, Estilo?> {
        if (xml == null) return emptyMap<String, Estilo>() to null
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val mapa = HashMap<String, Estilo>()
        var padrao: Estilo? = null
        for (s in doc.getElementsByTag("w:style")) {
            val id = s.attr("w:styleId")
            val nomeEstilo = filho(s, "w:name")?.attr("w:val").orEmpty()
            val rPr = filho(s, "w:rPr"); val pPr = filho(s, "w:pPr")
            val estilo = Estilo(
                nome = nomeEstilo,
                negrito = ligado(rPr?.let { filho(it, "w:b") }) == true,
                nivelTopico = pPr?.let { filho(it, "w:outlineLvl") }?.attr("w:val")?.toIntOrNull()?.takeIf { it < 9 },
                numerado = pPr?.let { filho(it, "w:numPr") } != null,
            )
            mapa[id] = estilo
            if (s.attr("w:type") == "paragraph" && (s.attr("w:default") == "1" || s.attr("w:default") == "true")) padrao = estilo
        }
        return mapa to padrao
    }

    private fun filho(e: Element, tag: String): Element? = e.children().firstOrNull { it.tagName() == tag }

    /** w:b / w:i: presente sem valor ou com valor verdadeiro = ligado. null = ausente. */
    private fun ligado(e: Element?): Boolean? {
        if (e == null) return null
        val v = e.attr("w:val").lowercase()
        return v.isEmpty() || v == "true" || v == "1" || v == "on"
    }

    /** Runs do parágrafo, como no python-docx: w:r diretos e dentro de w:hyperlink. */
    private fun runs(p: Element): List<Element> = p.children().flatMap { c ->
        when (c.tagName()) {
            "w:r" -> listOf(c)
            "w:hyperlink" -> c.children().filter { it.tagName() == "w:r" }
            else -> emptyList()
        }
    }

    private fun textoDoRun(r: Element): String {
        val sb = StringBuilder()
        for (c in r.children()) when (c.tagName()) {
            "w:t" -> sb.append(c.wholeText())
            "w:tab", "w:ptab" -> sb.append('\t')
            "w:br", "w:cr" -> sb.append('\n')
            "w:noBreakHyphen" -> sb.append('-')
        }
        return sb.toString()
    }

    private fun tamanhoDoRun(r: Element): Float? =
        filho(r, "w:rPr")?.let { filho(it, "w:sz") }?.attr("w:val")?.toFloatOrNull()?.div(2f)

    private fun negritoDoRun(r: Element): Boolean? = filho(r, "w:rPr")?.let { ligado(filho(it, "w:b")) }

    private fun textoDoParagrafo(p: Element) = runs(p).joinToString("") { textoDoRun(it) }

    private fun paragrafo(p: Element, estilos: Map<String, Estilo>, padrao: Estilo?, corpo: Float?): Bloco? {
        val texto = Texto.limparEspacos(textoDoParagrafo(p).replace("\t", " "))
        if (texto.isEmpty()) return null
        val pPr = filho(p, "w:pPr")
        val estilo = pPr?.let { filho(it, "w:pStyle") }?.attr("w:val")?.let { estilos[it] } ?: padrao
        val nomeEstilo = estilo?.nome.orEmpty()

        if (EstilosWord.IGNORAR.containsMatchIn(nomeEstilo)) return null
        if (EstilosWord.TITULO_DOC.matches(nomeEstilo)) return Bloco("titulo", texto, 1)
        if (EstilosWord.SUBTITULO.matches(nomeEstilo)) return Bloco("p", texto)
        EstilosWord.TITULO.find(nomeEstilo)?.let { return Bloco("h", texto, minOf(it.groupValues[2].toInt() + 1, 4)) }
        val nivel = pPr?.let { filho(it, "w:outlineLvl") }?.attr("w:val")?.toIntOrNull()?.takeIf { it < 9 } ?: estilo?.nivelTopico
        if (nivel != null && texto.length < 200) return Bloco("h", texto, minOf(nivel + 2, 4))
        if (EstilosWord.CITACAO.containsMatchIn(nomeEstilo)) return Bloco("citacao", texto)
        val numerado = pPr?.let { filho(it, "w:numPr") } != null || estilo?.numerado == true
        if (EstilosWord.LISTA.containsMatchIn(nomeEstilo) || numerado) return Bloco("item", Texto.MARCADOR_SIMBOLO.replace(texto, ""))

        // Títulos "manuais": parágrafo curto, sem ponto final, todo em negrito ou com fonte maior
        val corridos = runs(p).filter { textoDoRun(it).isNotBlank() }
        if (corridos.isNotEmpty() && texto.length < 120 && Texto.palavras(texto) <= 14 && !Regex("[.;,]$").containsMatchIn(texto)) {
            val negrito = corridos.all { r -> negritoDoRun(r) ?: (estilo?.negrito == true) }
            val maior = corpo != null && corridos.all { r -> tamanhoDoRun(r)?.let { it >= corpo * 1.15f } == true }
            if (negrito || maior) return Bloco("h", texto, if (maior) 3 else 4)
        }
        if (Texto.MARCADOR_LISTA.containsMatchIn(texto)) return Bloco("item", Texto.MARCADOR_SIMBOLO.replace(texto, ""))
        return Bloco("p", texto)
    }

    /** Cada linha da tabela vira um item ("Rótulo: valor" quando são 2 colunas). */
    private fun tabela(tbl: Element): List<Bloco> = tbl.children().filter { it.tagName() == "w:tr" }.mapNotNull { tr ->
        val celulas = tr.children().filter { it.tagName() == "w:tc" }.map { tc ->
            Texto.limparEspacos(tc.children().filter { it.tagName() == "w:p" }.joinToString("\n") { textoDoParagrafo(it) })
        }.filter { it.isNotEmpty() }
        if (celulas.isEmpty()) null else Bloco("item", Texto.unirCelulas(celulas))
    }
}
