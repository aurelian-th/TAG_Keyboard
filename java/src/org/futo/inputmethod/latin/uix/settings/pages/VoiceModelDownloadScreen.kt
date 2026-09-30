package org.futo.inputmethod.latin.uix.settings.pages

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.RichInputMethodManager
import org.futo.inputmethod.latin.uix.ACTIVE_SLM_MODEL_FILE
import org.futo.inputmethod.latin.uix.ACTIVE_VOICE_MODEL_FILE
import org.futo.inputmethod.latin.uix.FileKind
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.preferenceKeyFor
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.ScrollableList
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.xlm.ModelPaths
import org.futo.inputmethod.updates.openURI
import java.io.File

data class DownloadableModelItem(
    val titleRes: Int,
    val subtitleRes: Int,
    val fileName: String,
    val downloadUrl: String,
    val minCompleteSizeBytes: Long,
    val isSlm: Boolean = false
)

data class ModelDownloadProgress(
    val downloadId: Long,
    val status: Int,
    val progressPercent: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

object ModelDownloadTracker {
    private const val PREFS_NAME = "model_download_tracker_prefs"

    fun recordDownload(context: Context, downloadId: Long, fileName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(downloadId.toString(), fileName)
            .apply()
    }

    fun removeDownload(context: Context, downloadId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(downloadId.toString())
            .apply()
    }

    fun getAllTrackedDownloads(context: Context): Map<Long, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = mutableMapOf<Long, String>()
        for ((k, v) in prefs.all) {
            val id = k.toLongOrNull()
            if (id != null && v is String) {
                result[id] = v
            }
        }
        return result
    }

    fun isActivelyDownloading(context: Context, fileName: String): Boolean {
        val all = getAllTrackedDownloads(context)
        if (!all.values.contains(fileName)) return false
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        for ((id, name) in all) {
            if (name == fileName) {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = try { dm.query(query) } catch (_: Throwable) { null }
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    cursor.close()
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                        return true
                    }
                } else {
                    cursor?.close()
                }
            }
        }
        return false
    }
}

private val WHISPER_MODELS = listOf(
    DownloadableModelItem(
        titleRes = R.string.voice_model_base_title,
        subtitleRes = R.string.voice_model_base_subtitle,
        fileName = "voiceinput_ggml_base.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        minCompleteSizeBytes = 135 * 1024 * 1024L
    ),
    DownloadableModelItem(
        titleRes = R.string.voice_model_tiny_title,
        subtitleRes = R.string.voice_model_tiny_subtitle,
        fileName = "voiceinput_ggml_tiny.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        minCompleteSizeBytes = 70 * 1024 * 1024L
    ),
    DownloadableModelItem(
        titleRes = R.string.voice_model_small_title,
        subtitleRes = R.string.voice_model_small_subtitle,
        fileName = "voiceinput_ggml_small.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        minCompleteSizeBytes = 450 * 1024 * 1024L
    )
)

private val QWEN_MODELS = listOf(
    DownloadableModelItem(
        titleRes = R.string.rambler_qwen_0_5b_title,
        subtitleRes = R.string.rambler_qwen_0_5b_subtitle,
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        minCompleteSizeBytes = 320 * 1024 * 1024L,
        isSlm = true
    ),
    DownloadableModelItem(
        titleRes = R.string.rambler_qwen_1_5b_title,
        subtitleRes = R.string.rambler_qwen_1_5b_subtitle,
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        minCompleteSizeBytes = 900 * 1024 * 1024L,
        isSlm = true
    )
)

private fun triggerDownload(context: Context, item: DownloadableModelItem): Long? {
    return try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Clean up any stale incomplete download file in destination
        val targetDir = if (item.isSlm) {
            context.getExternalFilesDir("models") ?: context.filesDir
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
        val targetFile = File(targetDir, item.fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(item.downloadUrl))
            .setTitle(context.getString(item.titleRes))
            .setDescription(context.getString(item.subtitleRes))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        if (item.isSlm) {
            request.setDestinationInExternalFilesDir(context, "models", item.fileName)
        } else {
            request.setDestinationInExternalFilesDir(context, null, item.fileName)
        }

        val id = dm.enqueue(request)
        ModelDownloadTracker.recordDownload(context, id, item.fileName)
        Toast.makeText(
            context,
            context.getString(R.string.model_download_started, context.getString(item.titleRes)),
            Toast.LENGTH_SHORT
        ).show()
        id
    } catch (e: Exception) {
        android.util.Log.e("VoiceModelDownload", "DownloadManager failed, opening browser", e)
        context.openURI(item.downloadUrl, newTask = true)
        null
    }
}

