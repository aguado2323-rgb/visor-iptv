package com.visor.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.visor.tv.data.Favorites
import com.visor.tv.data.Progress
import com.visor.tv.data.Repo
import com.visor.tv.data.SecureStore
import com.visor.tv.data.Settings
import com.visor.tv.data.Tmdb
import com.visor.tv.player.PlaybackQueue
import com.visor.tv.record.RecordingStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class VisorApp : Application(), ImageLoaderFactory {

    lateinit var http: OkHttpClient
        private set
    lateinit var vault: SecureStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var repo: Repo
        private set
    lateinit var favorites: Favorites
        private set
    lateinit var progress: Progress
        private set
    lateinit var recordings: RecordingStore
        private set
    lateinit var tmdb: Tmdb
        private set
    lateinit var updater: com.visor.tv.data.Updater
        private set
    lateinit var recents: com.visor.tv.data.RecentChannels
        private set
    lateinit var folders: com.visor.tv.data.Folders
        private set
    lateinit var epg: com.visor.tv.data.EpgStore
        private set
    lateinit var cameras: com.visor.tv.data.Cameras
        private set
    /** La app abre una pantalla suya (reproductor): no cuenta como salir para volver a bloquear carpetas. */
    @Volatile var internalNav = false
    val queue = PlaybackQueue()

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                // Identificación genérica y honesta del cliente; nunca se registran URLs.
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
        vault = SecureStore(this)
        settings = Settings(this)
        repo = Repo(this, http, vault)
        favorites = Favorites(this)
        progress = Progress(this)
        recordings = RecordingStore(this)
        tmdb = Tmdb(http)
        updater = com.visor.tv.data.Updater(this, http)
        recents = com.visor.tv.data.RecentChannels(this)
        folders = com.visor.tv.data.Folders(this)
        epg = com.visor.tv.data.EpgStore(this, http)
        cameras = com.visor.tv.data.Cameras(vault)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REC, "Grabaciones", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(http)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()

    companion object {
        const val CHANNEL_REC = "rec"
        const val USER_AGENT = "Visor/0.1 (Android TV)"
        lateinit var instance: VisorApp
            private set
    }
}
