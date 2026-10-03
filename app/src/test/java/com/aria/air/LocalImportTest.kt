package com.aria.air

import com.aria.air.data.*
import com.aria.air.importer.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the import parser over a folder of your own documents (text, .eml/.html, byAir CSV, backups, and PDFs already
 * turned into "name.pdf.txt") and writes what each one parses to into report.txt there. Skipped unless AIR_LOCAL_DIR
 * is set; a gitignored local/run.py drives it.
 */
class LocalImportTest {
    @Test
    fun parseLocalFiles() {
        val dir = System.getenv("AIR_LOCAL_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        Db.load(File("src/main/assets/airports.tsv").reader(), File("src/main/assets/airlines.tsv").reader())
        val report = dir!!.listFiles()!!.filter { it.isFile && it.name != "report.txt" }.sortedBy { it.name }.joinToString("\n\n") { f ->
            // As importInto() does: PDF text is used as is, anything else shared or picked has HTML stripped.
            val t = f.readText().let { if (f.name.endsWith(".pdf.txt")) it else Importer.stripHtml(it) }
            "== ${f.name}\n" + when {
                Store.isBackup(t) -> "Air backup: ${Store.parse(t).size} flights"
                isByAirCsv(t) -> byAirFlights(t).joinToString("\n", "byAir export:\n") { "  ${it.date} ${it.label} ${it.from}-${it.to} ${it.friend}" }
                else -> "traveler: ${Importer.traveler(t)}  work: ${Importer.isWork(t)}\n" +
                    Importer.parse(t).joinToString("\n").ifEmpty { "no flights found" }
            }
        }
        File(dir, "report.txt").writeText(report + "\n")
    }
}
