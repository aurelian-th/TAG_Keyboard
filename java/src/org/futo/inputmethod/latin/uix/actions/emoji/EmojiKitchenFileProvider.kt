package org.futo.inputmethod.latin.uix.actions.emoji

import android.net.Uri
import androidx.core.content.FileProvider

class EmojiKitchenFileProvider : FileProvider() {
    override fun getType(uri: Uri): String {
        val path = uri.path ?: ""
        if (path.endsWith(".wasticker") || path.contains("_wa.webp")) {
            return "image/webp.wasticker"
        }
        val defaultType = super.getType(uri)
        return defaultType ?: "image/webp"
    }
}
