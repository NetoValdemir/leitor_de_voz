package br.leitordevoz.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.leitordevoz.texto.Frases
import br.leitordevoz.voz.MotorKokoro
import java.io.File

/**
 * Serviço de leitura: mantém o player vivo com a tela apagada, mostra a notificação
 * com controles (também na tela de bloqueio) e respeita o foco de áudio (pausa em
 * ligações, quando outro app toca, ou ao desconectar o fone).
 */
class LeitorService : Service() {

    inner class Ligacao : Binder() { val servico: LeitorService get() = this@LeitorService }

    private val app get() = application as App
    lateinit var leitor: Leitor
        private set
    @Volatile var ouvinte: Leitor.Eventos? = null

    private lateinit var sessao: MediaSession
    private var motor: MotorKokoro? = null
    private var motorArquivo: File? = null
    private var titulo = "Leitor de Voz"
    private var frases: List<String> = emptyList()
    private var emPrimeiroPlano = false
    private var focoPedido: AudioFocusRequest? = null
    private val wakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LeitorDeVoz:leitura")
    }

    private val foneDesconectado = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (leitor.tocando) leitor.pausar() }
    }
    private var receptorRegistrado = false

    override fun onCreate() {
        super.onCreate()
        sessao = MediaSession(this, "LeitorDeVoz").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = continuar()
                override fun onPause() = leitor.pausar()
                override fun onSkipToNext() = leitor.irPara(leitor.atual + 1)
                override fun onSkipToPrevious() = leitor.irPara(leitor.atual - 1)
                override fun onStop() = leitor.pausar()
            })
            setSessionActivity(PendingIntent.getActivity(this@LeitorService, 0, Intent(this@LeitorService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
        leitor = Leitor(::obterMotor, { app.pronuncias.palavras }, object : Leitor.Eventos {
            override fun frase(idx: Int) { ouvinte?.frase(idx); atualizarSessao() }
            override fun estado(tocando: Boolean, carregando: Boolean) { ouvinte?.estado(tocando, carregando); aoMudarEstado(tocando) }
            override fun fim() { ouvinte?.fim(); aoMudarEstado(false) }
            override fun erro(mensagem: String) { ouvinte?.erro(mensagem); aoMudarEstado(false) }
            override fun desempenho(vezesTempoReal: Double) { ouvinte?.desempenho(vezesTempoReal) }
        })
    }

    override fun onBind(intent: Intent): IBinder = Ligacao()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACAO_TOCAR_PAUSAR -> if (leitor.tocando) leitor.pausar() else continuar()
            ACAO_PROXIMA -> leitor.irPara(leitor.atual + 1)
            ACAO_ANTERIOR -> leitor.irPara(leitor.atual - 1)
            ACAO_FECHAR -> { leitor.pausar(); pararPrimeiroPlano(remover = true) }
        }
        return START_NOT_STICKY
    }

    private fun continuar() { if (leitor.total() > 0) leitor.retomar() }

    // ------------------------------------------------------------------ documento e motor

    fun abrir(estrutura: Frases.Estrutura) {
        titulo = estrutura.titulo
        frases = estrutura.blocos.flatMap { it.frases }
        leitor.carregar(estrutura)
        atualizarSessao()
    }

    fun fecharDocumento() {
        leitor.liberar()
        frases = emptyList()
        pararPrimeiroPlano(remover = true)
    }

    @Synchronized
    fun obterMotor(): MotorKokoro {
        val arquivo = app.modelo.arquivo()
        val atual = motor
        if (atual != null && motorArquivo == arquivo) return atual
        atual?.close()
        if (!app.modelo.pronto()) throw IllegalStateException("O modelo de voz ainda não foi baixado.")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return MotorKokoro(arquivo, app.recursos.estilos, app.recursos.fonemizador, threads).also { motor = it; motorArquivo = arquivo }
    }

    /** Carrega a voz em segundo plano (para a primeira frase sair rápido). */
    fun preaquecer() { Thread { try { obterMotor() } catch (e: Throwable) { } }.start() }

    @Synchronized
    fun descarregarMotor() { motor?.close(); motor = null; motorArquivo = null }

    // ------------------------------------------------------------------ primeiro plano, foco, sessão

    private fun aoMudarEstado(tocando: Boolean) {
        if (tocando) {
            pedirFoco()
            if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)
            if (!receptorRegistrado) {
                val filtro = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(foneDesconectado, filtro, Context.RECEIVER_NOT_EXPORTED)
                else registerReceiver(foneDesconectado, filtro)
                receptorRegistrado = true
            }
            if (!emPrimeiroPlano) {
                startForegroundService(Intent(this, LeitorService::class.java))
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICACAO, notificacao(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIFICACAO, notificacao(true))
                emPrimeiroPlano = true
            }
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (emPrimeiroPlano) pararPrimeiroPlano(remover = false)
        }
        atualizarSessao()
    }

    private fun pararPrimeiroPlano(remover: Boolean) {
        stopForeground(if (remover) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        emPrimeiroPlano = false
        if (remover) getSystemService(android.app.NotificationManager::class.java)!!.cancel(NOTIFICACAO)
        else getSystemService(android.app.NotificationManager::class.java)!!.notify(NOTIFICACAO, notificacao(false))
    }

    private fun pedirFoco() {
        val am = getSystemService(AudioManager::class.java)!!
        val pedido = focoPedido ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { mudanca ->
                if (mudanca == AudioManager.AUDIOFOCUS_LOSS || mudanca == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) leitor.pausar()
            }.build().also { focoPedido = it }
        am.requestAudioFocus(pedido)
    }

    private fun atualizarSessao() {
        val tocando = leitor.tocando
        sessao.isActive = frases.isNotEmpty()
        sessao.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (tocando) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, leitor.atual.toLong(), 1f)
                .build()
        )
        sessao.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, frases.getOrNull(leitor.atual)?.take(120) ?: "")
                .build()
        )
        if (emPrimeiroPlano || frases.isNotEmpty()) {
            try { getSystemService(android.app.NotificationManager::class.java)!!.notify(NOTIFICACAO, notificacao(tocando)) } catch (e: SecurityException) { }
        }
    }

    private fun acao(acao: String, codigo: Int) =
        PendingIntent.getService(this, codigo, Intent(this, LeitorService::class.java).setAction(acao), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun icone(id: Int) = android.graphics.drawable.Icon.createWithResource(this, id)

    private fun notificacao(tocando: Boolean): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, App.CANAL)
            .setSmallIcon(R.drawable.ic_alto_falante)
            .setContentTitle(titulo)
            .setContentText(frases.getOrNull(leitor.atual) ?: "")
            .setContentIntent(abrir)
            .setOngoing(tocando)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(acao(ACAO_FECHAR, 4))
            .addAction(Notification.Action.Builder(icone(android.R.drawable.ic_media_previous), "Anterior", acao(ACAO_ANTERIOR, 1)).build())
            .addAction(Notification.Action.Builder(icone(if (tocando) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (tocando) "Pausar" else "Tocar", acao(ACAO_TOCAR_PAUSAR, 2)).build())
            .addAction(Notification.Action.Builder(icone(android.R.drawable.ic_media_next), "Próxima", acao(ACAO_PROXIMA, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(sessao.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!leitor.tocando) stopSelf()
    }

    override fun onDestroy() {
        leitor.encerrar()
        if (receptorRegistrado) unregisterReceiver(foneDesconectado)
        if (wakeLock.isHeld) wakeLock.release()
        focoPedido?.let { getSystemService(AudioManager::class.java)!!.abandonAudioFocusRequest(it) }
        sessao.release()
        descarregarMotor()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICACAO = 1
        const val ACAO_TOCAR_PAUSAR = "br.leitordevoz.TOCAR_PAUSAR"
        const val ACAO_PROXIMA = "br.leitordevoz.PROXIMA"
        const val ACAO_ANTERIOR = "br.leitordevoz.ANTERIOR"
        const val ACAO_FECHAR = "br.leitordevoz.FECHAR"
    }
}
