package br.leitordevoz.extracao

import br.leitordevoz.texto.Rx

import br.leitordevoz.texto.Bloco
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import br.leitordevoz.texto.Texto
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Uma letra do PDF, com posição (pontos, y crescendo para baixo), tamanho e negrito. */
data class Glifo(
    val c: String, val x0: Float, val x1: Float, val y0: Float, val y1: Float,
    val tamanho: Float, val negrito: Boolean,
)

/** Letras de uma linha visual, na ordem de leitura. `espacoDepois[i]` = havia separação de palavra após o glifo i. */
class LinhaBruta(val glifos: List<Glifo>, val espacoDepois: BooleanArray)

class PaginaBruta(val altura: Float, val linhas: List<LinhaBruta>)

/**
 * Reconstrução da estrutura de um PDF a partir da posição das letras.
 * Porte fiel das regras de extracao.py (que usavam o PyMuPDF): títulos pelo
 * tamanho de fonte/negrito, parágrafos pela geometria das linhas, listas,
 * sumário, cabeçalhos/rodapés, colunas, hifenização e letras espaçadas.
 */
object LayoutPdf {

    private class Linha(
        val pagina: Int, var x0: Float, var y0: Float, var x1: Float, var y1: Float,
        var tamanho: Float, var negrito: Float,
        var segmentos: MutableList<Triple<Float, Float, String>>,
    ) {
        var texto = ""
        var tipo = "p"
        var nivel = 0
    }

    fun extrair(paginas: List<PaginaBruta>, metaTitulo: String?, nome: String): Documento {
        val alturas = paginas.map { it.altura }
        var linhas = paginas.flatMapIndexed { num, p -> linhasDaPagina(p, num) }
        linhas = removerCabecalhosERodapes(linhas, alturas, paginas.size)
        linhas = linhas.filter { Texto.temLetraOuNumero(it.texto) }
        if (linhas.sumOf { it.texto.length } < 20) {
            throw ErroExtracao("Este PDF não possui texto selecionável (provavelmente é uma imagem digitalizada). Seria necessário OCR para lê-lo.")
        }
        val corpo = tamanhoDoCorpo(linhas)
        classificar(linhas, corpo)
        val blocos = montarBlocos(linhas, corpo)
        val titulo = escolherTitulo(blocos, corpo, metaTitulo.orEmpty(), nome)
        return Documento(titulo, blocos)
    }

    // ------------------------------------------------------------ linhas

    private fun r2(v: Float) = Math.rint(v * 2.0).toFloat() / 2f   // round(x*2)/2 do Python (meio para o par)

    private fun linhasDaPagina(pagina: PaginaBruta, num: Int): List<Linha> {
        val linhas = mutableListOf<Linha>()
        for (lb in pagina.linhas) {
            val gs = lb.glifos
            if (gs.isEmpty()) continue
            // tamanho dominante da linha (ponderado por caracteres)
            val cont = LinkedHashMap<Float, Int>()
            for (g in gs) if (g.c.isNotBlank()) cont[r2(g.tamanho)] = (cont[r2(g.tamanho)] ?: 0) + 1
            val dominante = cont.maxByOrNull { it.value }?.key ?: gs[0].tamanho

            // runs de mesmo tamanho; remove índices de nota sobrescritos ("manutenção¹")
            val runs = mutableListOf<MutableList<Int>>()
            for (i in gs.indices) {
                if (runs.isNotEmpty() && abs(gs[runs.last().last()].tamanho - gs[i].tamanho) < 0.01f) runs.last().add(i)
                else runs.add(mutableListOf(i))
            }
            val remover = HashSet<Int>()
            if (runs.size > 1) {
                for (run in runs) {
                    val pequeno = gs[run[0]].tamanho < dominante * 0.8f
                    val texto = run.joinToString("") { gs[it].c }.trim()
                    if (pequeno && Regex("[\\d*†‡,]{1,4}").matches(texto)) remover.addAll(run)
                }
            }
            val idx = gs.indices.filter { it !in remover }
            if (idx.isEmpty()) continue

            val segmentos = segmentar(idx, gs, lb.espacoDepois, dominante)
            val visiveis = idx.filter { gs[it].c.isNotBlank() }.ifEmpty { idx }
            val negr = visiveis.count { gs[it].negrito }.toFloat() / visiveis.size
            val x0 = idx.minOf { gs[it].x0 }; val x1 = idx.maxOf { gs[it].x1 }
            val y0 = idx.minOf { gs[it].y0 }; val y1 = idx.maxOf { gs[it].y1 }
            linhas.add(Linha(num, x0, y0, x1, y1, dominante, negr, segmentos))
        }
        return unirMesmaAltura(linhas)
    }

