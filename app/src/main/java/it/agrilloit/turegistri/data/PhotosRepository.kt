package it.agrilloit.turegistri.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Foto scattate da YouRec: salvate in Pictures/YouRec via MediaStore,
 * con query della galleria e miniature.
 */
class PhotosRepository(private val context: Context) {

    /** Salva la bitmap in Pictures/YouRec; ritorna l'uri o null. */
    fun save(bitmap: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "YouRec_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YouRec")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        }.onFailure { return null }
        return uri
    }

    /** Salva byte JPEG già codificati in Pictures/YouRec. */
    fun saveBytes(bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "YouRec_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/YouRec")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        }.onFailure { return null }
        return uri
    }

    /** L'ultima foto scattata (o null). */
    fun latest(): Uri? = list(1).firstOrNull()

    /** Elenco delle foto YouRec, dalla più recente. */
    fun list(limit: Int = 200): List<Uri> = runCatching {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Pictures/YouRec%")
        val result = ArrayList<Uri>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && result.size < limit) {
                result.add(Uri.withAppendedPath(collection, cursor.getLong(idCol).toString()))
            }
        }
        result
    }.getOrDefault(emptyList())

    /** Bitmap ridimensionata per una miniatura. */
    fun thumbnail(uri: Uri, sizePx: Int = 128): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > sizePx * 2 || opts.outHeight / sample > sizePx * 2) {
                sample *= 2
            }
            val opts2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    /** Cancella la vecchia cartella sessioni se esiste ancora (pulizia storica). */
    fun cleanupLegacy() {
        runCatching {
            java.io.File("/storage/emulated/0/Android/data/it.agrilloit.yourec/files/foto").deleteRecursively()
        }
    }

    companion object {
        /** Nome cartella galleria. */
        const val FOLDER = "Pictures/YouRec"
    }
}
