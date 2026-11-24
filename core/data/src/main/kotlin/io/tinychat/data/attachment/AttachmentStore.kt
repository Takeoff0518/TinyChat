package io.tinychat.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import io.tinychat.net.transport.ChatException
import io.tinychat.model.ErrorKind
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * 选择器给出的一个文件（还没入库）。
 *
 * 只带 `uri` 与元信息，**不拷原件**：原件的存放路径需要 `messageId`，
 * 而 `messageId` 要等用户点发送时才有（02 §3.4）。缩略图另外生成，路径固定。
 */
data class PickedFile(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val mime: String?,
    val isImage: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    /** 图片缩略图（webp）；非图片或解码失败为 null */
    val thumbPath: String? = null,
)

/** 落盘结果：原件绝对路径 + 实际大小 */
data class SavedFile(val path: String, val size: Long)

enum class InlineDocumentType(val maxBytes: Long, val limit: String) {
    PDF(8L * 1024 * 1024, "8 MiB"),
    TEXT(512L * 1024, "512 KiB"),
    WORD(16L * 1024 * 1024, "16 MiB"),
    WORKBOOK(16L * 1024 * 1024, "16 MiB"),
    PRESENTATION(16L * 1024 * 1024, "16 MiB"),
}

/**
 * 附件文件仓库（M4-1/2/3）：目录布局按 02 §3.4。
 *
 * ```
 * filesDir/attachments/{messageId}/{id}_{safeName}   # 原件
 * filesDir/thumbs/{id}.webp                          # 缩略图（最长边 512px）
 * ```
 *
 * 两条硬规则：流式拷贝（**禁止 `readAllBytes`**）、单文件 100MiB 上限。
 */
class AttachmentStore(private val context: Context) {

    companion object {
        /** Files API 单文件上限（03 §6.5） */
        const val MAX_BYTES: Long = 100L * 1024 * 1024

        /** 缩略图最长边 */
        const val THUMB_MAX_PX = 512

        /** 第三方视觉：压到这个长边再内联（05 §6.4） */
        const val VISION_MAX_PX = 1568

        /** 第三方视觉的 JPEG 质量 */
        const val VISION_QUALITY = 85

        const val MAX_INLINE_REQUEST_BYTES = 16L * 1024 * 1024

        private const val MAX_VISION_BYTES = 3L * 1024 * 1024

        private const val COPY_BUFFER = 64 * 1024
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        private val TEXT_MIMES = setOf("application/json", "application/xml", "application/yaml", "application/x-yaml")
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini",
            "html", "htm", "css", "js", "ts", "py", "java", "kt", "kts", "c", "h", "cpp", "hpp", "rs", "go", "sql",
        )

        val supportsLocalPdfText: Boolean get() = PdfAttachmentText.available

