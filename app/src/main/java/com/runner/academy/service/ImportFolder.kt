package com.runner.academy.service

/** What a folder import reads of a folder's files. */
internal object ImportFolder {

    private val MEDIA = listOf("image/", "video/", "audio/")

    /** A photo, video or sound by its MIME type: no workout, not opened. */
    fun isMedia(mimeType: String?): Boolean = mimeType != null && MEDIA.any { mimeType.startsWith(it) }
}
