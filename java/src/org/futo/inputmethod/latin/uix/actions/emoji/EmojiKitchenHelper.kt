package org.futo.inputmethod.latin.uix.actions.emoji

import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class EmojiKitchenCombination(
    val leftEmoji: String,
    val rightEmoji: String,
    val imageUrl: String
)

object EmojiKitchenDatabase {
    private const val DB_NAME = "emoji_kitchen.db"
    private const val ASSET_DB_NAME = "emoji_kitchen.db"
    private const val ASSET_GZ_NAME = "emoji_kitchen.db.gz"

    @Volatile
    private var database: SQLiteDatabase? = null
    private val lock = Any()

    private fun getDb(context: Context): SQLiteDatabase {
        database?.let { if (it.isOpen) return it }
        synchronized(lock) {
            database?.let { if (it.isOpen) return it }

            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) {
                dbFile.parentFile?.mkdirs()
                var extracted = false
                // Try 1: Open direct uncompressed db asset
                try {
                    context.assets.open(ASSET_DB_NAME).use { input ->
                        FileOutputStream(dbFile).use { out ->
                            input.copyTo(out)
                        }
                    }
                    extracted = true
                } catch (_: Exception) {}

                // Try 2: Open gzip-compressed db asset if Try 1 failed
                if (!extracted) {
                    try {
                        context.assets.open(ASSET_GZ_NAME).use { inputGz ->
                            GZIPInputStream(inputGz).use { gis ->
                                FileOutputStream(dbFile).use { out ->
                                    val buffer = ByteArray(65536)
                                    var len: Int
                                    while (gis.read(buffer).also { len = it } > 0) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("EmojiKitchen", "Failed to extract emoji kitchen db", e)
                    }
                }
            }

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            database = db
            return db
        }
    }

    suspend fun getExactCombination(context: Context, left: String, right: String): EmojiKitchenCombination? =
        withContext(Dispatchers.IO) {
            try {
                val db = getDb(context)
                val cLeft = left.replace("\uFE0F", "")
                val cRight = right.replace("\uFE0F", "")

                val query = """
                    SELECT left_emoji, right_emoji, image_url FROM emoji_combinations 
                    WHERE (left_emoji IN (?, ?) AND right_emoji IN (?, ?))
                       OR (left_emoji IN (?, ?) AND right_emoji IN (?, ?))
                    LIMIT 1
                """.trimIndent()

                val args = arrayOf(
                    cLeft, "$cLeft\uFE0F", cRight, "$cRight\uFE0F",
                    cRight, "$cRight\uFE0F", cLeft, "$cLeft\uFE0F"
                )

                db.rawQuery(query, args).use { cursor ->
                    if (cursor.moveToNext()) {
                        return@withContext EmojiKitchenCombination(
                            leftEmoji = cursor.getString(0),
                            rightEmoji = cursor.getString(1),
                            imageUrl = cursor.getString(2)
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("EmojiKitchen", "Error querying exact combination for ($left, $right)", e)
            }
            null
        }

    suspend fun getCombinations(context: Context, emoji: String, excludeUrl: String? = null): List<EmojiKitchenCombination> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<EmojiKitchenCombination>()
            val seenUrls = HashSet<String>()
            excludeUrl?.let { seenUrls.add(it) }

            try {
                val db = getDb(context)
                val cleanEmoji = emoji.replace("\uFE0F", "")
                val variants = arrayOf(cleanEmoji, "$cleanEmoji\uFE0F", cleanEmoji, "$cleanEmoji\uFE0F")

                val query = "SELECT left_emoji, right_emoji, image_url FROM emoji_combinations WHERE left_emoji IN (?, ?) OR right_emoji IN (?, ?) LIMIT 80"
                db.rawQuery(query, variants).use { cursor ->
                    while (cursor.moveToNext()) {
                        val url = cursor.getString(2)
                        if (seenUrls.add(url)) {
                            results.add(
                                EmojiKitchenCombination(
                                    leftEmoji = cursor.getString(0),
                                    rightEmoji = cursor.getString(1),
                                    imageUrl = url
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("EmojiKitchen", "Error querying combinations for $emoji", e)
            }
            results
        }
}

object EmojiKitchenStickerManager {
    private val bitmapCache = LruCache<String, Bitmap>(64)

    fun getCachedBitmap(url: String): Bitmap? = bitmapCache.get(url)

    suspend fun loadBitmap(context: Context, combination: EmojiKitchenCombination): Bitmap? {
        bitmapCache.get(combination.imageUrl)?.let { return it }
        val file = getOrDownloadSticker(context, combination) ?: return null
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        if (bmp != null) {
            bitmapCache.put(combination.imageUrl, bmp)
        }
        return bmp
    }

    fun clearCache(context: Context) {
        try {
            bitmapCache.evictAll()
            val stickersDir = File(context.cacheDir, "stickers")
            stickersDir.deleteRecursively()
        } catch (e: Exception) {
            android.util.Log.e("EmojiKitchen", "Failed to clear sticker cache", e)
        }
    }

    suspend fun getOrDownloadSticker(context: Context, combination: EmojiKitchenCombination): File? =
        withContext(Dispatchers.IO) {
            try {
                val stickersDir = File(context.cacheDir, "stickers").apply { mkdirs() }
                val fileName = "ek_" + combination.imageUrl.hashCode().toString() + ".png"
                val file = File(stickersDir, fileName)

                if (file.exists() && file.length() > 0) {
                    return@withContext file
                }

                val url = URL(combination.imageUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@withContext file
                }
            } catch (e: Exception) {
                android.util.Log.e("EmojiKitchen", "Failed to download sticker", e)
            }
            null
        }

    fun getUriForFile(context: Context, imageFile: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, imageFile)
    }

    suspend fun prepareStickerFile(
        context: Context,
        pngFile: File,
        editorInfo: EditorInfo?
    ): Pair<File, List<String>> = withContext(Dispatchers.IO) {
        val packageName = editorInfo?.packageName
        val supportedMimes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            editorInfo?.contentMimeTypes?.toList() ?: emptyList()
        } else {
            emptyList()
        }

        val isWhatsApp = packageName?.let {
            it == "com.whatsapp" || it == "com.whatsapp.w4b" || it.contains("whatsapp")
        } == true || supportedMimes.any { it.contains("wasticker") }

        if (isWhatsApp) {
            try {
                val stickersDir = File(context.cacheDir, "stickers").apply { mkdirs() }
                val webpFile = File(stickersDir, "${pngFile.nameWithoutExtension}_wa.webp")
                if (!webpFile.exists() || webpFile.length() == 0L || webpFile.length() > 98 * 1024L) {
                    val bmp = BitmapFactory.decodeFile(pngFile.absolutePath)
                    if (bmp != null) {
                        val scaled = if (bmp.width != 512 || bmp.height != 512) {
                            Bitmap.createScaledBitmap(bmp, 512, 512, true)
                        } else {
                            bmp
                        }
                        var quality = 85
                        var bytes: ByteArray
                        do {
                            val baos = ByteArrayOutputStream()
                            @Suppress("DEPRECATION")
                            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSY
                            } else {
                                Bitmap.CompressFormat.WEBP
                            }
                            scaled.compress(format, quality, baos)
                            bytes = baos.toByteArray()
                            quality -= 10
                        } while (bytes.size > 98 * 1024 && quality >= 20)

                        FileOutputStream(webpFile).use { out ->
                            out.write(bytes)
                        }
                        if (scaled != bmp) scaled.recycle()
                        bmp.recycle()
                    }
                }
                if (webpFile.exists() && webpFile.length() > 0L) {
                    val mimeList = if (supportedMimes.any { it.contains("wasticker") } || supportedMimes.isEmpty()) {
                        listOf("image/webp.wasticker")
                    } else {
                        listOf("image/webp")
                    }
                    return@withContext Pair(webpFile, mimeList)
                }
            } catch (e: Exception) {
                android.util.Log.e("EmojiKitchen", "Error converting sticker to WebP for WhatsApp", e)
            }
        }

        val mimeList = mutableListOf<String>()
        if (supportedMimes.contains("image/png")) mimeList.add("image/png")
        if (supportedMimes.contains("image/webp")) mimeList.add("image/webp")
        if (mimeList.isEmpty()) {
            mimeList.add("image/png")
            mimeList.add("image/webp")
        }
        Pair(pngFile, mimeList)
    }

    fun sendSticker(
        context: Context,
        imageFile: File,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?
    ): Boolean {
        if (inputConnection == null || editorInfo == null) return false
        try {
            val contentUri: Uri = getUriForFile(context, imageFile)
            val description = ClipDescription("Emoji Kitchen Sticker", arrayOf("image/png", "image/webp"))
            val inputContentInfo = InputContentInfoCompat(contentUri, description, null)
            val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

            return InputConnectionCompat.commitContent(
                inputConnection,
                editorInfo,
                inputContentInfo,
                flags,
                null
            )
        } catch (e: Exception) {
            android.util.Log.e("EmojiKitchen", "Failed to send sticker via commitContent", e)
            return false
        }
    }
}
