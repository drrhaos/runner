package com.runner.academy.util

import com.runner.academy.util.fit.FitTestWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportFormatTest {

    private fun sniff(bytes: ByteArray) = ImportFormat.sniff(BufferedInputStream(bytes.inputStream()))

    private fun sniff(text: String) = sniff(text.toByteArray())

    @Test
    fun `archives by their signatures`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("a.gpx")); it.write(1) }
        }.toByteArray()
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(1) } }.toByteArray()

        assertEquals(ImportFormat.ZIP, sniff(zip))
        assertEquals(ImportFormat.GZIP, sniff(gzip))
    }

    @Test
    fun `a FIT file by its header`() {
        assertEquals(ImportFormat.FIT, sniff(FitTestWriter().fileId().bytes()))
    }

    @Test
    fun `GPX and TCX by the root element, whatever comes before it`() {
        assertEquals(ImportFormat.GPX, sniff("""<?xml version="1.0"?><gpx version="1.1"></gpx>"""))
        assertEquals(
            ImportFormat.GPX,
            sniff("﻿  \n<?xml version=\"1.0\"?>\n<!-- exported -->\n<!DOCTYPE gpx>\n<gpx></gpx>")
        )
        assertEquals(ImportFormat.TCX, sniff("""   <?xml version="1.0"?><TrainingCenterDatabase xmlns="x"/>"""))
        assertEquals(ImportFormat.TCX, sniff("""<?xml version="1.0"?><ns2:TrainingCenterDatabase xmlns:ns2="x"/>"""))
        assertEquals(ImportFormat.GPX, sniff("""<gpx:gpx xmlns:gpx="x"></gpx:gpx>"""))
        assertEquals(ImportFormat.UNKNOWN, sniff("""<?xml version="1.0"?><kml></kml>"""))
    }

    @Test
    fun `the app's backup is a JSON object with workouts`() {
        assertEquals(ImportFormat.BACKUP, sniff("""{"version":2,"exportDate":"x","workoutCount":1,"workouts":[{}]}"""))
        assertEquals(ImportFormat.BACKUP, sniff("""{ "v": 3, "d" : [ ] }"""))
        // A training plans backup, a service's activity summary: other JSON
        assertEquals(ImportFormat.UNKNOWN, sniff("""{"version":1,"plans":[],"templates":[]}"""))
        assertEquals(ImportFormat.UNKNOWN, sniff("""[{"activityId":1}]"""))
        // The key inside a nested object or a string is someone else's
        assertEquals(ImportFormat.UNKNOWN, sniff("""{"summary":{"d":[1,2]},"name":"x"}"""))
        assertEquals(ImportFormat.UNKNOWN, sniff("""{"note":"\"workouts\": [ quoted","n":1}"""))
    }

    @Test
    fun `a DOCTYPE with an internal subset is passed`() {
        assertEquals(
            ImportFormat.GPX,
            sniff("""<?xml version="1.0"?><!DOCTYPE gpx [ <!ENTITY a "b"> ]><gpx></gpx>""")
        )
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(ImportFormat.UNKNOWN, sniff(byteArrayOf()))
        assertEquals(ImportFormat.UNKNOWN, sniff("   "))
        assertEquals(ImportFormat.UNKNOWN, sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals(ImportFormat.UNKNOWN, sniff("activityId,name\n1,Run"))
    }

    @Test
    fun `the stream is left at its start`() {
        val input = BufferedInputStream("  <gpx></gpx>".byteInputStream())

        ImportFormat.sniff(input)

        assertEquals("  <gpx></gpx>", input.readBytes().decodeToString())
    }

    @Test
    fun `skipBlanks passes a BOM and white space`() {
        val input = BufferedInputStream("﻿ \r\n<?xml?>".byteInputStream())

        ImportFormat.skipBlanks(input)

        assertEquals("<?xml?>", input.readBytes().decodeToString())
    }
}