    /**
     * Monta o texto da linha a partir dos glifos, separando em "segmentos" onde
     * há grandes vãos horizontais (rótulo e valor, item e página do sumário).
     * Corrige letras espaçadas ("R E L A T Ó R I O").
     */
    private fun segmentar(idx: List<Int>, gs: List<Glifo>, espaco: BooleanArray, tamanho: Float): MutableList<Triple<Float, Float, String>> {
        val segs = mutableListOf<MutableList<Int>>()
        for ((k, i) in idx.withIndex()) {
            if (k > 0 && vao(gs, i) > tamanho * 1.0f) { segs.add(mutableListOf(i)); continue }
            if (segs.isEmpty()) segs.add(mutableListOf(i)) else segs.last().add(i)
        }
        return segs.map { s -> Triple(gs[s.first()].x0, gs[s.last()].x1, textoDosGlifos(s, gs, espaco)) }.toMutableList()
    }

    /** Vão antes do glifo j, medido a partir do glifo imediatamente anterior no arquivo
     *  (mesmo que ele tenha sido removido, como um "¹" de nota de rodapé). */
    private fun vao(gs: List<Glifo>, j: Int): Float = if (j == 0) 0f else gs[j].x0 - gs[j - 1].x1

    private fun textoDosGlifos(s: List<Int>, gs: List<Glifo>, espaco: BooleanArray): String {
        // separação entre s[k] e s[k+1]: marca de palavra do PDFBox em algum glifo entre eles, ou vão visível
        fun separa(k: Int): Boolean {
            val i = s[k]; val j = s[k + 1]
            return (i until j).any { espaco[it] } || vao(gs, j) > gs[i].tamanho * 0.18f
        }
        val sb = StringBuilder()
        for (k in s.indices) {
            sb.append(gs[s[k]].c)
            if (k < s.lastIndex && separa(k)) sb.append(' ')
        }
        var texto = sb.toString()
        val tokens = texto.trim().split(Texto.ESPACOS).filter { it.isNotEmpty() }
        if (tokens.size >= 4 && tokens.count { it.length == 1 }.toFloat() / tokens.size > 0.7f) {
            // Letras espaçadas ("R E L A T Ó R I O"): o PDFBox marca separação entre todas
            // as letras. Os espaços verdadeiros vêm como caracteres de espaço no PDF; se não
            // houver nenhum, usa os vãos maiores que o espaçamento normal entre as letras.
            val temEspacoReal = s.any { gs[it].c.isBlank() }
            val visiveis = s.filter { gs[it].c.isNotBlank() }
            val vaos = (1 until visiveis.size).map { gs[visiveis[it]].x0 - gs[visiveis[it - 1]].x1 }.sorted()
            val mediana = if (vaos.isEmpty()) 0f else vaos[vaos.size / 2]
            val out = StringBuilder()
            for ((k, i) in s.withIndex()) {
                if (gs[i].c.isBlank()) { if (temEspacoReal) out.append(' '); continue }
                out.append(gs[i].c)
                if (!temEspacoReal && k < s.lastIndex) {
                    val prox = s.drop(k + 1).firstOrNull { gs[it].c.isNotBlank() }
                    if (prox != null && gs[prox].x0 - gs[i].x1 > mediana + gs[i].tamanho * 0.15f) out.append(' ')
                }
            }
            texto = out.toString()
        }
        return Regex(" +").replace(texto, " ")
    }

