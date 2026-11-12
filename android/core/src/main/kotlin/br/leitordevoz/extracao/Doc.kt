package br.leitordevoz.extracao

import br.leitordevoz.texto.Bloco
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import br.leitordevoz.texto.LeitorTxt
import br.leitordevoz.texto.Texto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Leitor mínimo do contêiner OLE / Compound File Binary ([MS-CFB]) usado pelo Office 97-2003. */
class ArquivoOle(private val dados: ByteArray) {
    private val buf = ByteBuffer.wrap(dados).order(ByteOrder.LITTLE_ENDIAN)
    private val tamSetor = 1 shl u16(0x1E)
    private val tamMini = 1 shl u16(0x20)
    private val corteMini = u32(0x38)
    private val fat: IntArray
    private val miniFat: IntArray
    private val entradas: List<Entrada>
    private val miniStream: ByteArray

    private class Entrada(val nome: String, val tipo: Int, val inicio: Int, val tamanho: Long)

    init {
        require(ehOle(dados)) { "não é OLE" }
        // DIFAT: 109 entradas no cabeçalho + setores extras
        val setoresFat = ArrayList<Int>()
        for (i in 0 until 109) { val s = u32(0x4C + i * 4); if (s >= 0) setoresFat.add(s) }
        var difat = u32(0x44); var nDifat = u32(0x48)
        while (difat >= 0 && nDifat-- > 0) {
            val base = posSetor(difat)
            for (i in 0 until tamSetor / 4 - 1) { val s = u32(base + i * 4); if (s >= 0) setoresFat.add(s) }
            difat = u32(base + tamSetor - 4)
        }
        val porSetor = tamSetor / 4
        fat = IntArray(setoresFat.size * porSetor)
        for ((k, s) in setoresFat.withIndex()) { val base = posSetor(s); for (i in 0 until porSetor) fat[k * porSetor + i] = u32(base + i * 4) }

        val dir = cadeia(u32(0x30), -1)
        entradas = (0 until dir.size / 128).map { i ->
            val e = ByteBuffer.wrap(dir, i * 128, 128).order(ByteOrder.LITTLE_ENDIAN)
            val tamNome = e.getShort(i * 128 + 64).toInt() and 0xFFFF
            val nome = String(dir, i * 128, maxOf(0, tamNome - 2), Charsets.UTF_16LE)
            Entrada(nome, dir[i * 128 + 66].toInt(), e.getInt(i * 128 + 0x74), e.getInt(i * 128 + 0x78).toLong() and 0xFFFFFFFFL)
        }
        val raiz = entradas.firstOrNull { it.tipo == 5 }
        miniStream = if (raiz != null) cadeia(raiz.inicio, raiz.tamanho) else ByteArray(0)
        val mf = cadeia(u32(0x3C), -1)
        miniFat = IntArray(mf.size / 4) { ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN).getInt(it * 4) }
    }

    fun existe(nome: String) = entradas.any { it.tipo == 2 && it.nome == nome }

    fun ler(nome: String): ByteArray {
        val e = entradas.firstOrNull { it.tipo == 2 && it.nome == nome } ?: throw ErroExtracao("Fluxo $nome ausente")
        return if (e.tamanho < corteMini) cadeiaMini(e.inicio, e.tamanho) else cadeia(e.inicio, e.tamanho)
    }

    private fun u16(p: Int) = buf.getShort(p).toInt() and 0xFFFF
    private fun u32(p: Int) = buf.getInt(p)
    private fun posSetor(s: Int) = (s + 1) * tamSetor

    private fun cadeia(inicio: Int, tamanho: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = inicio; var guarda = 0
        while (s >= 0 && guarda++ < fat.size + 1) {
            val p = posSetor(s)
            if (p + tamSetor > dados.size) { if (p < dados.size) out.write(dados, p, dados.size - p); break }
            out.write(dados, p, tamSetor)
            s = if (s < fat.size) fat[s] else -2
        }
        val b = out.toByteArray()
        return if (tamanho in 0 until b.size) b.copyOf(tamanho.toInt()) else b
    }

    private fun cadeiaMini(inicio: Int, tamanho: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = inicio; var guarda = 0
        while (s >= 0 && guarda++ < miniFat.size + 1) {
            val p = s * tamMini
            if (p + tamMini > miniStream.size) break
            out.write(miniStream, p, tamMini)
            s = if (s < miniFat.size) miniFat[s] else -2
        }
        return out.toByteArray().copyOf(tamanho.toInt())
    }

    /** Título do documento (SummaryInformation, PIDSI_TITLE), se houver. */
    fun titulo(): String {
        val nome = "\u0005SummaryInformation"
        if (!existe(nome)) return ""
        return try {
            val d = ByteBuffer.wrap(ler(nome)).order(ByteOrder.LITTLE_ENDIAN)
            val secao = d.getInt(44)
            val n = d.getInt(secao + 4)
            for (i in 0 until n) {
                val id = d.getInt(secao + 8 + i * 8); val off = d.getInt(secao + 12 + i * 8)
                if (id == 2 && d.getInt(secao + off) == 0x1E) {
                    val tam = d.getInt(secao + off + 4)
                    val bytes = ByteArray(tam).also { d.position(secao + off + 8); d.get(it) }
                    return String(bytes, charset("windows-1252")).trimEnd('\u0000').trim()
                }
            }
            ""
        } catch (e: Exception) { "" }
    }

    companion object {
        fun ehOle(b: ByteArray) = b.size >= 8 && b.copyOf(8).contentEquals(
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        )
    }
}

