package br.leitordevoz.voz

/** Ajustes do texto antes da fala e dicionário de pronúncia (porte de voz.py). */
object PreparoFala {

    /** Troca palavras pela grafia de pronúncia, mantendo a maiúscula inicial. */
    fun aplicarDicionario(texto: String, palavras: Map<String, String>): String {
        if (palavras.isEmpty()) return texto
        val alternativas = palavras.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val padrao = Regex("(?iU)(?<![\\w-])($alternativas)(?![\\w-])")
        return padrao.replace(texto) { m ->
            val original = m.groupValues[1]
            val nova = palavras[original.lowercase()] ?: return@replace original
            if (original.isNotEmpty() && original[0].isUpperCase()) nova.replaceFirstChar { it.uppercase() } else nova
        }
    }

    fun preparar(texto: String, tipo: String = "p"): String {
        var t = Regex("(?U)https?://\\S+|www\\.\\S+").replace(texto, " link ")
        t = Regex("(?U)\\S+@\\S+\\.\\w+").replace(t, " e-mail ")
        t = Regex("\\[\\d+(?:[,–-]\\d+)*\\]").replace(t, "")   // marcadores de referência [12]
        t = t.replace("&", " e ").replace("§", " parágrafo ").replace("n.º", "número ").replace("nº", "número ")
        t = Regex("(?U)\\s+[·•|]\\s+").replace(t, ", ")         // separadores visuais viram pausa
        t = Regex("[_*#|~^<>{}\\\\]+").replace(t, " ")
        // Sequências em CAIXA ALTA soam robóticas: lê como texto normal. Siglas curtas ficam.
        t = Regex("(?U)\\b[A-ZÁÉÍÓÚÂÊÔÃÕÀÇ]{2,}(?:[\\s,:\\-]+[A-ZÁÉÍÓÚÂÊÔÃÕÀÇ0-9]{2,})+\\b").replace(t) { it.value.lowercase() }
        t = Regex("(?U)\\b[A-ZÁÉÍÓÚÂÊÔÃÕÀÇ]{6,}\\b").replace(t) { it.value.lowercase() }
        if (tipo == "titulo" || tipo == "h" || tipo == "item") {
            // "2.1 Responsabilidade única" → pausa depois da numeração
            t = Regex("(?U)^(\\d+(?:\\.\\d+)*)\\.?\\s+(?=\\S)").replace(t, "$1. ")
            t = Regex("(?U)^([a-zA-Z])\\)\\s+").replace(t, "$1) ")
        }
        t = Regex("(?U)\\s+").replace(t, " ").trim()
        if (t.isNotEmpty() && t[0].isLowerCase()) t = t[0].uppercaseChar() + t.substring(1)
        // Sem pontuação final, a voz termina a frase "no ar"; um ponto fecha a entonação.
        if (t.isNotEmpty() && !Regex("[.!?…:;,]['\"”»)\\]]*$").containsMatchIn(t)) t += "."
        return t
    }
}
