package com.runner.academy.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared FileProvider write + share-sheet helpers used by workout/plan/stats export UIs.
 */
object ShareExports {

    fun writeCacheText(context: Context, fileName: String, content: String): File {
        val file = File(context.cacheDir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun writeCacheTemp(context: Context, prefix: String, suffix: String, content: String): File {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun uriForFile(context: Context, file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
    }

    fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: CharSequence,
        readyMessage: CharSequence? = null
    ) {
        val uri = uriForFile(context, file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(context, shareIntent, chooserTitle)
        if (readyMessage != null) {
            Toast.makeText(context, readyMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun sharePlainText(context: Context, text: String, chooserTitle: CharSequence) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startChooser(context, shareIntent, chooserTitle)
    }

    private fun startChooser(context: Context, shareIntent: Intent, chooserTitle: CharSequence) {
        val chooser = Intent.createChooser(shareIntent, chooserTitle)
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