/**
 * DOC (Word 97-2003) lido direto do formato binário ([MS-DOC]): texto pela tabela
 * de peças, estilos de parágrafo (STSH), numeração, nível de tópico e tabelas
 * pelas propriedades de parágrafo (PAPX). Porte de extracao_word.py.
 */
object LeitorDoc {

    fun extrair(bytes: ByteArray, nome: String): Documento {
        val ole: ArquivoOle
        val wd: ByteArray
        val tabela: ByteArray
        try {
            ole = ArquivoOle(bytes)
            if (!ole.existe("WordDocument")) throw ErroExtracao("Este arquivo não é um documento do Word.")
            wd = ole.ler("WordDocument")
            if (u16(wd, 0) != 0xA5EC) throw ErroExtracao("Formato de documento do Word não reconhecido.")
            val flags = u16(wd, 0x0A)
            if (flags and 0x0100 != 0) throw ErroExtracao("Este documento do Word é protegido por senha.")
            tabela = ole.ler(if (flags and 0x0200 != 0) "1Table" else "0Table")
        } catch (e: ErroExtracao) {
            throw e
        } catch (e: Exception) {
            throw ErroExtracao("Não foi possível abrir o documento .doc (corrompido ou protegido).")
        }
        val tituloMeta = ole.titulo()
        val paragrafos = paragrafosBinarios(wd, tabela)
        var estilos: Map<Int, Pair<Int, String>> = emptyMap()
        var props: List<Prop> = emptyList()
        try {
            estilos = folhaDeEstilos(wd, tabela)
            props = propriedadesDeParagrafo(wd, tabela)
        } catch (e: Exception) { /* sem estilos: estrutura deduzida pelo texto */ }

        var blocos = mutableListOf<Bloco>()
        var titulo = ""
        if (props.isNotEmpty()) {
            val limpar = LimpadorDeCampos()
            val celulas = mutableListOf<String>(); val celula = mutableListOf<String>()
            fun fecharLinha() {
                if (celula.isNotEmpty()) { celulas.add(celula.joinToString(" ")); celula.clear() }
                val linha = celulas.filter { it.isNotEmpty() }
                if (linha.isNotEmpty()) blocos.add(Bloco("item", Texto.unirCelulas(linha)))
                celulas.clear()
            }
            for ((bruto, fc) in paragrafos) {
                val fimDeCelula = bruto.endsWith("\u0007")
                val texto = Texto.limparEspacos(limparMarcas(limpar(bruto.trimEnd('\u0007'))))
                val p = buscar(props, fc)
                if (p.fimDeLinha) { fecharLinha(); continue }
                if (p.emTabela || fimDeCelula) {
                    if (texto.isNotEmpty()) celula.add(texto)
                    if (fimDeCelula) { celulas.add(celula.joinToString(" ")); celula.clear() }
                    continue
                }
                if (celulas.isNotEmpty() || celula.isNotEmpty()) fecharLinha()
                if (texto.isEmpty()) continue
                val (sti, nomeEstilo) = estilos[p.istd] ?: (0 to "")
                blocos.add(classificar(texto, nomeEstilo, sti, p.lista, p.nivel))
            }
            fecharLinha()
        } else {
            val limpar = LimpadorDeCampos()
            val textos = paragrafos.map { Texto.limparEspacos(limparMarcas(limpar(it.first).replace("\u0007", ""))) }
            val d = LeitorTxt.extrair(textos.filter { it.isNotEmpty() }.joinToString("\n\n"), nome)
            titulo = d.titulo; blocos = d.blocos.toMutableList()
        }
        blocos = blocos.filter { Texto.temLetraOuNumero(it.texto) }.toMutableList()
        if (blocos.sumOf { it.texto.length } < 5) throw ErroExtracao("O documento do Word não tem texto para ler.")

        blocos.firstOrNull { it.tipo == "titulo" }?.let { return Documento(it.texto, blocos) }
        if (tituloMeta.isNotEmpty()) return Documento(tituloMeta, Texto.marcarTitulo(tituloMeta, blocos))
        return Documento(titulo.ifEmpty { Texto.tituloDeNome(nome) }, blocos)
    }

