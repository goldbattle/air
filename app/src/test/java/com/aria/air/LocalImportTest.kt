package com.aria.air

import com.aria.air.data.*
import com.aria.air.importer.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the import parser over a folder of your own documents (text, .eml/.html, byAir CSV, backups, and PDFs already
 * turned into "name.pdf.txt") and checks each against expected.tsv there: file, traveler, flights. known.txt lists
 * the people to match (you first, then friends), one per line. Writes actual.tsv (same format, to accept) and
 * report.txt. Skipped unless AIR_LOCAL_DIR is set; the gitignored local/run.py drives it.
 */
class LocalImportTest {
    @Test
    fun parseLocalFiles() {
        val dir = System.getenv("AIR_LOCAL_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory == true)
        Db.load(File("src/main/assets/airports.tsv").reader(), File("src/main/assets/airlines.tsv").reader())
        val skip = setOf("report.txt", "actual.tsv", "expected.tsv", "known.txt")
        val known = File(dir, "known.txt").takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val expected = File(dir, "expected.tsv").takeIf { it.exists() }?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }?.associate { it.substringBefore('\t') to it }.orEmpty()
        val files = dir!!.listFiles()!!.filter { it.isFile && it.name !in skip }.sortedBy { it.name }
        val actual = files.map { f ->
            // As importInto() does: PDF text is used as is, anything else shared or picked has HTML stripped.
            val t = f.readText().let { if (f.name.endsWith(".pdf.txt")) it else Importer.stripHtml(it) }
            val name = f.name.removeSuffix(".txt")
            // ponytail: yearless dates are read as of today, so an email's "Dec 3" can drift; add a per-file date if one does.
            when {
                Store.isBackup(t) -> "$name\tbackup\t${Store.parse(t).joinToString(", ") { "${it.label} ${it.date} ${it.from}-${it.to}" }}"
                isByAirCsv(t) -> "$name\tbyAir\t${byAirFlights(t).joinToString(", ") { "${it.label} ${it.date} ${it.from}-${it.to} ${it.friend}".trim() }}"
                else -> "$name\t${Importer.traveler(t, known) ?: "-"}\t" +
                    Importer.parse(t).joinToString(", ") { "${it.label} ${it.date ?: "?"} ${it.from ?: "?"}-${it.to ?: "?"}" }
            }
        }
        File(dir, "actual.tsv").writeText(actual.joinToString("\n", postfix = "\n"))
        val fails = actual.mapNotNull { a ->
            val e = expected[a.substringBefore('\t')]
            when {
                e == null -> "NEW   $a"
                e != a -> "FAIL  expected $e\n      got      $a"
                else -> null
            }
        }
        File(dir, "report.txt").writeText(
            "${actual.size - fails.size}/${actual.size} as expected\n" + fails.joinToString("\n", postfix = "\n"),
        )
        assertTrue("see report.txt", fails.none { it.startsWith("FAIL") })
    }
}