    private fun unirMesmaAltura(linhas: List<Linha>): List<Linha> {
        val unidas = mutableListOf<Linha>()
        for (l in linhas) {
            var alvo: Linha? = null
            for (u in unidas.takeLast(3)) {
                val sobrepoe = min(u.y1, l.y1) - max(u.y0, l.y0)
                if (u.pagina == l.pagina && sobrepoe > 0.5f * min(u.y1 - u.y0, l.y1 - l.y0) && (l.x0 >= u.x1 - 1 || l.x1 <= u.x0 + 1)) {
                    alvo = u; break
                }
            }
            if (alvo == null) { unidas.add(l); continue }
            alvo.segmentos = (alvo.segmentos + l.segmentos).sortedBy { it.first }.toMutableList()
            alvo.x0 = min(alvo.x0, l.x0); alvo.x1 = max(alvo.x1, l.x1)
            alvo.y0 = min(alvo.y0, l.y0); alvo.y1 = max(alvo.y1, l.y1)
            alvo.tamanho = max(alvo.tamanho, l.tamanho)
            alvo.negrito = (alvo.negrito + l.negrito) / 2
        }
        for (u in unidas) u.texto = textoDaLinha(u)
        return unidas
    }

    private fun textoDaLinha(l: Linha): String {
        var segs = l.segmentos.map { Triple(it.first, it.second, Texto.limparEspacos(it.third)) }.filter { it.third.isNotEmpty() }
        if (segs.isEmpty()) return ""
        // Pontilhado de sumário: "Introdução ........ 3"
        segs = segs.map { Triple(it.first, it.second, Rx.u("\\s*[.·…_]{3,}[\\s.·…_]*(\\d+)?$").replace(it.third, "")) }
            .filter { it.third.isNotEmpty() }
        if (segs.size >= 2 && Regex("(?i)\\d{1,4}|[ivxlcdm]{1,6}").matches(segs.last().third)) {
            if (segs.last().first - segs[segs.size - 2].second > 40) {   // número de página afastado à direita
                segs = segs.dropLast(1)
                l.x1 = segs.last().second
            }
        }
        if (segs.size == 1) return segs[0].third
        val partes = mutableListOf(segs[0].third)
        for (k in 1 until segs.size) {
            if (segs[k].first - segs[k - 1].second > l.tamanho * 1.5f) partes.add("\u0000")  // separador de coluna/tabela
            partes.add(segs[k].third)
        }
        val colunas = partes.joinToString(" ").split("\u0000").map { it.trim() }
        return if (colunas.size == 1) colunas[0] else Texto.unirCelulas(colunas)
    }

    private fun removerCabecalhosERodapes(linhas: List<Linha>, alturas: List<Float>, nPaginas: Int): List<Linha> {
        fun zona(l: Linha): Boolean { val h = alturas[l.pagina]; return l.y1 < h * 0.08f || l.y0 > h * 0.92f }
        fun norm(t: String) = Regex("\\d+").replace(t.lowercase(), "#").trim()
        val repetidas = HashSet<String>()
        if (nPaginas >= 2) {
            val contagem = HashMap<String, Int>()
            for (pag in 0 until nPaginas) {
                linhas.filter { it.pagina == pag && zona(it) }.map { norm(it.texto) }.toSet().forEach { contagem[it] = (contagem[it] ?: 0) + 1 }
            }
            val minimo = if (nPaginas <= 4) 2 else max(3, (nPaginas * 0.3).toInt())
            contagem.filter { it.value >= minimo }.keys.forEach { repetidas.add(it) }
        }
        val numeroPagina = Rx.u(ignorarCaixa = true, padrao = "(p[áa]g(ina)?\\.?\\s*)?[-–—]?\\s*\\d{1,4}\\s*[-–—]?(\\s*(de|/|of)\\s*\\d{1,4})?")
        return linhas.filter { !(zona(it) && (norm(it.texto) in repetidas || numeroPagina.matches(it.texto.trim()))) }
    }

    private fun tamanhoDoCorpo(linhas: List<Linha>): Float {
        val cont = LinkedHashMap<Float, Int>()
        for (l in linhas) cont[l.tamanho] = (cont[l.tamanho] ?: 0) + l.texto.length
        return cont.maxByOrNull { it.value }!!.key
    }

    // ------------------------------------------------------------ classificação

