package com.runner.academy.data

import com.runner.academy.util.fit.FitTestWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportWalkerTest {

    private val walker = ImportWalker()

    private fun gpx(day: Int = 1, type: String? = null): ByteArray {
        val start = "2024-01-%02dT10".format(day)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk>${type?.let { "<type>$it</type>" } ?: ""}<trkseg>
              <trkpt lat="55.7558" lon="37.6176"><time>$start:00:00Z</time></trkpt>
              <trkpt lat="55.7608" lon="37.6176"><time>$start:05:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trim().toByteArray()
    }

    private fun fit(): ByteArray {
        val start = 1_700_000_000_000L
        return FitTestWriter().fileId()
            .record(start, 55.0, 37.0)
            .record(start + 10_000, 55.0003, 37.0)
            .session(start, timerS = 10.0, distanceM = 33.0)
            .bytes()
    }

    private fun zip(vararg entries: Pair<String, ByteArray?>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                bytes?.let(zip::write)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun units(bytes: ByteArray, name: String = "file", skip: Int = 0, walker: ImportWalker = this.walker) =
        walker.units(name, bytes.inputStream(), skip).toList()

    private fun List<ImportRead>.kinds() = map { it.javaClass.simpleName }

    @Test
    fun `a plain track file is one unit`() {
        val reads = units(gpx(), "run.gpx")

        assertEquals(listOf("Track"), reads.kinds())
        assertEquals("run.gpx", (reads.single() as ImportRead.Track).candidate.source)
    }

    @Test
    fun `a ZIP gives a unit per file, by content, folders left out`() {
        val reads = units(
            zip(
                "activities/" to null,
                "activities/1.gpx" to gpx(1),
                "activities/2.fit" to fit(),
                "export.txt" to gpx(3),
                "media/photo.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2),
                "summary.json" to """[{"activityId":1}]""".toByteArray(),
                "empty.gpx" to ByteArray(0)
            ),
            "export.zip"
        )

        assertEquals(listOf("Archive", "Track", "Track", "Track", "Ignored", "Ignored", "Ignored"), reads.kinds())
        assertEquals("1.gpx", (reads[1] as ImportRead.Track).candidate.source)
    }

    @Test
    fun `nested ZIPs and gzip are read through`() {
        val inner = zip("UploadedFiles_0-_Part1/a.fit" to fit(), "b.gpx" to gpx(2))
        val reads = units(zip("DI_CONNECT/UploadedFiles_0-_Part1.zip" to inner, "activities/3.gpx.gz" to gzip(gpx(3))))

        assertEquals(listOf("Archive", "Archive", "Track", "Track", "Archive", "Track"), reads.kinds())
        assertEquals("3.gpx", (reads.last() as ImportRead.Track).candidate.source)
    }

    @Test
    fun `a gzip file on its own is an archive of one track`() {
        assertEquals(listOf("Archive", "Track"), units(gzip(fit()), "1.fit.gz").kinds())
    }

    @Test
    fun `containers deeper than three are passed silently`() {
        val deep = zip("4.zip" to zip("run.gpx" to gpx()))
        val reads = units(zip("2.zip" to zip("3.zip" to deep)))

        assertEquals(listOf("Archive", "Archive", "Archive", "Ignored"), reads.kinds())
    }

    @Test
    fun `a track over the size limit is an error`() {
        val small = ImportWalker(maxTrackBytes = 100)

        assertEquals(listOf("Archive", "Failed"), units(zip("big.gpx" to gpx()), walker = small).kinds())
    }

    @Test
    fun `broken files are errors, other sports and non-activities apart`() {
        val reads = units(
            zip(
                "broken.gpx" to "<?xml version=\"1.0\"?><gpx><trk>".toByteArray(),
                "ride.gpx" to gpx(type = "Ride"),
                "course.fit" to FitTestWriter().fileId(type = 6).bytes(),
                "cut.gz" to gzip(gpx()).copyOf(30),
                "run.gpx" to gpx()
            )
        )

        assertEquals(listOf("Archive", "Failed", "NotRunning", "Ignored", "Archive", "Failed", "Track"), reads.kinds())
    }

    @Test
    fun `an entry of a compression ZIP cannot inflate is an error, not a crash`() {
        val bytes = zip("a.gpx" to gpx(1), "b.gpx" to gpx(2))
        // Deflate64 (method 9) in the first entry's local and central headers
        listOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04) to 8, byteArrayOf(0x50, 0x4B, 0x01, 0x02) to 10).forEach { (signature, offset) ->
            val at = (0..bytes.size - 4).first { i -> signature.indices.all { bytes[i + it] == signature[it] } }
            bytes[at + offset] = 9
            bytes[at + offset + 1] = 0
        }

        val reads = units(bytes)

        assertEquals("Archive", reads.first().javaClass.simpleName)
        assertEquals("Failed", reads[1].javaClass.simpleName)
    }

    @Test
    fun `an archive that stops reading is one error for its rest`() {
        val bytes = zip("a.gpx" to gpx(1), "b.gpx" to gpx(2), "c.gpx" to gpx(3))
        // Cut inside the second entry
        val cut = bytes.copyOf(bytes.size / 2)

        val reads = units(cut)

        assertEquals("Track", reads[1].javaClass.simpleName)
        assertEquals("Failed", reads.last().javaClass.simpleName)
        assertTrue(reads.size <= 4)
    }

    @Test
    fun `the app's backup in an archive is handed over to read`() {
        val json = """{"version":2,"workoutCount":0,"workouts":[]}"""
        val reads = units(zip("runner_backup.json" to json.toByteArray(), "run.gpx" to gpx()))

        assertEquals(listOf("Archive", "Backup", "Track"), reads.kinds())
    }

    @Test
    fun `a backup's reader holds its rows while the walk is there`() {
        val json = """{"version":2,"workouts":[]}"""
        val texts = mutableListOf<String>()
        walker.units("x.zip", zip("b.json" to json.toByteArray()).inputStream()).forEach { read ->
            if (read is ImportRead.Backup) texts += read.reader.readText()
        }

        assertEquals(listOf(json), texts)
    }

    @Test
    fun `the first units are passed over on a run again`() {
        val bytes = zip("a.gpx" to gpx(1), "photo.jpg" to byteArrayOf(1, 2), "nested.zip" to zip("b.gpx" to gpx(2), "c.gpx" to gpx(3)))

        val reads = units(bytes, skip = 3)

        // Units: a.gpx, photo.jpg, b.gpx — passed; c.gpx read
        assertEquals(listOf("Archive", "Archive", "Track"), reads.kinds())
        assertEquals("c.gpx", (reads.last() as ImportRead.Track).candidate.source)
    }
}
