package br.leitordevoz.voz

import br.leitordevoz.texto.Rx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kokoro-82M (Apache-2.0) rodando no ONNX Runtime — porte de motor_kokoro.py e
 * de voz._sintetizar_kokoro. Uma frase por vez; devolve PCM 16 bits a 24 kHz,
 * sem o silêncio das pontas (as pausas são controladas pelo player).
 */
class MotorKokoro(
    arquivoModelo: File,
    private val estilos: Map<String, FloatArray>,   // voz → 510 × 256 floats
    private val fonemizador: Fonemizador,
    threads: Int = 4,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val sessao: OrtSession = env.createSession(
        arquivoModelo.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val entradaTokens = if ("input_ids" in sessao.inputNames) "input_ids" else "tokens"
    private val trava = Any()

    /** Texto já pronto para a fala (preparado + dicionário aplicados). */
    fun sintetizar(texto: String, voz: String, velocidade: Float): ShortArray {
        if (!Rx.u("\\w").containsMatchIn(texto)) return ShortArray(TAXA / 10)
        val estilo = estilos[voz] ?: estilos.values.first()
        val partes = dividir(fonemizador.fonemas(texto))
        val blocos = partes.mapNotNull { parte ->
            val tokens = VocabularioKokoro.tokens(parte)
            if (tokens.isEmpty()) null else inferir(tokens, estilo, velocidade.coerceIn(0.5f, 2.0f))
        }
        if (blocos.isEmpty()) return ShortArray(0)
        val audio = FloatArray(blocos.sumOf { it.size }).also { var p = 0; for (b in blocos) { b.copyInto(it, p); p += b.size } }
        var pico = 0f
        for (v in audio) pico = max(pico, abs(v))
        val escala = if (pico > 1f) 1f / pico else 1f
        val pcm = ShortArray(audio.size) { (min(1f, max(-1f, audio[it] * escala)) * 32767f).toInt().toShort() }
        return aparar(pcm)
    }

    private fun inferir(tokens: List<Int>, estilo: FloatArray, velocidade: Float): FloatArray = synchronized(trava) {
        val n = tokens.size
        val ids = LongArray(n + 2).also { for (i in tokens.indices) it[i + 1] = tokens[i].toLong() }
        val linha = min(n, estilo.size / 256 - 1)
        val s = FloatArray(256).also { System.arraycopy(estilo, linha * 256, it, 0, 256) }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, (n + 2).toLong())).use { tTok ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(s), longArrayOf(1, 256)).use { tEst ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(velocidade)), longArrayOf(1)).use { tVel ->
                    sessao.run(mapOf(entradaTokens to tTok, "style" to tEst, "speed" to tVel)).use { r ->
                        val fb = (r.get(0) as OnnxTensor).floatBuffer
                        FloatArray(fb.remaining()).also { fb.get(it) }
                    }
                }
            }
        }
    }

    override fun close() { sessao.close() }

    companion object {
        const val TAXA = 24000

        /** O modelo aceita até ~510 fonemas; divide em pontuação se passar disso. */
        fun dividir(ps: String, limite: Int = 500): List<String> {
            if (ps.length <= limite) return listOf(ps)
            val partes = mutableListOf<String>()
            var atual = ""
            for (pedaco in Rx.u("(?<=[,;:.!?])\\s+").split(ps)) {
                if (atual.isNotEmpty() && atual.length + pedaco.length + 1 > limite) { partes.add(atual); atual = pedaco }
                else atual = "$atual $pedaco".trim()
            }
            if (atual.isNotEmpty()) partes.add(atual)
            return partes.map { it.take(limite) }
        }

        /** Remove o silêncio do início e do fim (como voz._aparar). */
        fun aparar(pcm: ShortArray, taxa: Int = TAXA): ShortArray {
            var primeiro = -1; var ultimo = -1
            for (i in pcm.indices) if (abs(pcm[i].toInt()) > 400) { if (primeiro < 0) primeiro = i; ultimo = i }
            if (primeiro < 0) return ShortArray(0)
            val ini = max(0, primeiro - (taxa * 0.015).toInt())
            val fim = min(pcm.size, ultimo + (taxa * 0.045).toInt())
            return pcm.copyOfRange(ini, fim)
        }

        /** Estilos de voz (arquivo .f32: 510 × 256 floats little-endian). */
        fun lerEstilo(bytes: ByteArray): FloatArray {
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }
}