/**
 * Checks if the model was downloaded via browser to public Downloads directory
 * and copies it into the app's internal or external model storage.
 */
private fun checkAndImportFromPublicDownloads(context: Context, item: DownloadableModelItem): Boolean {
    return try {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidate = File(publicDownloads, item.fileName)
        if (candidate.exists() && candidate.length() >= item.minCompleteSizeBytes) {
            val targetDir = if (item.isSlm) {
                ModelPaths.getModelDirectory(context)
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
            val targetFile = File(targetDir, item.fileName)
            if (!targetFile.exists() || targetFile.length() != candidate.length()) {
                candidate.copyTo(targetFile, overwrite = true)
            }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        android.util.Log.e("VoiceModelDownload", "checkAndImportFromPublicDownloads error", e)
        false
    }
}

@Composable
fun ModelDownloadCard(
    item: DownloadableModelItem,
    isInstalled: Boolean,
    isActive: Boolean,
    downloadProgress: ModelDownloadProgress?,
    onDownload: () -> Unit,
    onActivate: () -> Unit
) {
    Surface(
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(item.subtitleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.model_download_active),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Download progress indicator
            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (downloadProgress.progressPercent >= 0) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (downloadProgress.progressPercent >= 0) {
                                "Downloading: ${downloadProgress.progressPercent}%"
                            } else {
                                stringResource(R.string.model_download_downloading)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        if (downloadProgress.totalBytes > 0) {
                            val dlMb = downloadProgress.bytesDownloaded / (1024 * 1024)
                            val totalMb = downloadProgress.totalBytes / (1024 * 1024)
                            Text(
                                text = "$dlMb MB / $totalMb MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (downloadProgress != null) {
                    Button(
                        onClick = { },
                        enabled = false,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(stringResource(R.string.model_download_downloading))
                    }
                } else if (isInstalled) {
                    if (!isActive) {
                        Button(
                            onClick = onActivate,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.check),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(end = 4.dp)
                            )
                            Text(stringResource(R.string.model_download_activate))
                        }
                    }
                    OutlinedButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Re-download")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.arrow_down),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(stringResource(R.string.model_download_button))
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceModelDownloadScreen(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val refreshTrigger = remember { mutableStateOf(0) }

    val activeDownloads = remember { mutableStateMapOf<String, ModelDownloadProgress>() }

    // Reconnect to any background downloads tracked persistently across screen exits
    LaunchedEffect(Unit) {
        val tracked = ModelDownloadTracker.getAllTrackedDownloads(context)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null && tracked.isNotEmpty()) {
            for ((id, fileName) in tracked) {
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = try { dm.query(query) } catch (_: Throwable) { null }
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val pct = if (total > 0) ((bytes * 100) / total).toInt() else -1
                    cursor.close()
                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                        activeDownloads[fileName] = ModelDownloadProgress(id, status, pct, bytes, total)
                    } else {
                        ModelDownloadTracker.removeDownload(context, id)
                    }
                } else {
                    cursor?.close()
                    ModelDownloadTracker.removeDownload(context, id)
                }
            }
        }
    }

    // Helper functions to check installed status with strict size validation
    val isWhisperInstalled = { item: DownloadableModelItem ->
        if (activeDownloads.containsKey(item.fileName) || ModelDownloadTracker.isActivelyDownloading(context, item.fileName)) {
            false
        } else {
            val dir = context.getExternalFilesDir(null)
            val extModelsDir = context.getExternalFilesDir("models")
            val extFile = File(dir, item.fileName)
            val extModelsFile = File(extModelsDir, item.fileName)
            val intFile = File(context.filesDir, item.fileName)
            val publicImported = checkAndImportFromPublicDownloads(context, item)

            val existsAndComplete = (extFile.exists() && extFile.length() >= item.minCompleteSizeBytes) ||
                    (extModelsFile.exists() && extModelsFile.length() >= item.minCompleteSizeBytes) ||
                    (intFile.exists() && intFile.length() >= item.minCompleteSizeBytes) ||
                    publicImported

            // Cleanup corrupted or partial download if no download is running
            if (!existsAndComplete) {
                if (extFile.exists() && extFile.length() < item.minCompleteSizeBytes) extFile.delete()
                if (extModelsFile.exists() && extModelsFile.length() < item.minCompleteSizeBytes) extModelsFile.delete()
                if (intFile.exists() && intFile.length() < item.minCompleteSizeBytes) intFile.delete()
            }
            existsAndComplete
        }
    }

    val isSlmInstalled = { item: DownloadableModelItem ->
        if (activeDownloads.containsKey(item.fileName) || ModelDownloadTracker.isActivelyDownloading(context, item.fileName)) {
            false
        } else {
            val internalDir = ModelPaths.getModelDirectory(context)
            val extModelsDir = context.getExternalFilesDir("models")
            val extDir = context.getExternalFilesDir(null)

            val intFile = File(internalDir, item.fileName)
            val extModelsFile = File(extModelsDir, item.fileName)
            val extFile = File(extDir, item.fileName)
            val publicImported = checkAndImportFromPublicDownloads(context, item)

            val existsAndComplete = (intFile.exists() && intFile.length() >= item.minCompleteSizeBytes) ||
                    (extModelsFile.exists() && extModelsFile.length() >= item.minCompleteSizeBytes) ||
                    (extFile.exists() && extFile.length() >= item.minCompleteSizeBytes) ||
                    publicImported

            if (!existsAndComplete) {
                if (intFile.exists() && intFile.length() < item.minCompleteSizeBytes) intFile.delete()
                if (extModelsFile.exists() && extModelsFile.length() < item.minCompleteSizeBytes) extModelsFile.delete()
                if (extFile.exists() && extFile.length() < item.minCompleteSizeBytes) extFile.delete()
            }
            existsAndComplete
        }
    }

    // Active models settings
    val activeVoiceModel = remember(refreshTrigger.value) {
        context.getSetting(ACTIVE_VOICE_MODEL_FILE)
    }
    val activeSlmModel = remember(refreshTrigger.value) {
        context.getSetting(ACTIVE_SLM_MODEL_FILE)
    }

    // Background polling loop for active DownloadManager downloads
    LaunchedEffect(activeDownloads.size) {
        while (activeDownloads.isNotEmpty()) {
            delay(400)
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: break
            val toRemove = mutableListOf<String>()

            for ((fileName, progress) in activeDownloads.toMap()) {
                val query = DownloadManager.Query().setFilterById(progress.downloadId)
                val cursor = try {
                    dm.query(query)
                } catch (t: Throwable) {
                    null
                }

                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val pct = if (total > 0) ((bytes * 100) / total).toInt() else -1

                    activeDownloads[fileName] = progress.copy(
                        status = status,
                        progressPercent = pct,
                        bytesDownloaded = bytes,
                        totalBytes = total
                    )

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        toRemove.add(fileName)
                        ModelDownloadTracker.removeDownload(context, progress.downloadId)

                        val isSlm = QWEN_MODELS.any { it.fileName == fileName }
                        if (isSlm) {
                            withContext(Dispatchers.IO) {
                                val src = File(context.getExternalFilesDir("models"), fileName)
                                val dst = File(ModelPaths.getModelDirectory(context), fileName)
                                if (src.exists()) {
                                    try {
                                        src.copyTo(dst, overwrite = true)
                                    } catch (e: Exception) {
                                        android.util.Log.e("VoiceModelDownload", "Copy SLM error", e)
                                    }
                                }
                            }
                            context.setSetting(ACTIVE_SLM_MODEL_FILE.key, fileName)
                            context.setSetting(org.futo.inputmethod.latin.uix.RAMBLER_AI_MODE.key, true)
                        } else {
                            if (activeVoiceModel.isEmpty() || activeVoiceModel == "builtin") {
                                context.setSetting(ACTIVE_VOICE_MODEL_FILE.key, fileName)
                                val currentSubtypeLocales = RichInputMethodManager.getInstance().currentSubtypeLocales
                                currentSubtypeLocales.forEach { loc ->
                                    context.setSetting(FileKind.VoiceInput.preferenceKeyFor(loc.language), fileName)
                                }
                            }
                        }
                        refreshTrigger.value++
                        Toast.makeText(context, context.getString(R.string.model_download_installed) + ": $fileName", Toast.LENGTH_SHORT).show()
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        toRemove.add(fileName)
                        ModelDownloadTracker.removeDownload(context, progress.downloadId)
                        Toast.makeText(context, "Download failed for $fileName", Toast.LENGTH_SHORT).show()
                        refreshTrigger.value++
                    }
                    cursor.close()
                } else {
                    cursor?.close()
                }
            }

            toRemove.forEach { activeDownloads.remove(it) }
        }
    }

    ScrollableList {
        ScreenTitle(
            stringResource(R.string.voice_model_download_screen_title),
            showBack = true,
            navController = navController
        )

        // 1. Whisper ASR Section
        ScreenTitle(stringResource(R.string.voice_model_whisper_header))
        Tip(stringResource(R.string.voice_model_whisper_tip))

        // Built-in model status card
        val isBuiltInActive = activeVoiceModel.isEmpty() || activeVoiceModel == "builtin"
        Surface(
            color = if (isBuiltInActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.voice_model_builtin_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.voice_model_builtin_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isBuiltInActive) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.model_download_active),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                context.setSetting(ACTIVE_VOICE_MODEL_FILE.key, "builtin")
                                val currentSubtypeLocales = RichInputMethodManager.getInstance().currentSubtypeLocales
                                currentSubtypeLocales.forEach { loc ->
                                    context.setSetting(FileKind.VoiceInput.preferenceKeyFor(loc.language), "")
                                }
                                refreshTrigger.value++
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, context.getString(R.string.model_download_activated, "Built-in English"), Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.model_download_activate))
                    }
                }
            }
        }

        // Multilingual Whisper Models
        WHISPER_MODELS.forEach { model ->
            val installed = isWhisperInstalled(model)
            val active = activeVoiceModel == model.fileName

            ModelDownloadCard(
                item = model,
                isInstalled = installed,
                isActive = active,
                downloadProgress = activeDownloads[model.fileName],
                onDownload = {
                    val id = triggerDownload(context, model)
                    if (id != null) {
                        activeDownloads[model.fileName] = ModelDownloadProgress(
                            downloadId = id,
                            status = DownloadManager.STATUS_PENDING,
                            progressPercent = -1,
                            bytesDownloaded = 0,
                            totalBytes = 0
                        )
                    }
                    refreshTrigger.value++
                },
                onActivate = {
                    coroutineScope.launch {
                        context.setSetting(ACTIVE_VOICE_MODEL_FILE.key, model.fileName)
                        val currentSubtypeLocales = RichInputMethodManager.getInstance().currentSubtypeLocales
                        currentSubtypeLocales.forEach { loc ->
                            context.setSetting(FileKind.VoiceInput.preferenceKeyFor(loc.language), model.fileName)
                        }
                        refreshTrigger.value++
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.model_download_activated, context.getString(model.titleRes)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }

        NavigationItem(
            title = stringResource(R.string.voice_model_explore_online),
            subtitle = stringResource(R.string.voice_model_explore_online_subtitle),
            style = NavigationItemStyle.Misc,
            icon = painterResource(R.drawable.globe),
            navigate = {
                context.openURI("https://keyboard.futo.tech/voice-input-models", true)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Rambler AI SLM Section
        ScreenTitle(stringResource(R.string.rambler_slm_header))
        Tip(stringResource(R.string.rambler_slm_tip))

        QWEN_MODELS.forEach { model ->
            val installed = isSlmInstalled(model)
            val active = activeSlmModel == model.fileName

            ModelDownloadCard(
                item = model,
                isInstalled = installed,
                isActive = active,
                downloadProgress = activeDownloads[model.fileName],
                onDownload = {
                    val id = triggerDownload(context, model)
                    if (id != null) {
                        activeDownloads[model.fileName] = ModelDownloadProgress(
                            downloadId = id,
                            status = DownloadManager.STATUS_PENDING,
                            progressPercent = -1,
                            bytesDownloaded = 0,
                            totalBytes = 0
                        )
                    }
                    refreshTrigger.value++
                },
                onActivate = {
                    coroutineScope.launch {
                        context.setSetting(ACTIVE_SLM_MODEL_FILE.key, model.fileName)
                        refreshTrigger.value++
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.model_download_activated, context.getString(model.titleRes)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }

        NavigationItem(
            title = stringResource(R.string.language_settings_explore_transformers_online),
            style = NavigationItemStyle.Misc,
            icon = painterResource(R.drawable.globe),
            navigate = {
                context.openURI("https://keyboard.futo.tech/models", true)
            }
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}
