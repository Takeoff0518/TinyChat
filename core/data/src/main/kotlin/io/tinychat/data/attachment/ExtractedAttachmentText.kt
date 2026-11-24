package io.tinychat.data.attachment

import android.annotation.SuppressLint
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import java.io.File

internal class AttachmentTextTooLong : Exception()

internal class AttachmentText(private val maxChars: Int = 128 * 1024) {
    private val buffer = StringBuilder()

    fun append(text: String) {
        if (text.length > maxChars - buffer.length) throw AttachmentTextTooLong()
        buffer.append(text)
    }

    fun line(text: String = "") {
        append(text)
        append("\n")
    }

    fun content(): String = buffer.toString()

    fun finish(): String? = content().trim().takeIf(String::isNotEmpty)
}

internal object PdfAttachmentText {
    private const val MAX_PAGES = 250
    private const val PDF_EXTENSION_VERSION = 13

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= PDF_EXTENSION_VERSION)

    @SuppressLint("NewApi") // The pre-35 renderer requires S Extensions 13.
    fun read(file: File): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> withDescriptor(file) { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                readPages(renderer.pageCount) { index, result ->
                    renderer.openPage(index).use { page ->
                        page.textContents.forEach { block -> result.line(block.text) }
                    }
                }
            }
        }
        available -> withDescriptor(file) { descriptor ->
            PdfRendererPreV(descriptor).use { renderer ->
                readPages(renderer.pageCount) { index, result ->
                    renderer.openPage(index).use { page ->
                        page.textContents.forEach { block -> result.line(block.text) }
                    }
                }
            }
        }
        else -> null
    }

    private inline fun withDescriptor(
        file: File,
        read: (ParcelFileDescriptor) -> String?,
    ): String? = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(read)

    private inline fun readPages(
        pageCount: Int,
        readPage: (Int, AttachmentText) -> Unit,
    ): String? {
        if (pageCount > MAX_PAGES) throw AttachmentTextTooLong()
        val result = AttachmentText()
        repeat(pageCount) { index ->
            readPage(index, result)
            result.line()
        }
        return result.finish()
    }
}
