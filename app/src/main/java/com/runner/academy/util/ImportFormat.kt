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

        /** The keys of a backup's array of workouts (the full and the short one). */
        private val BACKUP_KEYS = setOf("workouts", "d")

        /** The format of what [input] starts with; it is left at its start (mark and reset). */
        fun sniff(input: BufferedInputStream): ImportFormat {
            val bytes = head(input)
            return when {
                bytes.startsWith(ZIP_MAGIC) -> ZIP
                bytes.startsWith(GZIP_MAGIC) -> GZIP
                FitDecoder.isFit(bytes) -> FIT
                else -> textFormat(bytes)
            }
        }

        /** Passes a UTF-8 BOM and white space, which an XML parser does not take before `<?xml`. */
        fun skipBlanks(input: BufferedInputStream) {
            val blanks = blanksAt(head(input))
            var skipped = 0L
            while (skipped < blanks) {
                val n = input.skip(blanks - skipped)
                if (n <= 0) break
                skipped += n
            }
        }

        /** Up to [HEAD] first bytes of [input], which is left at its start. */
        private fun head(input: BufferedInputStream): ByteArray {
            input.mark(HEAD)
            val head = ByteArray(HEAD)
            var size = 0
            while (size < HEAD) {
                val n = input.read(head, size, HEAD - size)
                if (n < 0) break
                size += n
            }
            input.reset()
            return head.copyOf(size)
        }

        private fun textFormat(bytes: ByteArray): ImportFormat {
            // Markup and keys are ASCII: a one-byte charset reads them whatever the text's encoding
            val text = String(bytes, blanksAt(bytes), bytes.size - blanksAt(bytes), Charsets.ISO_8859_1)
            return when {
                text.startsWith("<") -> xmlFormat(text)
                text.startsWith("{") && hasBackupRows(text) -> BACKUP
                else -> UNKNOWN
            }
        }

        /**
         * The JSON object [json] has a backup's array of workouts among its own keys: one in a
         * nested object or inside a string is another file's.
         */
        private fun hasBackupRows(json: String): Boolean {
            var depth = 0
            var i = 0
            while (i < json.length) {
                when (json[i]) {
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    '"' -> {
                        val end = stringEnd(json, i)
                        if (end < 0) return false
                        if (depth == 1 && json.substring(i + 1, end) in BACKUP_KEYS) {
                            val after = json.substring(end + 1).trimStart()
                            if (after.startsWith(":") && after.substring(1).trimStart().startsWith("[")) return true
                        }
                        i = end
                    }
                }
                i++
            }
            return false
        }

        /** The index of the quote closing the string opened at [start]; -1 when it is cut off. */
        private fun stringEnd(json: String, start: Int): Int {
            var i = start + 1
            while (i < json.length) {
                when (json[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            return -1
        }

        /** After the declaration, comments and a DOCTYPE: the root `gpx` or `TrainingCenterDatabase`. */
        private fun xmlFormat(xml: String): ImportFormat {
            var rest = xml
            while (true) {
                rest = rest.trimStart()
                rest = when {
                    rest.startsWith("<?") -> rest.substringAfter("?>", "")
                    rest.startsWith("<!--") -> rest.substringAfter("-->", "")
                    // A DOCTYPE may hold declarations in brackets, `>` among them
                    rest.startsWith("<!") && rest.indexOf('[') in 0 until rest.indexOf('>') ->
                        rest.substringAfter("]").substringAfter(">", "")
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