    private fun classificar(texto: String, nomeEstilo: String, sti: Int, lista: Boolean, nivel: Int?): Bloco {
        if (sti == 62 || EstilosWord.TITULO_DOC.matches(nomeEstilo)) return Bloco("titulo", texto, 1)
        if (sti in 1..9) return Bloco("h", texto, minOf(sti + 1, 4))
        EstilosWord.TITULO.find(nomeEstilo)?.let { return Bloco("h", texto, minOf(it.groupValues[2].toInt() + 1, 4)) }
        if (nivel != null && texto.length < 200) return Bloco("h", texto, minOf(nivel + 2, 4))
        if (EstilosWord.CITACAO.containsMatchIn(nomeEstilo)) return Bloco("citacao", texto)
        if (lista || EstilosWord.LISTA.containsMatchIn(nomeEstilo) || Texto.MARCADOR_LISTA.containsMatchIn(texto)) {
            return Bloco("item", Texto.MARCADOR_SIMBOLO.replace(texto, ""))
        }
        return Bloco("p", texto)
    }

    // ------------------------------------------------------------ binário

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun i16(b: ByteArray, p: Int) = u16(b, p).toShort().toInt()
    private fun u32(b: ByteArray, p: Int): Long =
        (u16(b, p).toLong()) or (u16(b, p + 2).toLong() shl 16)

    /** Peças do corpo principal: (texto, fc inicial, bytes por caractere) — [MS-DOC] 2.4.1. */
    private fun pecas(wd: ByteArray, tabela: ByteArray): List<Triple<String, Long, Int>> {
        val ccpTexto = u32(wd, 0x4C)
        val fcClx = u32(wd, 0x1A2).toInt(); val lcbClx = u32(wd, 0x1A6).toInt()
        val clx = tabela.copyOfRange(fcClx, minOf(tabela.size, fcClx + lcbClx))
        var i = 0
        while (i < clx.size && clx[i].toInt() == 0x01) i += 3 + i16(clx, i + 1)   // Prc: pular
        if (i >= clx.size || clx[i].toInt() != 0x02) throw ErroExtracao("Estrutura interna do .doc não reconhecida.")
        val lcb = u32(clx, i + 1).toInt()
        val plc = clx.copyOfRange(i + 5, i + 5 + lcb)
        val n = (lcb - 4) / 12
        val cps = LongArray(n + 1) { u32(plc, it * 4) }
        val saida = mutableListOf<Triple<String, Long, Int>>()
        var total = 0L
        for (k in 0 until n) {
            if (total >= ccpTexto) break
            val qtd = minOf(cps[k + 1] - cps[k], ccpTexto - total).toInt()
            val fcBruto = u32(plc, (n + 1) * 4 + k * 8 + 2)
            if (fcBruto and 0x40000000L != 0L) {   // 8 bits (cp1252)
                val fc = ((fcBruto and 0x3FFFFFFFL) / 2).toInt()
                saida.add(Triple(String(wd, fc, minOf(qtd, wd.size - fc), charset("windows-1252")), fc.toLong(), 1))
            } else {                              // UTF-16
                val fc = (fcBruto and 0x3FFFFFFFL).toInt()
                saida.add(Triple(String(wd, fc, minOf(2 * qtd, wd.size - fc), Charsets.UTF_16LE), fc.toLong(), 2))
            }
            total += qtd
        }
        return saida
    }

