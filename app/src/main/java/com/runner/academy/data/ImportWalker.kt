package com.runner.academy.data

import android.os.Build
import android.util.Log
import com.runner.academy.util.ImportFormat
import com.runner.academy.util.NotRunningException
import com.runner.academy.util.TrackFileImport
import com.runner.academy.util.NotAnActivityException
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * What the walk of a file yields. Each one but [Archive] is an import unit: a track file, an
 * entry of an archive, a backup — what [ImportTotals.done] counts.
 */
sealed interface ImportRead {

    /** A ZIP or gzip was opened: how many units the file holds is not known ahead. Not a unit. */
    data object Archive : ImportRead

    class Track(val candidate: ImportCandidate) : ImportRead

    /** The app's backup: its rows are read from [reader] before the walk is taken further. */
    class Backup(val name: String?, val reader: Reader) : ImportRead

    /** A workout of another sport. */
    data object NotRunning : ImportRead

    /** Recognised but unreadable: a cut FIT, broken XML or gzip, an entry ZIP cannot inflate. */
    data object Failed : ImportRead

    /** No workout file (a photo, another app's JSON, an empty file), or nested too deep. */
    data object Ignored : ImportRead
}

/**
 * Reads the import units of one file as a stream: a track file is one; a ZIP or gzip is opened
 * (nested ones too, [maxContainers] deep) and each of its files is told by its content
 * ([ImportFormat]). Nothing is written to disk and nothing is held whole but one track
 * ([maxTrackBytes] unpacked at most). Entries come in the archive's order, so a walk run again
 * meets the same units and can pass over those done before.
 */
class ImportWalker(
    private val maxContainers: Int = MAX_CONTAINERS,
    private val maxTrackBytes: Long = MAX_TRACK_BYTES
) {

    /**
     * The units of [input] (a file named [name]) after its first [skip] ones, read as they are
     * taken: the units passed over are recognised but not read. A [ImportRead.Backup]'s reader
     * is only good until the next one is taken. [input] is not closed.
     */
    fun units(name: String?, input: InputStream, skip: Int = 0): Sequence<ImportRead> =
        walk(name, BufferedInputStream(NonClosing(input)), containers = 0, Skip(skip))

    /** Units still to pass over, shared by the whole walk of a file. */
    private class Skip(var left: Int) {

        /** Passes one unit over when some are left to pass: true when it was. */
        fun pass(): Boolean {
            if (left <= 0) return false
            left--
            return true
        }
    }

    private fun walk(name: String?, input: BufferedInputStream, containers: Int, skip: Skip): Sequence<ImportRead> =
        sequence {
            val format = try {
                ImportFormat.sniff(input)
            } catch (e: Throwable) {
                failure(name, e, skip)?.let { yield(it) }
                return@sequence
            }
            val container = format == ImportFormat.ZIP || format == ImportFormat.GZIP
            if (container && containers < maxContainers) {
                yield(ImportRead.Archive)
                if (format == ImportFormat.ZIP) {
                    yieldAll(zip(input, containers + 1, skip))
                } else {
                    yieldAll(walk(name?.removeSuffix(".gz"), BufferedInputStream(GZIPInputStream(input)), containers + 1, skip))
                }
            } else if (!skip.pass()) {
                yield(
                    when (format) {
                        // Nested too deep, or no workout file
                        ImportFormat.ZIP, ImportFormat.GZIP, ImportFormat.UNKNOWN -> ImportRead.Ignored
                        // A backup is read without a limit: its rows stream through
                        ImportFormat.BACKUP -> ImportRead.Backup(name, input.reader(Charsets.UTF_8).buffered())
                        ImportFormat.FIT, ImportFormat.GPX, ImportFormat.TCX -> track(format, name, input)
                    }
                )
            }
        }.catchingReads(name, skip)

    /**
     * The error unit of [name], unreadable past here ([e]); null while it is passed over. Only
     * failures of reading count: an exception, or the InternalError java.util.zip throws for an
     * entry it cannot inflate (Deflate64); anything else goes on up.
     */
    private fun failure(name: String?, e: Throwable, skip: Skip): ImportRead? {
        if (e !is Exception && e !is InternalError) throw e
        Log.w(TAG, "Unreadable $name: ${e.message}")
        return if (skip.pass()) null else ImportRead.Failed
    }

    /** The files of a ZIP in its order; when it stops reading, its rest is one error. */
    private fun zip(input: InputStream, containers: Int, skip: Skip): Sequence<ImportRead> = sequence {
        // Android 14+ refuses entry names with `..` or a leading `/`: nothing is unpacked to disk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            dalvik.system.ZipPathValidator.clearCallback()
        }
        val zip = ZipInputStream(input)
        while (true) {
            val entry = try {
                zip.nextEntry ?: break
            } catch (e: Throwable) {
                failure("archive past here", e, skip)?.let { yield(it) }
                break
            }
            if (entry.isDirectory) continue
            yieldAll(walk(entry.name.substringAfterLast('/'), BufferedInputStream(NonClosing(zip)), containers, skip))
        }
    }

    /**
     * An error while a container is read (a gzip that breaks inside) ends this part of the walk
     * as one error, unless it is still being passed over.
     */
    private fun Sequence<ImportRead>.catchingReads(name: String?, skip: Skip): Sequence<ImportRead> {
        val source = this
        return sequence {
            val reads = source.iterator()
            while (true) {
                val next = try {
                    if (!reads.hasNext()) break
                    reads.next()
                } catch (e: Throwable) {
                    failure(name, e, skip)?.let { yield(it) }
                    break
                }
                yield(next)
            }
        }
    }

    private fun track(format: ImportFormat, name: String?, input: InputStream): ImportRead = try {
        val workout = TrackFileImport.parse(format, BufferedInputStream(Capped(NonClosing(input), maxTrackBytes)), name)
        ImportRead.Track(ImportCandidate(workout, source = name))
    } catch (e: NotRunningException) {
        Log.i(TAG, "Skip $name: not a run")
        ImportRead.NotRunning
    } catch (e: NotAnActivityException) {
        ImportRead.Ignored
    } catch (e: Exception) {
        Log.w(TAG, "Skip track file $name: ${e.message}")
        ImportRead.Failed
    } catch (e: InternalError) {
        // java.util.zip on an entry it cannot inflate
        Log.w(TAG, "Skip track file $name: ${e.message}")
        ImportRead.Failed
    }

    /** A stream whose close leaves the one under it open (an archive's entry, a parser's `use`). */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    /** Fails a read past [limit] bytes: a track larger than that is no track the app keeps. */
    private class Capped(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counted(it) }

        override fun skip(n: Long): Long = super.skip(n).also { counted(it.toInt()) }

        private fun counted(bytes: Int) {
            count += bytes
            if (count > limit) throw IOException("Track over $limit bytes")
        }
    }

    companion object {
        private const val TAG = "ImportWalker"

        /** Archives within archives: a service's full export may be a ZIP of ZIPs or hold `.gz` files. */
        const val MAX_CONTAINERS = 3

        const val MAX_TRACK_BYTES = 64L * 1024 * 1024
    }
}