    private fun classificar(linhas: List<Linha>, corpo: Float) {
        val tamanhosTitulo = linhas.filter { it.tamanho >= corpo * 1.12f && it.texto.length < 200 }.map { it.tamanho }.toSet().sortedDescending()
        for (l in linhas) {
            val t = l.texto
            when {
                l.tamanho >= corpo * 1.12f && t.length < 200 -> {
                    l.tipo = "h"
                    l.nivel = if (l.tamanho in tamanhosTitulo) min(2 + tamanhosTitulo.indexOf(l.tamanho), 4) else 3
                }
                l.negrito > 0.85f && l.tamanho >= corpo * 0.95f && t.length < 120 &&
                    !Regex("[.;,]$").containsMatchIn(t) && Texto.palavras(t) <= 14 -> { l.tipo = "h"; l.nivel = 4 }
                l.tamanho < corpo * 0.85f -> l.tipo = "nota"
                else -> l.tipo = "p"
            }
        }
    }

    /** Margens recorrentes (ex.: borda direita de cada coluna). round(v/3)*3 do Python. */
    private fun bordas(valores: List<Float>, minimo: Int = 3): List<Float> {
        val cont = LinkedHashMap<Float, Int>()
        for (v in valores) { val k = (Math.rint(v / 3.0) * 3).toFloat(); cont[k] = (cont[k] ?: 0) + 1 }
        return cont.filter { it.value >= minimo }.keys.sorted()
    }

