package br.leitordevoz.texto

/**
 * Funções e modelos de texto compartilhados (porte de extracao.py).
 * Tipos de bloco: titulo | h | p | item | citacao | nota.
 */

class ErroExtracao(mensagem: String) : Exception(mensagem)

data class Bloco(var tipo: String, var texto: String, var nivel: Int = 0, var tamanho: Float = 0f)

data class Documento(val titulo: String, val blocos: List<Bloco>)

object Texto {
    fun limparEspacos(texto: String): String {
        var t = texto.replace("­", "").replace("ﬁ", "fi").replace("ﬂ", "fl")
            .replace("ﬀ", "ff").replace("ﬃ", "ffi").replace("ﬄ", "ffl")
        t = HIFENS.replace(t, "-")
        t = INVISIVEIS.replace(t, "")
        return ESPACOS.replace(t, " ").trim()
    }

    fun tituloDeNome(nome: String): String {
        val base = Regex("(?i)\\.[a-z0-9]{2,4}$").replace(nome.substringAfterLast('/'), "")
        return Regex("[_\\-]+").replace(base, " ").trim().ifEmpty { "Documento" }
    }

    /** Junta duas linhas, desfazendo a hifenização de fim de linha. */
    fun juntar(a: String, b: String): String {
        if (a.isEmpty()) return b
        if (HIFEN_FINAL.containsMatchIn(a) && b.isNotEmpty() && b[0].isLowerCase()) return a.dropLast(1) + b
        return "$a $b"
    }

    fun unirCelulas(celulas: List<String>): String {
        if (celulas.size == 2 && palavras(celulas[0]) <= 4 && !Regex("[.:;!?]$").containsMatchIn(celulas[0])) {
            return "${celulas[0]}: ${celulas[1]}"
        }
        return celulas.joinToString("; ")
    }

    /** Garante que o documento comece pelo título (que também é lido). */
    fun marcarTitulo(titulo: String, blocos: MutableList<Bloco>): MutableList<Bloco> {
        fun norm(s: String) = NAO_PALAVRA.replace(s, " ").trim().lowercase()
        for (b in blocos.take(3)) {
            val nb = norm(b.texto)
            if (b.tipo == "h" && nb.isNotEmpty() && (nb == norm(titulo) || norm(titulo).contains(nb))) {
                b.tipo = "titulo"; b.nivel = 1
                return blocos
            }
        }
        blocos.add(0, Bloco("titulo", titulo, 1))
        return blocos
    }

    fun palavras(t: String): Int = t.trim().split(ESPACOS).count { it.isNotEmpty() }

    fun temLetraOuNumero(t: String) = PALAVRA.containsMatchIn(t)

    fun decodificar(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
        return try {
            utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    val MARCADOR_LISTA = Regex("^(?:[•●▪◦‣∙○■□➢➤►▶✓✔\\-–—*]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])\\s+")
    val MARCADOR_SIMBOLO = Regex("^[•●▪◦‣∙○■□➢➤►▶✓✔*]\\s*")

    private val HIFENS = Regex("[‐‑‒⁃−]")
    private val INVISIVEIS = Regex("[-�​-‍﻿]")
    val ESPACOS = Regex("(?U)\\s+")
    private val HIFEN_FINAL = Regex("(?U)\\w[-‐‑­]$")
    private val NAO_PALAVRA = Regex("(?U)\\W+")
    private val PALAVRA = Regex("(?U)\\w")
}

/** Leitura de TXT (e alternativa genérica para texto corrido). */
object LeitorTxt {
    fun extrair(texto: String, nome: String = ""): Documento {
        val t = texto.replace("\r\n", "\n").replace("\r", "\n")
        val blocos = mutableListOf<Bloco>()
        for (trecho in Regex("(?U)\\n\\s*\\n").split(t)) {
            val linhas = trecho.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (linhas.isEmpty()) continue
            // Linha única, curta e sem pontuação final → título
            if (linhas.size == 1 && linhas[0].length < 90 && !Regex("[.;,!?]$").containsMatchIn(linhas[0])) {
                blocos.add(Bloco("h", Texto.limparEspacos(linhas[0]), 2))
                continue
            }
            var atual = ""
            for (l in linhas) {
                if (Texto.MARCADOR_LISTA.containsMatchIn(l)) {
                    if (atual.isNotEmpty()) blocos.add(Bloco("p", Texto.limparEspacos(atual)))
                    blocos.add(Bloco("item", Texto.limparEspacos(Texto.MARCADOR_SIMBOLO.replace(l, ""))))
                    atual = ""
                } else if (blocos.isNotEmpty() && blocos.last().tipo == "item" && atual.isEmpty() && l[0].isLowerCase()) {
                    blocos.last().texto = Texto.juntar(blocos.last().texto, l)
                } else {
                    atual = Texto.juntar(atual, l)
                }
            }
            if (atual.isNotEmpty()) blocos.add(Bloco("p", Texto.limparEspacos(atual)))
        }
        var titulo = Texto.tituloDeNome(nome)
        if (blocos.isNotEmpty() && blocos[0].tipo == "h") {
            blocos[0].tipo = "titulo"; blocos[0].nivel = 1
            titulo = blocos[0].texto
        }
        return Documento(titulo, blocos)
    }
}
