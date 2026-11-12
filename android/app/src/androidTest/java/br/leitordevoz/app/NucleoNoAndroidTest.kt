package br.leitordevoz.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.leitordevoz.texto.Frases
import br.leitordevoz.texto.Rx
import br.leitordevoz.texto.Texto
import br.leitordevoz.voz.PreparoFala
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roda o núcleo do app NO ANDROID (motor de expressões ICU, PdfBox-Android, espeak-ng
 * nativo) e compara com os gabaritos gerados pelo app Python (pasta testes/).
 */
@RunWith(AndroidJUnit4::class)
class NucleoNoAndroidTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as App
    private fun gabarito(nome: String) = inst.context.assets.open(nome).bufferedReader().use { it.readText() }

    @Test fun expressoesRegularesNoAndroid() {
        assertTrue("Rx deveria detectar o Android", Rx.ANDROID)
        assertEquals("a b c", Texto.limparEspacos("  a   b\t\nc "))
        assertEquals("O relatório de refatoração.", PreparoFala.preparar("O RELATÓRIO DE REFATORAÇÃO", "p"))
    }

    @Test fun extracaoIgualAoPython() {
        val esperado = JSONObject(gabarito("extracao.json"))
        val difs = mutableListOf<String>()
        for (arquivo in esperado.keys()) {
            val bytes = inst.context.assets.open("arquivos/$arquivo").use { it.readBytes() }
            val est = Frases.estruturar(app.extrator.extrair(bytes, "", arquivo))
            val ref = esperado.getJSONObject(arquivo)
            if (est.titulo != ref.getString("titulo")) difs.add("$arquivo: título '${est.titulo}' ≠ '${ref.getString("titulo")}'")
            val rb = ref.getJSONArray("blocos")
            if (est.blocos.size != rb.length()) difs.add("$arquivo: ${est.blocos.size} blocos ≠ ${rb.length()}")
            for (i in 0 until minOf(est.blocos.size, rb.length())) {
                val k = est.blocos[i]; val p = rb.getJSONObject(i)
                val fr = p.getJSONArray("frases").let { a -> (0 until a.length()).map { a.getString(it) } }
                if (k.tipo != p.getString("tipo") || k.frases != fr) { difs.add("$arquivo bloco $i: [${k.tipo}] ${k.frases.firstOrNull()?.take(60)} ≠ [${p.getString("tipo")}] ${fr.firstOrNull()?.take(60)}"); break }
            }
        }
        assertTrue(difs.joinToString("\n"), difs.isEmpty())
    }

    @Test fun fonemasIguaisAoPython() {
        val casos = JSONArray(gabarito("fonemas.json"))
        val f = app.recursos.fonemizador
        val difs = (0 until casos.length()).map { casos.getJSONObject(it) }
            .filter { f.fonemas(it.getString("texto")) != it.getString("fonemas") }
            .map { "${it.getString("texto")}\n  android: ${f.fonemas(it.getString("texto"))}\n  python:  ${it.getString("fonemas")}" }
        assertTrue("${difs.size} de ${casos.length()} frases diferem:\n" + difs.take(5).joinToString("\n"), difs.isEmpty())
    }

    @Test fun preparoIgualAoPython() {
        val j = JSONObject(gabarito("preparo.json"))
        val dic = j.getJSONObject("dicionario").let { d -> d.keys().asSequence().associateWith { d.getString(it) } }
        val casos = j.getJSONArray("casos")
        val difs = (0 until casos.length()).map { casos.getJSONObject(it) }.filter { c ->
            PreparoFala.preparar(PreparoFala.aplicarDicionario(c.getString("texto"), dic), c.getString("tipo")) != c.getString("esperado")
        }.map { "[${it.getString("tipo")}] ${it.getString("texto")} → ${it.getString("esperado")}" }
        assertTrue("${difs.size} casos diferem:\n" + difs.take(5).joinToString("\n"), difs.isEmpty())
    }
}