    private fun mediana(v: List<Float>): Float {
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun montarBlocos(linhas: List<Linha>, corpo: Float): MutableList<Bloco> {
        val textoCorrido = linhas.filter { it.tipo == "p" || it.tipo == "nota" }.ifEmpty { linhas }
        val bordasDir = HashMap<Int, List<Float>>()
        val bordasEsq = HashMap<Int, List<Float>>()
        for (pag in linhas.map { it.pagina }.toSet()) {
            val daPagina = textoCorrido.filter { it.pagina == pag }.ifEmpty { linhas.filter { it.pagina == pag } }
            val minimo = max(3, daPagina.size / 10)
            val direita = (bordas(daPagina.map { it.x1 }, minimo) + (Math.rint(daPagina.maxOf { it.x1 } / 3.0) * 3).toFloat()).toSet()
            bordasDir[pag] = direita.sorted()
            // margens esquerdas: só linhas "cheias", para itens recuados não virarem coluna
            val cheias = daPagina.filter { l -> direita.any { abs(l.x1 - it) <= 4 } }
            val esquerdas = bordas(cheias.map { it.x0 }, 1).ifEmpty { listOf(daPagina.minOf { it.x0 }) }
            // descarta "margens" que são só recuo de primeira linha
            bordasEsq[pag] = esquerdas.filter { b -> esquerdas.none { o -> b - o > 0 && b - o <= 3 * corpo } }
        }
        val entrelinhas = linhas.zipWithNext().filter { (a, b) ->
            a.pagina == b.pagina && a.tipo == "p" && b.tipo == "p" && b.y0 - a.y0 > 0 && b.y0 - a.y0 < corpo * 3
        }.map { (a, b) -> b.y0 - a.y0 }
        val entrelinha = if (entrelinhas.isNotEmpty()) mediana(entrelinhas) else corpo * 1.4f

        fun bordaDireita(l: Linha): Float = bordasDir[l.pagina]!!.filter { it >= l.x1 - 4 }.minOrNull() ?: l.x1
        fun margemEsquerda(l: Linha): Float = bordasEsq[l.pagina]!!.filter { it <= l.x0 + 4 }.maxOrNull() ?: l.x0
        fun terminouCedo(l: Linha): Boolean {
            val direita = bordaDireita(l)
            val largura = max(direita - margemEsquerda(l), 1f)
            return direita - l.x1 > max(3 * l.tamanho, 0.15f * largura) && !Regex("[-‐­]$").containsMatchIn(l.texto)
        }
        fun proximaAlinhada(i: Int): Boolean {
            if (i + 1 >= linhas.size) return false
            val l = linhas[i]; val p = linhas[i + 1]
            return p.pagina == l.pagina && abs(p.x0 - l.x0) < 2 && p.tipo == "p"
        }

        val blocos = mutableListOf<Bloco>()
        var atual: Bloco? = null
        var anterior: Linha? = null
        for ((i, l) in linhas.withIndex()) {
            val recuo = l.x0 - margemEsquerda(l)
            val texto = l.texto
            val marcador = Texto.MARCADOR_LISTA.containsMatchIn(texto)
            var tipo = l.tipo
            if (tipo == "p" && (marcador || recuo >= 2 * l.tamanho)) {
                tipo = if (marcador || (anterior != null && terminouCedo(anterior)) || proximaAlinhada(i)) "item" else "p"
            }
            var novo = true
            val a = atual
            if (a != null && anterior != null) {
                val mesmaColuna = anterior.pagina == l.pagina && l.y0 > anterior.y0
                val salto = l.y0 - anterior.y0
                novo = when {
                    a.tipo == "h" && tipo == "h" -> !(mesmaColuna && abs(l.tamanho - anterior.tamanho) < 0.6f &&
                        salto < l.tamanho * 2.2f && !Regex("[.!?]$").containsMatchIn(a.texto))
                    tipo == "h" || a.tipo == "h" || a.tipo == "titulo" -> true
                    marcador -> true
                    abs(l.tamanho - anterior.tamanho) > 1.0f && tipo != a.tipo -> true
                    terminouCedo(anterior) -> true
                    mesmaColuna && salto > entrelinha * 1.45f -> true   // espaço extra entre parágrafos
                    mesmaColuna && l.x0 - anterior.x0 > 0.8f * l.tamanho && !Texto.MARCADOR_LISTA.containsMatchIn(a.texto) && a.tipo != "item" -> true
                    a.tipo == "item" && tipo == "item" && abs(l.x0 - anterior.x0) < 2 && terminouCedo(anterior) -> true
                    else -> false
                }
                // Continuação entre colunas/páginas: frase inacabada + minúscula
                if (novo && tipo == "p" && a.tipo == "p" && !mesmaColuna && l.texto.isNotEmpty() && l.texto[0].isLowerCase() &&
                    !Regex("[.!?:…]$").containsMatchIn(a.texto)) novo = false
            }
            if (novo) {
                atual?.let { blocos.add(it) }
                atual = Bloco(tipo, texto, l.nivel, l.tamanho)
            } else {
                atual!!.texto = Texto.juntar(atual.texto, texto)
            }
            anterior = l
        }
        atual?.let { blocos.add(it) }

        val resultado = mutableListOf<Bloco>()
        var emSumario = false
        val sumario = Regex("(?i)(sum[áa]rio|[íi]ndice( geral)?|conte[úu]do|contents)")
        for (b in blocos) {
            var t = Texto.limparEspacos(b.texto)
            if (b.tipo == "h") {
                emSumario = sumario.matches(t)
            } else if (emSumario && b.tipo == "p" && t.length < 120 && !Regex("[.!?]$").containsMatchIn(t)) {
                b.tipo = "item"
            } else if (emSumario) {
                emSumario = false
            }
            if (b.tipo == "item") t = Texto.MARCADOR_SIMBOLO.replace(t, "")
            if (!Texto.temLetraOuNumero(t)) continue
            resultado.add(Bloco(b.tipo, t, b.nivel, b.tamanho))
        }
        return resultado
    }

    private fun escolherTitulo(blocos: MutableList<Bloco>, corpo: Float, meta: String, nome: String): String {
        // O maior texto do início do documento costuma ser o título
        val maior = blocos.take(12).maxByOrNull { it.tamanho }
        if (maior != null && maior.tamanho >= corpo * 1.4f && maior.texto.length < 160) {
            maior.tipo = "titulo"; maior.nivel = 1
            return maior.texto
        }
        val m = meta.trim()
        if (m.length > 3 && !Regex("(?i)\\.(docx?|pdf|odt|tex|indd)$|^untitled|^microsoft").containsMatchIn(m)) {
            blocos.add(0, Bloco("titulo", m, 1))
            return m
        }
        return Texto.tituloDeNome(nome).ifEmpty { "Documento PDF" }
    }
}
