package br.leitordevoz.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import br.leitordevoz.extracao.Extrator
import br.leitordevoz.extracao.LeitorPdf
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Objetos que vivem enquanto o app estiver aberto (ou tocando em segundo plano). */
class App : Application() {
    lateinit var recursos: Recursos
    lateinit var pronuncias: Pronuncias
    lateinit var modelo: Modelo
    lateinit var extrator: Extrator

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        recursos = Recursos(this)
        pronuncias = Pronuncias(this)
        modelo = Modelo(this)
        extrator = Extrator(LeitorPdf { bytes, nome -> PdfAndroid.extrair(bytes, nome) })
        val canal = NotificationChannel(CANAL, getString(R.string.canal_leitura), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(canal)
    }

    companion object { const val CANAL = "leitura" }
}