    /** (texto do parágrafo, posição da marca de parágrafo no arquivo); \r ou \u0007 (célula) encerram. */
    private fun paragrafosBinarios(wd: ByteArray, tabela: ByteArray): List<Pair<String, Long>> {
        val paragrafos = mutableListOf<Pair<String, Long>>()
        val atual = StringBuilder()
        for ((texto, fc, largura) in pecas(wd, tabela)) {
            var inicio = 0
            for (j in texto.indices) {
                val c = texto[j]
                if (c == '\r' || c == '\u0007') {
                    atual.append(texto, inicio, j)
                    paragrafos.add((atual.toString() + if (c == '\u0007') "\u0007" else "") to (fc + j * largura))
                    atual.setLength(0); inicio = j + 1
                }
            }
            atual.append(texto, inicio, texto.length)
        }
        if (atual.isNotBlank()) paragrafos.add(atual.toString() to -1L)
        return paragrafos
    }

    /** istd → (sti, nome do estilo) — [MS-DOC] 2.9.271. */
    private fun folhaDeEstilos(wd: ByteArray, tabela: ByteArray): Map<Int, Pair<Int, String>> {
        val fc = u32(wd, 0xA2).toInt(); val lcb = u32(wd, 0xA6).toInt()
        val stsh = tabela.copyOfRange(fc, fc + lcb)
        val cbStshi = u16(stsh, 0)
        val cstd = u16(stsh, 2); val cbBase = u16(stsh, 4)
        var pos = 2 + cbStshi
        val estilos = HashMap<Int, Pair<Int, String>>()
        for (istd in 0 until cstd) {
            if (pos + 2 > stsh.size) break
            val cbStd = u16(stsh, pos)
            val ini = pos + 2
            pos += 2 + cbStd
            if (cbStd == 0 || cbStd < cbBase + 2 || ini + cbStd > stsh.size) continue
            val sti = u16(stsh, ini) and 0x0FFF
            val cch = u16(stsh, ini + cbBase)
            val fimNome = minOf(ini + cbBase + 2 + 2 * cch, ini + cbStd)
            val nomeEstilo = String(stsh, ini + cbBase + 2, fimNome - (ini + cbBase + 2), Charsets.UTF_16LE)
            estilos[istd] = sti to nomeEstilo.split(",")[0].trim()
        }
        return estilos
    }

    private class Prop(val ini: Long, val fim: Long, val istd: Int, val lista: Boolean, val nivel: Int?, val emTabela: Boolean, val fimDeLinha: Boolean)

    private val SEM_PROP = Prop(0, 0, 0, false, null, false, false)

