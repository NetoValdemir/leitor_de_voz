package br.leitordevoz.voz

import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/** Áudio gerado pelo motor Kotlin (mesmo código do Android) × app Python, frase por frase. */
class MotorKokoroTest {
    @Test fun audioIgualAoPython() {
        val modelo = File(System.getProperty("kokoro.modelo").orEmpty())
        val ref = File(System.getProperty("kokoro.referencia").orEmpty())
        val jni = System.getProperty("espeak.jni").orEmpty()
        assumeTrue(modelo.exists() && ref.isDirectory && File(jni).exists())
        System.load(jni)
        val assets = File(System.getProperty("assets"))
        val fon = Fonemizador(EspeakNativo(File(assets, "espeak-ng-data").absolutePath))
        val estilos = listOf("pf_dora", "pm_alex", "pm_santa").associateWith { MotorKokoro.lerEstilo(File(assets, "vozes/$it.f32").readBytes()) }
        MotorKokoro(modelo, estilos, fon).use { motor ->
            val meta = JSONArray(File(ref, "meta.json").readText())
            for (i in 0 until meta.length()) {
                val m = meta.getJSONObject(i)
                val texto = PreparoFala.preparar(m.getString("texto"), "p")
                val t0 = System.nanoTime()
                val k = motor.sintetizar(texto, m.getString("voz"), m.getDouble("velocidade").toFloat())
                val ms = (System.nanoTime() - t0) / 1e6
                val pb = ByteBuffer.wrap(File(ref, "$i.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val p = ShortArray(pb.remaining()).also { pb.get(it) }
                val n = minOf(k.size, p.size)
                var dot = 0.0; var nk = 0.0; var np = 0.0; var maxDif = 0
                for (j in 0 until n) { dot += k[j] * p[j].toDouble(); nk += k[j] * k[j].toDouble(); np += p[j] * p[j].toDouble(); maxDif = maxOf(maxDif, abs(k[j] - p[j])) }
                val corr = dot / sqrt(nk * np)
                println("frase $i (${m.getString("voz")}, ${m.getDouble("velocidade")}×): amostras Kotlin ${k.size} / Python ${p.size} | correlação %.5f | dif. máx %d | %.0f ms para %.1f s de áudio".format(corr, maxDif, ms, k.size / 24000.0))
                assertTrue(abs(k.size - p.size) <= 2 && corr > 0.999, "frase $i diverge do Python")
            }
        }
    }
}
