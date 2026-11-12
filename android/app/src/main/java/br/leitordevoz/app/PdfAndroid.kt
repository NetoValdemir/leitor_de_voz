package br.leitordevoz.app

import br.leitordevoz.extracao.Glifo
import br.leitordevoz.extracao.LayoutPdf
import br.leitordevoz.extracao.LinhaBruta
import br.leitordevoz.extracao.PaginaBruta
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter

/** Coleta as letras do PDF (posição, tamanho, negrito) para a análise de layout do :core. */
private class ColetorGlifos : PDFTextStripper() {
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
}

object PdfAndroid {
    fun extrair(bytes: ByteArray, nome: String): Documento {
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            throw ErroExtracao("Este PDF é protegido por senha.")
        } catch (e: Exception) {
            throw ErroExtracao("Não foi possível abrir o PDF (arquivo corrompido ou protegido).")
        }
        return doc.use {
            val c = ColetorGlifos()
            c.writeText(it, StringWriter())
            LayoutPdf.extrair(c.paginas, it.documentInformation?.title, nome)
        }
    }
}