    /** Propriedades de cada parágrafo, das FKPs de PAPX — [MS-DOC] 2.9.175 / 2.9.209. */
    private fun propriedadesDeParagrafo(wd: ByteArray, tabela: ByteArray): List<Prop> {
        val fc = u32(wd, 0x102).toInt(); val lcb = u32(wd, 0x106).toInt()
        val plc = tabela.copyOfRange(fc, fc + lcb)
        val n = (lcb - 4) / 8
        val saida = mutableListOf<Prop>()
        for (k in 0 until n) {
            val pn = (u32(plc, (n + 1) * 4 + k * 4) and 0x3FFFFF).toInt()
            val ini = pn * 512
            if (ini + 512 > wd.size) continue
            val fkp = wd.copyOfRange(ini, ini + 512)
            val crun = fkp[511].toInt() and 0xFF
            val rgfc = LongArray(crun + 1) { u32(fkp, it * 4) }
            for (i in 0 until crun) {
                val bOffset = (fkp[(crun + 1) * 4 + i * 13].toInt() and 0xFF) * 2
                var istd = 0; var lista = false; var nivel: Int? = null; var emTabela = false; var fimDeLinha = false
                if (bOffset != 0) {
                    val cb = fkp[bOffset].toInt() and 0xFF
                    val (tam, comeco) = if (cb != 0) (2 * cb - 1) to (bOffset + 1) else (2 * (fkp[bOffset + 1].toInt() and 0xFF)) to (bOffset + 2)
                    val grp = fkp.copyOfRange(comeco, minOf(512, comeco + tam))
                    if (grp.size >= 2) {
                        istd = u16(grp, 0)
                        // sprms: numeração (sprmPIlfo), nível de tópico (sprmPOutLvl), tabela (sprmPFInTable/sprmPFTtp)
                        var j = 2
                        while (j + 2 <= grp.size) {
                            val sprm = u16(grp, j); j += 2
                            val tamOp = when (sprm ushr 13) {
                                0, 1 -> 1; 2, 4, 5 -> 2; 3 -> 4; 7 -> 3
                                else -> {
                                    if (j >= grp.size) break
                                    if (sprm == 0xC615 && (grp[j].toInt() and 0xFF) == 255) u16(grp, j) + 1 else (grp[j].toInt() and 0xFF) + 1
                                }
                            }
                            if (j + tamOp > grp.size) break
                            when (sprm) {
                                0x460B -> if (tamOp >= 2 && i16(grp, j) != 0) lista = true
                                0x2640 -> if ((grp[j].toInt() and 0xFF) < 9) nivel = grp[j].toInt() and 0xFF
                                0x2416 -> emTabela = grp[j].toInt() != 0
                                0x2417 -> fimDeLinha = grp[j].toInt() != 0
                            }
                            j += tamOp
                        }
                    }
                }
                saida.add(Prop(rgfc[i], rgfc[i + 1], istd, lista, nivel, emTabela, fimDeLinha))
            }
        }
        return saida.sortedWith(compareBy({ it.ini }, { it.fim }))
    }

    private fun buscar(props: List<Prop>, fc: Long): Prop {
        if (fc < 0 || props.isEmpty()) return SEM_PROP
        var lo = 0; var hi = props.size - 1; var k = -1
        while (lo <= hi) { val m = (lo + hi) / 2; if (props[m].ini <= fc) { k = m; lo = m + 1 } else hi = m - 1 }
        return if (k >= 0 && props[k].ini <= fc && fc < props[k].fim) props[k] else SEM_PROP
    }

    /** Remove instruções de campo (HYPERLINK, PAGE, TOC…), mantendo o resultado visível; o estado atravessa parágrafos. */
    private class LimpadorDeCampos {
        private val pilha = ArrayList<Boolean>()
        operator fun invoke(texto: String): String {
            val sb = StringBuilder()
            for (c in texto) when {
                c == '\u0013' -> pilha.add(true)
                c == '\u0014' -> if (pilha.isNotEmpty()) pilha[pilha.lastIndex] = false
                c == '\u0015' -> if (pilha.isNotEmpty()) pilha.removeAt(pilha.lastIndex)
                pilha.isNotEmpty() && pilha.last() -> {}
                else -> sb.append(c)
            }
            return sb.toString()
        }
    }

    private fun limparMarcas(t: String): String =
        Regex("[\\x{00}-\\x{06}\\x{08}\\x{0e}-\\x{12}\\x{16}-\\x{1d}]").replace(
            t.replace('\u000b', ' ').replace('\u000c', ' ').replace("\u001e", "-").replace("\u001f", ""), ""
        )
}
