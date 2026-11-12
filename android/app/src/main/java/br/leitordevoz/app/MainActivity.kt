package br.leitordevoz.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import br.leitordevoz.texto.Documento
import br.leitordevoz.texto.ErroExtracao
import br.leitordevoz.texto.Frases
import br.leitordevoz.texto.LeitorTxt
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Tela do app: a interface é a mesma página web do app de PC (assets/web), exibida num
 * WebView. A leitura, a voz e a extração de texto rodam em código nativo (Kotlin),
 * acessado pela ponte `Android` no JavaScript.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val app get() = application as App
    private val trabalho = Executors.newSingleThreadExecutor()
    private var servico: LeitorService? = null
    private var paginaPronta = false
    private var pendente: Intent? = null

    private val conexao = object : ServiceConnection {
        override fun onServiceConnected(nome: ComponentName, binder: IBinder) {
            servico = (binder as LeitorService.Ligacao).servico.also { s ->
                s.ouvinte = object : Leitor.Eventos {
                    override fun frase(idx: Int) = enviar(JSONObject().put("tipo", "frase").put("idx", idx))
                    override fun estado(tocando: Boolean, carregando: Boolean) =
                        enviar(JSONObject().put("tipo", "estado").put("tocando", tocando).put("carregando", carregando))
                    override fun fim() = enviar(JSONObject().put("tipo", "fim"))
                    override fun erro(mensagem: String) = enviar(JSONObject().put("tipo", "erro").put("mensagem", mensagem))
                    override fun desempenho(vezesTempoReal: Double) = enviar(JSONObject().put("tipo", "desempenho").put("vezes", vezesTempoReal))
                }
                if (app.modelo.pronto()) s.preaquecer()
            }
            processarPendente()
        }
        override fun onServiceDisconnected(nome: ComponentName) { servico = null }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean {
                    startActivity(Intent(Intent.ACTION_VIEW, r.url)); return true   // links externos: navegador
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Ponte(), "Android")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(web)
        // A partir do Android 11 o app desenha atrás das barras do sistema (como o Android 15
        // exige); a página recebe as margens seguras para não ficar escondida atrás delas.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            web.setOnApplyWindowInsetsListener { v, insets ->
                val d = resources.displayMetrics.density
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                margens = (b.top / d).toInt() to (b.bottom / d).toInt()
                aplicarMargens()
                v.onApplyWindowInsets(insets)
            }
        }
        bindService(Intent(this, LeitorService::class.java), conexao, BIND_AUTO_CREATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (estado == null) pendente = intent
    }

    private var margens = 0 to 0
    private fun aplicarMargens() {
        if (paginaPronta) web.evaluateJavascript("window.app&&app.margens(${margens.first},${margens.second})", null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendente = intent
        processarPendente()
    }

    /** Arquivo ou link recebido por "Compartilhar" / "Abrir com". */
    private fun processarPendente() {
        val i = pendente ?: return
        if (!paginaPronta || servico == null) return
        pendente = null
        when (i.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                val texto = i.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                when {
                    uri != null -> abrirUri(uri)
                    texto != null && Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(texto) -> abrirUrl(texto)
                    texto != null -> {
                        val link = Regex("https?://\\S+").find(texto)?.value
                        if (link != null && texto.length < link.length + 200) abrirUrl(link)
                        else processar("Texto compartilhado") { LeitorTxt.extrair(texto, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Texto compartilhado") }
                    }
                }
            }
            Intent.ACTION_VIEW -> i.data?.let { if (it.scheme == "http" || it.scheme == "https") abrirUrl(it.toString()) else abrirUri(it) }
        }
    }

    private fun abrirUrl(url: String) = processarComFonte(url) {
        val (bytes, tipo, final) = app.extrator.baixar(url)
        app.extrator.extrair(bytes, tipo, final) to final
    }

    private fun abrirUri(uri: Uri) = processarComFonte(nomeDe(uri)) {
        val nome = nomeDe(uri)
        val bytes = contentResolver.openInputStream(uri)?.use { entrada ->
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(65536)
            while (true) {
                val n = entrada.read(b); if (n < 0) break
                out.write(b, 0, n)
                if (out.size() > 60 * 1024 * 1024) throw ErroExtracao("O arquivo é grande demais (limite de 60 MB).")
            }
            out.toByteArray()
        } ?: throw ErroExtracao("Não foi possível abrir o arquivo.")
        app.extrator.extrair(bytes, contentResolver.getType(uri).orEmpty(), nome) to nome
    }

    private fun nomeDe(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"

    private fun processarComFonte(rotulo: String, tarefa: () -> Pair<Documento, String>) {
        enviar(JSONObject().put("tipo", "extraindo").put("rotulo", rotulo))
        trabalho.execute {
            try {
                val (doc, fonte) = tarefa()
                val est = Frases.estruturar(doc)
                if (est.totalFrases == 0) throw ErroExtracao("Nenhum texto encontrado para ler.")
                servico?.abrir(est)
                enviar(JSONObject().put("tipo", "documento").put("dados", estruturaJson(est, fonte)))
            } catch (e: ErroExtracao) {
                enviar(JSONObject().put("tipo", "erro-extracao").put("mensagem", e.message))
            } catch (e: OutOfMemoryError) {
                enviar(JSONObject().put("tipo", "erro-extracao").put("mensagem", "O documento é grande demais para a memória do aparelho."))
            } catch (e: Exception) {
                enviar(JSONObject().put("tipo", "erro-extracao").put("mensagem", "Não foi possível ler o conteúdo (${e.javaClass.simpleName})."))
            }
        }
    }

    private fun processar(rotulo: String, tarefa: () -> Documento) =
        processarComFonte(rotulo) { tarefa() to rotulo }

    private fun estruturaJson(e: Frases.Estrutura, fonte: String) = JSONObject()
        .put("titulo", e.titulo).put("fonte", fonte).put("total_frases", e.totalFrases).put("palavras", e.palavras)
        .put("blocos", JSONArray(e.blocos.map { b ->
            JSONObject().put("tipo", b.tipo).put("nivel", b.nivel).put("frases", JSONArray(b.frases)).put("pausas", JSONArray(b.pausas))
        }))

    private fun enviar(json: JSONObject) {
        runOnUiThread { if (paginaPronta) web.evaluateJavascript("window.app&&app.evento($json)", null) }
    }

    @Deprecated("API do framework")
    override fun onActivityResult(requisicao: Int, resultado: Int, dados: Intent?) {
        super.onActivityResult(requisicao, resultado, dados)
        if (requisicao == ESCOLHER && resultado == RESULT_OK) dados?.data?.let { abrirUri(it) }
    }

    @Deprecated("API do framework")
    override fun onBackPressed() {
        web.evaluateJavascript("window.app?app.voltar():false") { tratou ->
            if (tratou != "true") @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        servico?.ouvinte = null
        unbindService(conexao)
        web.destroy()
        trabalho.shutdownNow()
        super.onDestroy()
    }

    /** Métodos chamados pelo JavaScript da página (rodam fora da thread principal). */
    inner class Ponte {
        @JavascriptInterface fun pronto() = runOnUiThread { paginaPronta = true; aplicarMargens(); processarPendente() }

        @JavascriptInterface fun abrirEndereco(url: String) = runOnUiThread { abrirUrl(url) }

        @JavascriptInterface fun escolherArquivo() = runOnUiThread {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/plain", "text/html",
                ))
            @Suppress("DEPRECATION") startActivityForResult(i, ESCOLHER)
        }

        @JavascriptInterface fun tocar(idx: Int) { servico?.leitor?.tocar(idx) }
        @JavascriptInterface fun pausar() { servico?.leitor?.pausar() }
        @JavascriptInterface fun retomar() { servico?.leitor?.retomar() }
        @JavascriptInterface fun irPara(idx: Int) { servico?.leitor?.irPara(idx) }
        @JavascriptInterface fun fecharDocumento() { servico?.fecharDocumento() }
        @JavascriptInterface fun estaTocando(): Boolean = servico?.leitor?.tocando == true

        @JavascriptInterface fun ajustar(voz: String, velocidade: Double, pausas: Double) {
            servico?.leitor?.ajustar(voz, velocidade.toFloat(), pausas.toFloat())
        }

        @JavascriptInterface fun vozes(): String = JSONArray(Vozes.LISTA.map { (id, nome) -> JSONObject().put("id", id).put("nome", nome) }).toString()

        @JavascriptInterface fun modelo(): String {
            val m = app.modelo
            return JSONObject()
                .put("pronto", m.pronto()).put("escolhido", m.escolhido).put("baixando", m.baixando)
                .put("instalados", JSONArray(m.instalados()))
                .put("opcoes", JSONArray(Modelo.OPCOES.map { JSONObject().put("id", it.id).put("nome", it.nome).put("tamanho", it.tamanho) }))
                .toString()
        }

        @JavascriptInterface fun baixarModelo(id: String) {
            if (app.modelo.baixando) return
            Thread {
                try {
                    app.modelo.baixar(id) { feito, total -> enviar(JSONObject().put("tipo", "download").put("baixado", feito).put("total", total)) }
                    servico?.descarregarMotor()
                    servico?.preaquecer()
                    enviar(JSONObject().put("tipo", "download-fim"))
                } catch (e: Throwable) {
                    enviar(JSONObject().put("tipo", "download-erro").put("mensagem", e.message ?: "Falha no download. Verifique a conexão e tente de novo."))
                }
            }.start()
        }

        @JavascriptInterface fun cancelarDownload() = app.modelo.cancelarDownload()

        @JavascriptInterface fun pronuncia(): String = JSONObject()
            .put("palavras", JSONObject(app.pronuncias.palavras))
            .put("arquivo", app.pronuncias.caminho())
            .toString()

        @JavascriptInterface fun salvarPronuncia(json: String): String = try {
            val j = JSONObject(json).getJSONObject("palavras")
            app.pronuncias.salvar(j.keys().asSequence().associateWith { j.getString(it) })
            servico?.leitor?.ajustar(dicionarioMudou = true)
            JSONObject().put("ok", true).put("palavras", JSONObject(app.pronuncias.palavras)).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("mensagem", e.message ?: "erro").toString()
        }

        /** Fala um trecho com um dicionário de teste (sem salvar), interrompendo a leitura. */
        @JavascriptInterface fun testarPronuncia(texto: String, palavrasJson: String, voz: String, velocidade: Double) {
            val s = servico ?: return
            s.leitor.pausar()
            trabalho.execute {
                try {
                    val j = JSONObject(palavrasJson)
                    val dic = j.keys().asSequence().associateWith { j.getString(it) }
                    val preparado = br.leitordevoz.voz.PreparoFala.preparar(br.leitordevoz.voz.PreparoFala.aplicarDicionario(texto, dic), "p")
                    val pcm = s.obterMotor().sintetizar(preparado, voz, velocidade.toFloat())
                    Teste.tocar(pcm) { enviar(JSONObject().put("tipo", "teste-fim")) }
                } catch (e: Throwable) {
                    enviar(JSONObject().put("tipo", "teste-erro").put("mensagem", e.message ?: "erro"))
                }
            }
        }

        @JavascriptInterface fun pararTeste() = Teste.parar()

        @JavascriptInterface fun versao(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }

    companion object { const val ESCOLHER = 7 }
}

/** Toca um áudio curto de teste (painel de pronúncia). */
object Teste {
    private var atual: android.media.AudioTrack? = null

    @Synchronized fun tocar(pcm: ShortArray, aoTerminar: () -> Unit) {
        parar()
        if (pcm.isEmpty()) { aoTerminar(); return }
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(br.leitordevoz.voz.MotorKokoro.TAXA)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.notificationMarkerPosition = pcm.size - 1
        t.setPlaybackPositionUpdateListener(object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: android.media.AudioTrack) { aoTerminar(); parar() }
            override fun onPeriodicNotification(track: android.media.AudioTrack) {}
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        t.play()
        atual = t
    }

    @Synchronized fun parar() { atual?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; atual = null }
}
