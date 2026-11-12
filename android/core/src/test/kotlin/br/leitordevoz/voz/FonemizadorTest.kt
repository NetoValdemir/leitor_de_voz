package br.leitordevoz.voz

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Compara a conversão em fonemas do app Android com a referência gerada pelo
 * app Python (misaki + phonemizer + espeak-ng 1.52), frase por frase.
 *
 * Rodar com: gradle :core:test -Despeak.jni=/caminho/libespeak_jni.so -Despeak.dados=/caminho/espeak-ng-data
 */
class FonemizadorTest {
    @Test
    fun igualAoPython() {
        val jni = System.getProperty("espeak.jni").orEmpty()
        val dados = System.getProperty("espeak.dados").orEmpty()
        assumeTrue("biblioteca JNI do PC não informada", jni.isNotEmpty() && File(jni).exists())
        System.load(jni)
        val f = Fonemizador(EspeakNativo(dados))

        val casos = Regex("\\{\\s*\"texto\":\\s*(\".*?(?<!\\\\)\"),\\s*\"fonemas\":\\s*(\".*?(?<!\\\\)\")\\s*\\}")
            .findAll(File(System.getProperty("fixtures"), "fonemas.json").readText())
            .map { desescapar(it.groupValues[1]) to desescapar(it.groupValues[2]) }.toList()
        assumeTrue(casos.isNotEmpty())

        val diferentes = casos.filter { (texto, esperado) -> f.fonemas(texto) != esperado }
        for ((texto, esperado) in diferentes.take(10)) {
            println("TEXTO:    $texto\nPYTHON:   $esperado\nKOTLIN:   ${f.fonemas(texto)}\n")
        }
        assertEquals(0, diferentes.size, "${diferentes.size} de ${casos.size} frases diferem do Python")
        println("OK: ${casos.size} frases idênticas ao Python")
    }

    private fun desescapar(json: String): String {
        val s = json.substring(1, json.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
