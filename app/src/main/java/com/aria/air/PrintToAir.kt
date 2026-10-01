package com.aria.air

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Save to Air": a printer that lives on the phone. Print a confirmation email from Gmail (or any page) to it and the
 * PDF goes through the usual import, landing on the review screen. Nothing leaves the device.
 */
class PrintToAir : PrintService() {
    override fun onCreatePrinterDiscoverySession() = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: List<PrinterId>) {
            val id = generatePrinterId("air")
            val caps = PrinterCapabilitiesInfo.Builder(id)
                .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, true).addMediaSize(PrintAttributes.MediaSize.ISO_A4, false)
                .addResolution(PrintAttributes.Resolution("300", "300 dpi", 300, 300), true)
                .setColorModes(PrintAttributes.COLOR_MODE_COLOR, PrintAttributes.COLOR_MODE_COLOR)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
            addPrinters(listOf(PrinterInfo.Builder(id, "Save to Air", PrinterInfo.STATUS_IDLE).setDescription("Import flights").setCapabilities(caps).build()))
        }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: List<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) {}
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    /** Copies the job's PDF out of the spooler, finishes the job, then imports the copy. Job calls stay on the main thread. */
    override fun onPrintJobQueued(job: PrintJob) {
        if (!job.isQueued || !job.start()) return
        val file = File(cacheDir, "printed.pdf")
        val data = job.document.data
        App.scope.launch {
            val copied = runCatching {
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseInputStream(data!!).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
            when {
                job.isCancelled -> return@launch
                copied.isFailure -> { job.fail(copied.exceptionOrNull()?.message ?: "Couldn't read the document"); return@launch }
                else -> job.complete()
            }
            Nav.open(Tab.Flights)
            importInto(this@PrintToAir, Uri.fromFile(file), null, friend = false)
            startActivity(Intent(this@PrintToAir, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onRequestCancelPrintJob(job: PrintJob) {
        if (!job.isCompleted && !job.isCancelled) job.cancel()
    }
}
