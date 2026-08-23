package cloud.wumboing.rpchat.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper untuk avatar GIF: deteksi file GIF, simpan langsung tanpa lewat crop
 * (supaya animasinya tidak hilang), dan unduh dari URL (misalnya link Giphy).
 */
object AvatarPickHelper {

    fun isGifUri(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri)
        if (type == "image/gif") return true
        val path = uri.toString()
        return path.endsWith(".gif", ignoreCase = true)
    }

    fun isGifPath(path: String?): Boolean = path?.endsWith(".gif", ignoreCase = true) == true

    /** Simpan GIF hasil pilih galeri langsung ke file tujuan, tanpa crop (animasi tetap utuh). */
    fun saveGifDirectly(resolver: ContentResolver, uri: Uri, destFile: File): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Unduh GIF dari URL (misal link Giphy) di background thread, hasil dikirim lewat callback di main thread. */
    fun downloadGifFromUrl(urlString: String, destFile: File, onResult: (Boolean) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val success = try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.connect()
                connection.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.exists() && destFile.length() > 0
            } catch (e: Exception) {
                false
            }
            mainHandler.post { onResult(success) }
        }.start()
    }
}
