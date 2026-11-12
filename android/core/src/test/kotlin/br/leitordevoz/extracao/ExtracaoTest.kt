package br.leitordevoz.extracao

import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.Frases
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Compara a extração em Kotlin com a do app Python (testes/extracao.json), arquivo por arquivo. */
class ExtracaoTest {
    private val pasta = File(System.getProperty("fixtures"))
    private val esperado = JSONObject(File(pasta, "extracao.json").readText())

    private fun comparar(arquivo: String, extrair: (ByteArray, String) -> Documento): List<String> {
        val doc = extrair(File(pasta, "arquivos/$arquivo").readBytes(), arquivo)
        val est = Frases.estruturar(doc)
        val ref = esperado.getJSONObject(arquivo)
        val dif = mutableListOf<String>()
        if (est.titulo != ref.getString("titulo")) dif.add("título: '${est.titulo}' ≠ Python '${ref.getString("titulo")}'")
        val rb = ref.getJSONArray("blocos")
        val n = maxOf(est.blocos.size, rb.length())
        for (i in 0 until n) {
            val k = est.blocos.getOrNull(i)
            val p = if (i < rb.length()) rb.getJSONObject(i) else null
            val kTxt = k?.let { "[${it.tipo}${if (it.tipo == "h") it.nivel else ""}] ${it.frases.joinToString(" | ")}" }
            val pTxt = p?.let { o ->
                val fr = o.getJSONArray("frases"); val l = (0 until fr.length()).map { fr.getString(it) }
                "[${o.getString("tipo")}${if (o.getString("tipo") == "h") o.getInt("nivel") else ""}] ${l.joinToString(" | ")}"
            }
            if (kTxt != pTxt) dif.add("bloco $i:\n   KOTLIN: ${kTxt?.take(160)}\n   PYTHON: ${pTxt?.take(160)}")
            else if (k != null && p != null) {
                val pp = p.getJSONArray("pausas"); val lp = (0 until pp.length()).map { pp.getDouble(it) }
                if (k.pausas != lp) dif.add("bloco $i: pausas ${k.pausas} ≠ $lp")
            }
        }
        println(if (dif.isEmpty()) "OK  $arquivo: ${est.blocos.size} blocos idênticos ao Python" else "DIF $arquivo:\n" + dif.take(12).joinToString("\n"))
        return dif
    }

    @Test fun pdfRelatorio() = assertTrue(comparar("relatorio.pdf", ColetorGlifosJvm::extrair).isEmpty())
    @Test fun pdfLivro() = assertTrue(comparar("livro.pdf", ColetorGlifosJvm::extrair).isEmpty())
    private val extrator = Extrator(LeitorPdf { b, n -> ColetorGlifosJvm.extrair(b, n) })
    private fun geral(b: ByteArray, n: String) = extrator.extrair(b, "", n)

    @Test fun docxRelatorio() = assertTrue(comparar("relatorio.docx", ::geral).isEmpty())
    @Test fun docxHeadings() = assertTrue(comparar("heading123.docx", ::geral).isEmpty())
    @Test fun docxNumeracao() = assertTrue(comparar("Numbering.docx", ::geral).isEmpty())
    @Test fun docRelatorio() = assertTrue(comparar("relatorio.doc", ::geral).isEmpty())
    @Test fun docListas() = assertTrue(comparar("Lists.doc", ::geral).isEmpty())
    @Test fun html() {
        val d = geral(File(pasta, "arquivos/pagina.html").readBytes(), "pagina.html")
        val e = Frases.estruturar(d)
        println("HTML: ${e.titulo} | " + e.blocos.take(4).joinToString(" || ") { "[${it.tipo}] ${it.frases.first().take(50)}" })
        assertTrue(e.blocos.size > 5 && e.blocos.any { it.tipo == "h" })
    }
    @Test fun txt() = assertTrue(comparar("curto.txt") { b, n -> br.leitordevoz.texto.LeitorTxt.extrair(String(b), n) }.isEmpty())
}
