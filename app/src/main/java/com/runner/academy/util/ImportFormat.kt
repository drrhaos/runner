package com.runner.academy.util

import com.runner.academy.util.fit.FitDecoder
import java.io.BufferedInputStream

/**
 * What a file to import is, told by its first bytes and never by its name: the same detector
 * serves a picked file, a file of a folder and an entry of an archive.
 */
enum class ImportFormat {
    ZIP,
    GZIP,
    FIT,
    GPX,
    TCX,

    /** The app's own workout backup (JSON). */
    BACKUP,

    /** Anything else (a photo, another app's JSON, a plans backup, an empty file): skipped silently. */
    UNKNOWN;

    companion object {

        /** How much of a file is looked at. */
        const val HEAD = 4096

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** The first element of an XML text, its namespace prefix left out. */
        private val XML_ROOT = Regex("""^<(?:[\w.-]+:)?([\w.-]+)""")

        /** A backup's array of workouts (the full or the short key). */
        private val BACKUP_ROWS = Regex(""""(?:workouts|d)"\s*:\s*\[""")

        /** The format of what [input] starts with; it is left at its start (mark and reset). */
        fun sniff(input: BufferedInputStream): ImportFormat {
            input.mark(HEAD)
            val head = ByteArray(HEAD)
            var size = 0
            while (size < HEAD) {
                val n = input.read(head, size, HEAD - size)
                if (n < 0) break
                size += n
            }
            input.reset()
            val bytes = head.copyOf(size)
            return when {
                bytes.startsWith(ZIP_MAGIC) -> ZIP
                bytes.startsWith(GZIP_MAGIC) -> GZIP
                FitDecoder.isFit(bytes) -> FIT
                else -> textFormat(bytes)
            }
        }

        /** Passes a UTF-8 BOM and white space, which an XML parser does not take before `<?xml`. */
        fun skipBlanks(input: BufferedInputStream) {
            input.mark(HEAD)
            val head = ByteArray(HEAD)
            val size = input.read(head).coerceAtLeast(0)
            input.reset()
            val blanks = blanksAt(head.copyOf(size))
            var skipped = 0L
            while (skipped < blanks) {
                val n = input.skip(blanks - skipped)
                if (n <= 0) break
                skipped += n
            }
        }

        private fun textFormat(bytes: ByteArray): ImportFormat {
            // Markup and keys are ASCII: a one-byte charset reads them whatever the text's encoding
            val text = String(bytes, blanksAt(bytes), bytes.size - blanksAt(bytes), Charsets.ISO_8859_1)
            return when {
                text.startsWith("<") -> xmlFormat(text)
                text.startsWith("{") && BACKUP_ROWS.containsMatchIn(text) -> BACKUP
                else -> UNKNOWN
            }
        }

        /** After the declaration, comments and a DOCTYPE: the root `gpx` or `TrainingCenterDatabase`. */
        private fun xmlFormat(xml: String): ImportFormat {
            var rest = xml
            while (true) {
                rest = rest.trimStart()
                rest = when {
                    rest.startsWith("<?") -> rest.substringAfter("?>", "")
                    rest.startsWith("<!--") -> rest.substringAfter("-->", "")
                    rest.startsWith("<!") -> rest.substringAfter(">", "")
                    else -> break
                }
            }
            return when (XML_ROOT.find(rest)?.groupValues?.get(1)) {
                "gpx" -> GPX
                "TrainingCenterDatabase" -> TCX
                else -> UNKNOWN
            }
        }

        private fun blanksAt(bytes: ByteArray): Int {
            var i = if (bytes.startsWith(UTF8_BOM)) UTF8_BOM.size else 0
            while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
            return i
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