        fun inlineDocumentType(fileName: String, mime: String?): InlineDocumentType? {
            val mediaType = mime.orEmpty().substringBefore(';').trim().lowercase()
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return when {
                mediaType == "application/pdf" || extension == "pdf" -> InlineDocumentType.PDF
                mediaType == DOCX_MIME || extension == "docx" -> InlineDocumentType.WORD
                mediaType == XLSX_MIME || extension == "xlsx" -> InlineDocumentType.WORKBOOK
                mediaType == PPTX_MIME || extension == "pptx" -> InlineDocumentType.PRESENTATION
                mediaType.startsWith("text/") || mediaType in TEXT_MIMES || extension in TEXT_EXTENSIONS ->
                    InlineDocumentType.TEXT
                else -> null
            }
        }
    }

    private val attachmentsRoot = File(context.filesDir, "attachments")
    private val thumbsDir = File(context.filesDir, "thumbs")

    fun newId(): String = UUID.randomUUID().toString()

    fun thumbFile(id: String): File = File(thumbsDir, "$id.webp")

    /**
     * 读元信息 + 生成缩略图。原件不落盘。
     *
     * 名字/大小读不出来时给兜底值而不是抛错：某些 provider（含拍照的 FileProvider）
     * 不实现 `OpenableColumns`，但内容本身可读。
     */
    fun inspect(uri: Uri): PickedFile {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }

        val fileName = (name?.takeIf { it.isNotBlank() } ?: fallbackName(uri, mime))
            .substringAfterLast('/')
            .substringAfterLast('\\')
        val isImage = mime?.startsWith("image/") == true || fileName.isImageExtension()
        val id = newId()

        var width: Int? = null
        var height: Int? = null
        var thumbPath: String? = null
        if (isImage) {
            val bounds = decodeBounds(uri)
            width = bounds?.first
            height = bounds?.second
            thumbPath = runCatching {
                writeThumb(id) { context.contentResolver.openInputStream(uri) }
            }.getOrNull()?.absolutePath
        }

        return PickedFile(
            id = id,
            uri = uri,
            fileName = fileName,
            // 有些 provider 不给 SIZE；拷贝时再按实际字节校验上限
            size = if (size > 0) size else 0L,
            mime = mime ?: guessMime(fileName),
            isImage = isImage,
            width = width,
            height = height,
            thumbPath = thumbPath,
        )
    }

    /**
     * 把原件拷进 `attachments/{messageId}/`。超上限直接拒（不写半截文件）。
     */
    fun save(messageId: Long, picked: PickedFile, documentType: InlineDocumentType? = null): SavedFile {
        val maxBytes = documentType?.maxBytes ?: MAX_BYTES
        val sizeError = "附件「${picked.fileName}」超过 ${documentType?.limit ?: "100 MiB"}"
        if (picked.size > maxBytes) throw ChatException(ErrorKind.UPLOAD, message = sizeError)
        val dir = File(attachmentsRoot, messageId.toString())
        dir.mkdirs()
        val target = File(dir, "${picked.id}_${picked.fileName.safeName()}")
        try {
            val input = context.contentResolver.openInputStream(picked.uri)
                ?: throw ChatException(ErrorKind.UPLOAD, message = "无法打开该文件，请重新选择")
            input.use { source ->
                target.outputStream().use { sink ->
                    if (copyLimited(source, sink, maxBytes) < 0) {
                        throw ChatException(ErrorKind.UPLOAD, message = sizeError)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return SavedFile(path = target.absolutePath, size = target.length())
    }

    /** 删除某个附件相关的本地文件（原件 + 缩略图 + 第三方视觉派生图），用于草稿移除/消息删除 */
    fun delete(attachmentId: String, localPath: String?) {
        runCatching { thumbFile(attachmentId).delete() }
        val file = localPath?.let { File(it) }
        file?.let {
            runCatching { visionFile(it.absolutePath).delete() }
            runCatching { it.delete() }
        }
        // 顺手收敛空目录：一块附件都没有了，`attachments/{messageId}/` 就别留着
        val parent = file?.parentFile
        if (parent != null && parent.isDirectory && parent.listFiles()?.isEmpty() == true) {
            runCatching { parent.delete() }
        }
    }

    /** 删除整个消息目录（删消息/删会话的清理路径，M6-6 也会用） */
    fun deleteMessageDir(messageId: Long) {
        runCatching { File(attachmentsRoot, messageId.toString()).deleteRecursively() }
    }

    fun exists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        return file.isFile && file.length() > 0
    }

    /**
     * 给一个**已在磁盘上的**图片补缩略图（导入路径用：导入只拷原件，不生成缩略图，
     * 消息列表就会退化成"每次都解码原图"甚至占位图）。
     */
    fun writeThumbFromFile(id: String, source: File): File? {
        if (!source.isFile) return null
        return runCatching { writeThumb(id) { source.inputStream() } }.getOrNull()
    }

    /** 第三方视觉的派生图：压在原件旁边，压过一次就复用（决策 53） */
    fun visionFile(localPath: String): File = File("$localPath.vision.jpg")

    /**
     * 把本地图片变成可内联的 `data:image/jpeg;base64,…`。
     *
     * OpenAI 兼容链路没有服务端文件引用，图片只能整段塞进报文；历史里每一轮都会重发，
     * 所以只压一次、落盘复用。长边超过 [VISION_MAX_PX] 按比例缩，质量 [VISION_QUALITY]。
     * 失败返回 null（调用方决定报错还是跳过）。
     */
    fun visionDataUri(localPath: String): String? = runCatching {
        val source = storedFile(localPath) ?: return null
        val derived = visionFile(localPath)
        val target = if (derived.isFile && derived.length() > 0) {
            derived
        } else {
            encodeVision(source, derived) ?: return null
        }
        val bytes = readAttachmentBytes(target.absolutePath, MAX_VISION_BYTES) ?: return null
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    fun pdfDataUri(localPath: String): String? = runCatching {
        val bytes = readAttachmentBytes(localPath, InlineDocumentType.PDF.maxBytes) ?: return null
        val header = bytes.decodeToString(endIndex = minOf(bytes.size, 1024))
        if (!header.contains("%PDF-")) return null
        "data:application/pdf;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    fun documentText(localPath: String): String? = runCatching {
        val bytes = readAttachmentBytes(localPath, InlineDocumentType.TEXT.maxBytes) ?: return null
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            .takeIf { text -> text.isNotBlank() && text.none { it.isISOControl() && it !in "\n\r\t\u000C" } }
    }.getOrNull()

    internal fun extractedDocumentText(localPath: String, type: InlineDocumentType): String? = try {
        val file = storedFile(localPath)
        if (file == null || file.length() !in 1..type.maxBytes) {
            null
        } else {
            when (type) {
                InlineDocumentType.PDF -> PdfAttachmentText.read(file)
                InlineDocumentType.TEXT -> documentText(localPath)
                else -> OfficeAttachmentText.read(file, type)
            }
        }
    } catch (e: AttachmentTextTooLong) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun storedFile(path: String): File? {
        val file = File(path).canonicalFile
        return file.takeIf { it.isFile && it.toPath().startsWith(attachmentsRoot.canonicalFile.toPath()) }
    }

    private fun readAttachmentBytes(path: String, maxBytes: Long): ByteArray? {
        val file = storedFile(path) ?: return null
        val length = file.length()
        if (length !in 1..maxBytes) return null
        return DataInputStream(file.inputStream().buffered()).use { source ->
            val bytes = ByteArray(length.toInt())
            source.readFully(bytes)
            bytes.takeIf { source.read() == -1 }
        }
    }

    // —— 内部 ——

    private fun decodeBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    /** 缩略图：按 2 的幂降采样后压到最长边 512px */
    private fun writeThumb(id: String, open: () -> InputStream?): File? {
        val bounds = open()?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            if (options.outWidth > 0) options.outWidth to options.outHeight else null
        } ?: return null
        val longest = maxOf(bounds.first, bounds.second)
        var sample = 1
        while (longest / (sample * 2) >= THUMB_MAX_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val scaled = if (maxOf(bitmap.width, bitmap.height) > THUMB_MAX_PX) {
            val ratio = THUMB_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val out = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            if (out !== bitmap) bitmap.recycle()
            out
        } else {
            bitmap
        }

        thumbsDir.mkdirs()
        val target = thumbFile(id)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        target.outputStream().use { scaled.compress(format, 80, it) }
        scaled.recycle()
        return target.takeIf { it.length() > 0 }
    }

    /** 第三方视觉：按 2 的幂降采样 → 长边 ≤ 1568 → JPEG 85 */
    private fun encodeVision(source: File, target: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= VISION_MAX_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > VISION_MAX_PX) {
            val ratio = VISION_MAX_PX.toFloat() / longest
            val out = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            if (out !== bitmap) bitmap.recycle()
            out
        } else {
            bitmap
        }
        target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, VISION_QUALITY, it) }
        scaled.recycle()
        return target.takeIf { it.length() > 0 }
    }

    /** 流式拷贝；超过上限返回 -1（调用方负责删半截文件） */
    private fun copyLimited(source: InputStream, sink: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = try {
                source.read(buffer)
            } catch (e: IOException) {
                throw ChatException(ErrorKind.UPLOAD, message = e.message ?: "无法读取附件，请检查文件是否完整", cause = e)
            }
            if (read <= 0) break
            total += read
            if (total > maxBytes) return -1
            sink.write(buffer, 0, read)
        }
        return total
    }

    private fun fallbackName(uri: Uri, mime: String?): String {
        val fromUri = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        if (fromUri != null) return fromUri
        val ext = mime?.substringAfterLast('/', "")?.takeIf { it.isNotBlank() } ?: "bin"
        return "附件.$ext"
    }

    private fun guessMime(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt", "md", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "doc" -> "application/msword"
        "docx" -> DOCX_MIME
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> PPTX_MIME
        "xlsx" -> XLSX_MIME
        "epub" -> "application/epub+zip"
        else -> null
    }

    private fun String.isImageExtension(): Boolean =
        substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    private fun String.safeName(): String {
        val cleaned = filterNot { it in "\\/:*?\"<>|" }.trim()
        val limited = if (cleaned.length > 80) cleaned.takeLast(80) else cleaned
        return limited.ifBlank { "file" }
    }
}
