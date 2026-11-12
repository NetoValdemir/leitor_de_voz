package br.leitordevoz.voz

import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class PreparoFalaTest {
    @Test fun igualAoPython() {
        val j = JSONObject(File(System.getProperty("fixtures"), "preparo.json").readText())
        val dic = j.getJSONObject("dicionario").let { d -> d.keys().asSequence().associateWith { d.getString(it) } }
        val casos = j.getJSONArray("casos")
        var dif = 0
        for (i in 0 until casos.length()) {
            val c = casos.getJSONObject(i)
            val k = PreparoFala.preparar(PreparoFala.aplicarDicionario(c.getString("texto"), dic), c.getString("tipo"))
            if (k != c.getString("esperado")) { dif++; if (dif <= 8) println("[${c.getString("tipo")}] ${c.getString("texto")}\n   KOTLIN: $k\n   PYTHON: ${c.getString("esperado")}") }
        }
        println(if (dif == 0) "OK: ${casos.length()} casos idênticos ao Python" else "$dif diferenças")
        assertEquals(0, dif)
    }
}
