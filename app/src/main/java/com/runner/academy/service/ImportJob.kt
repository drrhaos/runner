package com.runner.academy.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.google.gson.Gson
import com.runner.academy.data.ImportTotals
import java.io.File
import java.util.UUID

/** What an import reads. Every field has a default, so it is read back from JSON. */
data class ImportRequest(val kind: Kind = Kind.FILES, val uris: List<String> = emptyList()) {
    enum class Kind {
        /** Files picked one by one: tracks, archives of them, backups — told by content. */
        FILES,

        /** A folder tree: every file but photos, videos and sound, recursively. */
        FOLDER,

        /** One JSON backup, or an archive holding one. */
        BACKUP
    }
}

/**
 * An import's job: its request and how far it got, saved after each batch. A list of picked
 * files outgrows WorkManager's input data (10 KB), so the worker gets the job's file; and when
 * WorkManager runs it again after the process was killed, it continues from [totals].
 */
data class ImportJob(val request: ImportRequest = ImportRequest(), val totals: ImportTotals = ImportTotals())

/** The job files, and the lasting read access to the documents of each job. */
object ImportJobs {

    private const val DIR = "import_jobs"
    private const val TAG = "ImportJobs"

    /**
     * Picked documents to keep lasting access to, at most: the system keeps a limited number
     * per app (128 to 512) and drops the oldest past it. Past this the files stay readable
     * while the process lives; after a restart they count as inaccessible.
     */
    private const val MAX_KEPT = 100

    private val gson = Gson()

    /** Writes the job of [request], keeping access to its documents; returns its file. */
    fun create(context: Context, request: ImportRequest): File {
        if (request.uris.size <= MAX_KEPT) request.uris.forEach { keepAccess(context, it) }
        return File(dir(context), "${UUID.randomUUID()}.json").also { save(it, ImportJob(request)) }
    }

    fun read(file: File): ImportJob? = try {
        gson.fromJson(file.readText(), ImportJob::class.java)
    } catch (e: Exception) {
        Log.w(TAG, "Unreadable import job $file: ${e.message}")
        null
    }

    /** Saves [job] whole or not at all: a kill while writing leaves the previous one. */
    fun save(file: File, job: ImportJob) {
        val next = File(file.path + ".tmp")
        next.writeText(gson.toJson(job))
        if (!next.renameTo(file)) throw IllegalStateException("Cannot save import job $file")
    }

    /** The job ended: its file goes, and the document access it kept. */
    fun finish(context: Context, file: File) {
        read(file)?.request?.uris?.forEach { releaseAccess(context, it) }
        file.delete()
    }

    /** Jobs of imports that did not end (stopped, or never run again); call when none runs. */
    fun finishAll(context: Context) {
        dir(context).listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() else finish(context, it) }
    }

    private fun keepAccess(context: Context, uri: String) {
        try {
            context.contentResolver.takePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not persistable: readable while the process lives
            Log.d(TAG, "No lasting access to $uri: ${e.message}")
        }
    }

    private fun releaseAccess(context: Context, uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }
}
