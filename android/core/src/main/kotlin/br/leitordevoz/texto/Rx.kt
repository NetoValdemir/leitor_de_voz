package br.leitordevoz.texto

/**
 * Expressões regulares com \s, \w e \b valendo para todo o Unicode (acentos, espaços especiais).
 *
 * No Java do PC isso exige a opção (?U). No Android, o motor de expressões (ICU) já é
 * Unicode por padrão e NÃO aceita (?U) — usá-la lá faz a expressão falhar. Por isso a
 * opção só é acrescentada fora do Android.
 */
object Rx {
    val ANDROID: Boolean = System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
        runCatching { Class.forName("android.os.Build") }.isSuccess

    private val UNICODE = if (ANDROID) "" else "(?U)"

    fun u(padrao: String, ignorarCaixa: Boolean = false): Regex =
        Regex((if (ignorarCaixa) "(?i)" else "") + UNICODE + padrao)
}
