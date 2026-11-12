package br.leitordevoz.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import br.leitordevoz.texto.Frases
import br.leitordevoz.voz.MotorKokoro
import br.leitordevoz.voz.PreparoFala
import java.util.concurrent.ConcurrentHashMap

/**
 * Player de leitura: gera o áudio das próximas frases (Kokoro) e toca num AudioTrack
 * contínuo, inserindo as pausas de fim de frase/bloco. O destaque da frase segue a
 * posição real de reprodução. Funciona com a tela apagada (dentro do LeitorService).
 */
class Leitor(
    private val motor: () -> MotorKokoro,          // carregado sob demanda
    private val dicionario: () -> Map<String, String>,
    private val eventos: Eventos,
) {
    interface Eventos {
        fun frase(idx: Int)
        fun estado(tocando: Boolean, carregando: Boolean)
        fun fim()
        fun erro(mensagem: String)
        fun desempenho(vezesTempoReal: Double)
    }

    private class Frase(val texto: String, val tipo: String, val pausa: Double)

    private val trava = Object()
    @Volatile private var frases: List<Frase> = emptyList()
    @Volatile var tocando = false
        private set
    @Volatile var atual = 0            // frase sendo ouvida
        private set
    @Volatile private var geracao = 0  // muda a cada salto/troca de voz: descarta o que estava em andamento
    @Volatile private var proximaEscrever = 0
    @Volatile private var encerrado = false

    @Volatile var voz = Vozes.PADRAO
    @Volatile var velocidade = 1f
    @Volatile var fatorPausas = 1f
    @Volatile private var versaoAudio = 0   // muda quando voz/velocidade/dicionário mudam

    private val cache = ConcurrentHashMap<Int, Pair<Int, ShortArray>>()   // idx → (versaoAudio, pcm)
    private val marcas = ArrayList<Pair<Long, Int>>()                     // (quadro inicial, idx)
    private var track: AudioTrack? = null
    private var quadrosEscritos = 0L
    private var medias = ArrayDeque<Double>()

    private val sintetizador = Thread({ loopSintese() }, "leitor-sintese").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    private val reprodutor = Thread({ loopReproducao() }, "leitor-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    private val relogio = Thread({ loopPosicao() }, "leitor-posicao").apply { isDaemon = true }

    init { sintetizador.start(); reprodutor.start(); relogio.start() }

    // ------------------------------------------------------------------ comandos

    fun carregar(estrutura: Frases.Estrutura): Unit = synchronized(trava) {
        pararInterno()
        frases = estrutura.blocos.flatMap { b -> b.frases.mapIndexed { i, f -> Frase(f, b.tipo, b.pausas[i]) } }
        cache.clear()
        atual = 0; proximaEscrever = 0
        trava.notifyAll()
    }

    fun liberar(): Unit = synchronized(trava) {
        pararInterno()
        frases = emptyList(); cache.clear()
        trava.notifyAll()
    }

    fun total() = frases.size

    fun tocar(idx: Int = atual) {
        synchronized(trava) {
            if (frases.isEmpty()) return
            saltarInterno(idx.coerceIn(0, frases.size - 1))
            tocando = true
            trava.notifyAll()
        }
        eventos.estado(true, cache[atual] == null)
    }

    fun pausar() {
        synchronized(trava) {
            tocando = false
            track?.pause()
            trava.notifyAll()
        }
        eventos.estado(false, false)
    }

    fun retomar() {
        synchronized(trava) {
            if (frases.isEmpty()) return
            tocando = true
            track?.play()
            trava.notifyAll()
        }
        eventos.estado(true, false)
    }

    fun irPara(idx: Int) {
        synchronized(trava) {
            if (frases.isEmpty()) return
            saltarInterno(idx.coerceIn(0, frases.size - 1))
            trava.notifyAll()
        }
        eventos.frase(atual)
    }

    /** Voz, velocidade, pausas ou dicionário mudaram: refaz o áudio a partir da frase atual. */
    fun ajustar(novaVoz: String = voz, novaVelocidade: Float = velocidade, novasPausas: Float = fatorPausas, dicionarioMudou: Boolean = false): Unit = synchronized(trava) {
        val mudouAudio = novaVoz != voz || novaVelocidade != velocidade || dicionarioMudou
        voz = novaVoz; velocidade = novaVelocidade; fatorPausas = novasPausas
        if (mudouAudio) { versaoAudio++; cache.clear() }
        if (frases.isNotEmpty()) saltarInterno(atual)
        trava.notifyAll()
    }

    fun encerrar(): Unit = synchronized(trava) {
        encerrado = true; pararInterno(); trava.notifyAll()
    }

    private fun pararInterno() {
        tocando = false
        geracao++
        track?.let { it.pause(); it.flush() }
    }

    private fun saltarInterno(idx: Int) {
        geracao++
        atual = idx; proximaEscrever = idx
        track?.let { it.pause(); it.flush() }
        // descarta áudio fora da janela útil
        cache.keys.filter { it < idx - 1 || it > idx + PRE_CARREGAR + 2 }.forEach { cache.remove(it) }
    }

    // ------------------------------------------------------------------ síntese

    private fun proximoParaSintetizar(): Int? {
        val base = proximaEscrever
        for (i in base until minOf(frases.size, base + PRE_CARREGAR)) {
            val c = cache[i]
            if (c == null || c.first != versaoAudio) return i
        }
        return null
    }

    private fun loopSintese() {
        while (!encerrado) {
            val (idx, versao, frase) = synchronized(trava) {
                var alvo = proximoParaSintetizar()
                while (alvo == null && !encerrado) { trava.wait(); alvo = proximoParaSintetizar() }
                if (encerrado) return
                Triple(alvo!!, versaoAudio, frases[alvo])
            }
            try {
                val texto = PreparoFala.preparar(PreparoFala.aplicarDicionario(frase.texto, dicionario()), frase.tipo)
                val t0 = SystemClock.elapsedRealtime()
                val pcm = motor().sintetizar(texto, voz, velocidade)
                val ms = SystemClock.elapsedRealtime() - t0
                if (pcm.size > MotorKokoro.TAXA / 2 && ms > 0) registrarDesempenho(pcm.size.toDouble() / MotorKokoro.TAXA / (ms / 1000.0))
                synchronized(trava) {
                    if (versao == versaoAudio && frases.getOrNull(idx) === frase) { cache[idx] = versao to pcm; trava.notifyAll() }
                }
            } catch (e: Throwable) {
                synchronized(trava) { tocando = false; trava.notifyAll() }
                eventos.erro("Erro ao gerar a voz: ${e.message ?: e.javaClass.simpleName}")
                synchronized(trava) { if (!encerrado) trava.wait(1000) }
            }
        }
    }

    private fun registrarDesempenho(v: Double) {
        medias.addLast(v); if (medias.size > 8) medias.removeFirst()
        eventos.desempenho(medias.average())
    }

    // ------------------------------------------------------------------ reprodução

    private fun novaTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(MotorKokoro.TAXA, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(MotorKokoro.TAXA).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, MotorKokoro.TAXA))   // ~0,5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loopReproducao() {
        var minhaGeracao = -1
        val silencio = ShortArray(2400)
        var avisouEspera = false
        while (!encerrado) {
            // espera ter o que tocar
            val (idx, pcm, ger) = synchronized(trava) {
                while (!encerrado && (!tocando || proximaEscrever >= frases.size || cache[proximaEscrever]?.first != versaoAudio)) {
                    // aguardando a síntese: avisa a interface uma vez ("gerando voz…")
                    if (tocando && proximaEscrever < frases.size && proximaEscrever == atual && !avisouEspera) {
                        avisouEspera = true; eventos.estado(true, true)
                    }
                    trava.wait(250)
                }
                if (encerrado) return
                Triple(proximaEscrever, cache[proximaEscrever]!!.second, geracao)
            }
            if (avisouEspera) { avisouEspera = false; eventos.estado(true, false) }
            if (ger != minhaGeracao) {   // salto, troca de voz ou início: começa limpo
                track?.release()
                track = novaTrack().also { it.play() }
                synchronized(marcas) { marcas.clear() }
                quadrosEscritos = 0
                minhaGeracao = ger
                eventos.estado(true, false)
            }
            val t = track ?: continue
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING && tocando) t.play()
            synchronized(marcas) { marcas.add(quadrosEscritos to idx) }
            if (!escrever(t, pcm, ger)) continue
            val f = frases.getOrNull(idx) ?: continue
            val pausa = (f.pausa * fatorPausas / velocidade * MotorKokoro.TAXA).toInt()
            var resta = pausa
            while (resta > 0 && geracao == ger) {
                val n = minOf(resta, silencio.size)
                if (!escrever(t, silencio, ger, n)) break
                resta -= n
            }
            synchronized(trava) {
                if (geracao == ger) {
                    proximaEscrever = idx + 1
                    cache.keys.filter { it < atual - 1 }.forEach { cache.remove(it) }
                    trava.notifyAll()
                    if (proximaEscrever >= frases.size) terminarQuandoEsvaziar(t, ger)
                }
            }
        }
    }

    /** Escreve em pedaços pequenos, para que pausa/salto respondam rápido. */
    private fun escrever(t: AudioTrack, dados: ShortArray, ger: Int, tamanho: Int = dados.size): Boolean {
        var pos = 0
        while (pos < tamanho) {
            if (geracao != ger || encerrado) return false
            val n = t.write(dados, pos, minOf(4096, tamanho - pos))
            if (n < 0) return false
            if (n == 0) { Thread.sleep(20); continue }
            pos += n; quadrosEscritos += n
        }
        return true
    }

    private fun terminarQuandoEsvaziar(t: AudioTrack, ger: Int) {
        Thread {
            val alvo = quadrosEscritos
            while (geracao == ger && !encerrado && t.playbackHeadPosition.toLong() and 0xFFFFFFFFL < alvo - 100) Thread.sleep(100)
            synchronized(trava) {
                if (geracao == ger && !encerrado) { tocando = false; atual = frases.size - 1; eventos.fim() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Segue a posição real de reprodução para destacar a frase que está sendo ouvida. */
    private fun loopPosicao() {
        var ultima = -1
        while (!encerrado) {
            Thread.sleep(120)
            val t = track ?: continue
            if (!tocando) continue
            val cabeca = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (e: IllegalStateException) { continue }
            val idx = synchronized(marcas) { marcas.lastOrNull { it.first <= cabeca }?.second } ?: continue
            if (idx != ultima || idx != atual) {
                ultima = idx
                if (idx != atual) atual = idx
                eventos.frase(idx)
            }
        }
    }

    companion object { const val PRE_CARREGAR = 4 }
}
