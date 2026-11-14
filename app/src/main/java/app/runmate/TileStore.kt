package app.runmate

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenStreetMap 지도 타일. 기록을 볼 때만 사용하며, 한 번 받은 타일은 디스크에 보관해
 * 같은 곳을 다시 볼 때는 데이터를 쓰지 않는다. 오프라인이면 보관된 타일만 보여준다.
 */
class TileStore(cacheDir: File, private val base: String = "https://tile.openstreetmap.org") {
    private val http = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "tiles"), 60L * 1024 * 1024))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val mem = LruCache<String, ImageBitmap>(64)

    suspend fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = fetch(key, null) ?: fetch(key, CacheControl.Builder().onlyIfCached().maxStale(365, TimeUnit.DAYS).build())
            val bmp = bytes?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } catch (e: Exception) { null } }
            if (bmp != null) mem.put(key, bmp)
            bmp
        }
    }

    private fun fetch(key: String, cc: CacheControl?): ByteArray? = try {
        val req = Request.Builder().url("$base/$key.png").header("User-Agent", "RunMate/1.2 (Android running tracker)")
        if (cc != null) req.cacheControl(cc)
        http.newCall(req.build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    } catch (e: Exception) { null }
}
