package br.leitordevoz.extracao

import br.leitordevoz.texto.Bloco
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import br.leitordevoz.texto.LeitorTxt
import br.leitordevoz.texto.Texto
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL

/** Lê as letras de um PDF (no Android: PdfBox-Android; nos testes: PDFBox). */
fun interface LeitorPdf {
    fun ler(bytes: ByteArray, nome: String): Documento
}

/** Detecta o formato pelo conteúdo (não só pela extensão) e extrai a estrutura. */
class Extrator(private val pdf: LeitorPdf) {

    fun extrair(bytes: ByteArray, tipoConteudo: String = "", nome: String = ""): Documento {
        val ct = tipoConteudo.lowercase()
        val n = nome.lowercase()
        if (LeitorDocx.ehDocx(bytes)) return LeitorDocx.extrair(bytes, nome)
        if (ArquivoOle.ehOle(bytes)) return LeitorDoc.extrair(bytes, nome)
        if (n.endsWith(".doc") || n.endsWith(".docx") || "msword" in ct || "wordprocessingml" in ct) {
            throw ErroExtracao("O arquivo tem extensão do Word, mas o conteúdo não é um documento do Word válido.")
        }
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-" || "pdf" in ct || n.endsWith(".pdf")) {
            return pdf.ler(bytes, nome)
        }
        if (n.endsWith(".txt") || ct.startsWith("text/plain")) return LeitorTxt.extrair(Texto.decodificar(bytes), nome)
        return LeitorHtml.extrair(Texto.decodificar(bytes), nome)
    }

    /** Baixa a URL (seguindo redirecionamentos) e devolve (conteúdo, tipo, URL final). */
    fun baixar(endereco: String): Triple<ByteArray, String, String> {
        val url = endereco.trim()
        if (!Regex("(?i)^https?://").containsMatchIn(url)) {
            return try { baixar("https://$url") } catch (e: ErroExtracao) { baixar("http://$url") }
        }
        var atual = url
        repeat(8) {
            val con = try { URL(atual).openConnection() as HttpURLConnection } catch (e: Exception) {
                throw ErroExtracao("Endereço inválido.")
            }
            con.instanceFollowRedirects = false
            con.connectTimeout = 20000; con.readTimeout = 30000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36 LeitorDeVoz/1.0")
            con.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.5")
            try {
                val codigo = con.responseCode
                if (codigo in 300..399) {
                    val destino = con.getHeaderField("Location") ?: throw ErroExtracao("Redirecionamento inválido.")
                    atual = URL(URL(atual), destino).toString()
                    return@repeat
                }
                if (codigo >= 400) throw ErroExtracao("O site respondeu com erro $codigo.")
                val bytes = con.inputStream.use { entrada ->
                    val out = java.io.ByteArrayOutputStream()
                    val b = ByteArray(65536)
                    while (true) {
                        val k = entrada.read(b); if (k < 0) break
                        out.write(b, 0, k)
                        if (out.size() > TAMANHO_MAXIMO) throw ErroExtracao("O arquivo é grande demais (limite de 60 MB).")
                    }
                    out.toByteArray()
                }
                return Triple(bytes, con.contentType.orEmpty(), atual)
            } catch (e: ErroExtracao) {
                throw e
            } catch (e: Exception) {
                throw ErroExtracao("Não foi possível acessar a URL (${e.javaClass.simpleName}). Verifique a conexão com a internet.")
            } finally {
                con.disconnect()
            }
        }
        throw ErroExtracao("Redirecionamentos demais.")
    }

    companion object { const val TAMANHO_MAXIMO = 60 * 1024 * 1024 }
}

/**
 * Páginas web e arquivos HTML: o Readability (modo leitura do Firefox) separa o
 * conteúdo principal; depois títulos, parágrafos, listas, citações e tabelas
 * viram blocos. Equivale ao trafilatura do app de PC.
 */
object LeitorHtml {
    fun extrair(html: String, url: String = ""): Documento {
        val artigo = try { Readability4JExtended(url.ifEmpty { "https://localhost/" }, html).parse() } catch (e: Exception) { null }
        val conteudo = artigo?.contentWithUtf8Encoding?.let { Jsoup.parse(it).body() } ?: Jsoup.parse(html).body()
        val titulo = artigo?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: Jsoup.parse(html).title().trim().takeIf { it.isNotEmpty() }
            ?: Texto.tituloDeNome(url)

        val blocos = mutableListOf<Bloco>()
        fun texto(e: Element) = Texto.limparEspacos(e.text())
        fun visitar(e: Element) {
            when (val tag = e.tagName().lowercase()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> texto(e).takeIf { it.isNotEmpty() }?.let { blocos.add(Bloco("h", it, tag[1].digitToInt())) }
                "p" -> texto(e).takeIf { it.isNotEmpty() }?.let { blocos.add(Bloco("p", it)) }
                "ul", "ol" -> e.children().filter { it.tagName() == "li" }.forEach { li ->
                    val t = texto(li); if (t.isNotEmpty()) blocos.add(Bloco("item", Texto.MARCADOR_SIMBOLO.replace(t, "")))
                }
                "blockquote" -> texto(e).takeIf { it.isNotEmpty() }?.let { blocos.add(Bloco("citacao", it)) }
                "table" -> e.select("tr").forEach { tr ->
                    val celulas = tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { texto(it) }.filter { it.isNotEmpty() }
                    if (celulas.isNotEmpty()) blocos.add(Bloco("item", Texto.unirCelulas(celulas)))
                }
                "pre", "code", "script", "style", "nav", "footer", "figure", "img", "svg", "button", "form" -> {}
                else -> {
                    val textoProprio = e.ownText().trim()
                    if (textoProprio.isNotEmpty() && tag !in setOf("div", "section", "article", "main", "body", "span")) {
                        blocos.add(Bloco("p", texto(e)))
                    } else if (textoProprio.isNotEmpty() && e.children().none { it.isBlock }) {
                        blocos.add(Bloco("p", texto(e)))
                    } else {
                        e.children().forEach { visitar(it) }
                    }
                }
            }
        }
        conteudo.children().forEach { visitar(it) }
        if (blocos.sumOf { it.texto.length } < 40) {
            throw ErroExtracao("Não encontrei texto legível nesta página. Ela pode depender de JavaScript ou exigir login.")
        }
        return Documento(titulo, Texto.marcarTitulo(titulo, blocos))
    }
}
