package br.leitordevoz.extracao

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter

/** Coleta as letras do PDF com o PDFBox (versão de PC, usada nos testes). O app usa a mesma lógica com o PdfBox-Android. */
class ColetorGlifosJvm : PDFTextStripper() {
    val paginas = mutableListOf<PaginaBruta>()
    private var linhas = mutableListOf<LinhaBruta>()
    private val glifos = mutableListOf<Glifo>()
    private val espacos = mutableListOf<Boolean>()
    private var altura = 0f

    init { sortByPosition = false }

    override fun startPage(page: PDPage) { linhas = mutableListOf(); altura = page.cropBox.height }
    override fun endPage(page: PDPage) { fecharLinha(); paginas.add(PaginaBruta(altura, linhas)) }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (tp in textPositions) {
            if (tp.dir != 0f) continue   // texto girado (marcas d'água, margens)
            // tamanho efetivo na página (inclui a escala da página), na mesma unidade das posições
            val tam = if (tp.yScale > 0) tp.yScale else if (tp.fontSizeInPt > 0) tp.fontSizeInPt else tp.heightDir
            glifos.add(Glifo(tp.unicode ?: "", tp.xDirAdj, tp.xDirAdj + tp.widthDirAdj, tp.yDirAdj - tp.heightDir, tp.yDirAdj, tam, negrito(tp.font)))
            espacos.add(false)
        }
    }
    override fun writeWordSeparator() { if (espacos.isNotEmpty()) espacos[espacos.lastIndex] = true }
    override fun writeLineSeparator() = fecharLinha()

    private fun fecharLinha() {
        if (glifos.isNotEmpty()) linhas.add(LinhaBruta(glifos.toList(), espacos.toBooleanArray()))
        glifos.clear(); espacos.clear()
    }

    private fun negrito(f: PDFont?): Boolean {
        if (f == null) return false
        val d = f.fontDescriptor
        return Regex("(?i)bold|black|heavy|semibold").containsMatchIn(f.name ?: "") || d?.isForceBold == true || (d?.fontWeight ?: 0f) >= 600f
    }

    companion object {
        fun extrair(bytes: ByteArray, nome: String): br.leitordevoz.texto.Documento =
            PDDocument.load(bytes).use { doc ->
                val c = ColetorGlifosJvm()
                c.writeText(doc, StringWriter())
                LayoutPdf.extrair(c.paginas, doc.documentInformation?.title, nome)
            }
    }
}
