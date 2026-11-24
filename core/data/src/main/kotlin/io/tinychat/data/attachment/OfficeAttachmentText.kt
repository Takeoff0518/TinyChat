package io.tinychat.data.attachment

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/** Reads only the text-bearing XML parts. Embedded files, macros and external relationships are ignored. */
internal object OfficeAttachmentText {
    private const val MAX_XML_BYTES = 16 * 1024 * 1024L
    private const val MAX_ZIP_ENTRIES = 8_192
    private const val MAX_SHARED_STRINGS = 65_536
    private const val MAX_SHARED_STRING_CHARS = 512 * 1024

    fun read(file: File, type: InlineDocumentType): String? = ZipFile(file).use { zip ->
        if (zip.size() > MAX_ZIP_ENTRIES) throw AttachmentTextTooLong()
        val reader = OfficeXml(zip)
        val result = AttachmentText()
        when (type) {
            InlineDocumentType.WORD -> word(reader, result)
            InlineDocumentType.WORKBOOK -> workbook(reader, result)
            InlineDocumentType.PRESENTATION -> presentation(reader, result)
            else -> return null
        }
        result.finish()
    }

    private fun word(reader: OfficeXml, result: AttachmentText) {
        for (part in listOf("word/document.xml", "word/footnotes.xml", "word/endnotes.xml")) {
            reader.scan(part) { xml ->
                if (xml.namespace?.contains("/wordprocessingml/") == true) {
                    when (xml.eventType) {
                        XmlPullParser.START_TAG -> when (xml.name) {
                            "t" -> result.append(xml.nextText())
                            "tab" -> result.append("\t")
                            "br", "cr" -> result.line()
                        }
                        XmlPullParser.END_TAG -> when (xml.name) {
                            "p", "tr" -> result.line()
                            "tc" -> result.append("\t")
                        }
                    }
                }
            }
        }
    }

    private fun workbook(reader: OfficeXml, result: AttachmentText) {
        val shared = sharedStrings(reader)
        for ((path, name) in reader.parts("xl", "workbook", "sheet", "worksheets")) {
            val sheet = AttachmentText()
            var rowHasText = false
            reader.scan(path) { xml ->
                when {
                    xml.eventType == XmlPullParser.START_TAG && xml.name == "c" -> {
                        val ref = xml.attribute("r")
                        val value = cell(xml, shared)
                        if (!value.isNullOrBlank()) {
                            if (rowHasText) sheet.append("\t")
                            if (!ref.isNullOrBlank()) sheet.append("$ref: ")
                            sheet.append(value)
                            rowHasText = true
                        }
                    }
                    xml.eventType == XmlPullParser.END_TAG && xml.name == "row" && rowHasText -> {
                        sheet.line()
                        rowHasText = false
                    }
                }
            }
            sheet.finish()?.let { text ->
                result.line("## $name")
                result.line(text)
                result.line()
            }
        }
    }

    private fun sharedStrings(reader: OfficeXml): List<String> {
        val items = mutableListOf<String>()
        var length = 0
        reader.scan("xl/sharedStrings.xml") { xml ->
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "si") {
                val text = richText(xml)
                if (items.size >= MAX_SHARED_STRINGS || text.length > MAX_SHARED_STRING_CHARS - length) {
                    throw AttachmentTextTooLong()
                }
                length += text.length
                items += text
            }
        }
        return items
    }

    private fun cell(xml: XmlPullParser, shared: List<String>): String? {
        val type = xml.attribute("t")
        var value: String? = null
        var formula: String? = null
        xml.children {
            if (xml.eventType == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "v" -> value = xml.nextText()
                    "is" -> value = richText(xml)
                    "f" -> formula = xml.nextText().takeIf(String::isNotEmpty)
                }
            }
        }
        return when (type) {
            "s" -> value?.toIntOrNull()?.let(shared::getOrNull)
            "b" -> if (value == "1") "TRUE" else if (value == "0") "FALSE" else value
            else -> value?.takeIf(String::isNotEmpty) ?: formula?.let { "=$it" }
        }
    }

    private fun richText(xml: XmlPullParser): String {
        val text = AttachmentText()
        xml.children {
            if (xml.eventType == XmlPullParser.START_TAG) {
                when (xml.name) {
                    "t" -> text.append(xml.nextText())
                    "rPh" -> xml.children { }
                }
            }
        }
        return text.content()
    }

    private fun presentation(reader: OfficeXml, result: AttachmentText) {
        val parts = reader.parts("ppt", "presentation", "sldId", "slides")
        for ((index, part) in parts.withIndex()) {
            val slide = AttachmentText()
            reader.scan(part.path) { xml ->
                if (xml.namespace?.contains("/drawingml/") == true) {
                    if (xml.eventType == XmlPullParser.START_TAG && xml.name == "t") slide.append(xml.nextText())
                    if (xml.eventType == XmlPullParser.END_TAG && xml.name == "p") slide.line()
                }
            }
            slide.finish()?.let { text ->
                result.line("## Slide ${index + 1}")
                result.line(text)
                result.line()
            }
        }
    }

    private data class Part(val path: String, val name: String)

    private class OfficeXml(private val zip: ZipFile) {
        private var remaining = MAX_XML_BYTES

        fun parts(folder: String, main: String, tag: String, subfolder: String): List<Part> {
            val prefix = "$folder/$subfolder/"
            val relationships = mutableMapOf<String, String>()
            scan("$folder/_rels/$main.xml.rels") { xml ->
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "Relationship" &&
                    xml.attribute("TargetMode") != "External"
                ) {
                    val id = xml.attribute("Id")
                    val target = xml.attribute("Target")
                    if (id != null && target != null) {
                        val path = if (target.startsWith('/')) target.drop(1) else "$folder/$target"
                        if (path.startsWith(prefix) && path.endsWith(".xml") &&
                            path.split('/').none { it == "." || it == ".." }
                        ) {
                            relationships[id] = path
                        }
                    }
                }
            }
            val ordered = mutableListOf<Part>()
            scan("$folder/$main.xml") { xml ->
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == tag) {
                    val path = xml.relationshipId()?.let(relationships::get)
                    if (path != null) ordered += Part(path, xml.attribute("name") ?: label(path))
                }
            }
            if (ordered.isNotEmpty()) return ordered

            val conventional = if (subfolder == "slides") "slide" else "sheet"
            val pattern = Regex("${Regex.escape(prefix)}$conventional([0-9]+)\\.xml")
            return zip.entries().asSequence().mapNotNull { entry ->
                val number = pattern.matchEntire(entry.name)?.groupValues?.get(1)?.toIntOrNull()
                number?.let { it to Part(entry.name, label(entry.name)) }
            }.sortedBy { it.first }.map { it.second }.toList()
        }

        fun scan(path: String, visit: (XmlPullParser) -> Unit) {
            val part = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: return
            if (part.size > remaining) throw AttachmentTextTooLong()
            zip.getInputStream(part).use { input ->
                val xml = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    if (getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                    }
                    setInput(bounded(input), null)
                }
                while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType == XmlPullParser.DOCDECL) throw IOException("OOXML parts must not include a DTD")
                    visit(xml)
                    xml.nextToken()
                }
            }
        }

        private fun bounded(input: InputStream): InputStream = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) charge(1) }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, length).also { if (it > 0) charge(it) }
        }

        private fun charge(count: Int) {
            remaining -= count
            if (remaining < 0) throw AttachmentTextTooLong()
        }

        private fun label(path: String) = path.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun XmlPullParser.attribute(name: String): String? = getAttributeValue(null, name)

    private fun XmlPullParser.relationshipId(): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index) == "id" &&
                getAttributeNamespace(index)?.contains("/relationships") == true
            ) return getAttributeValue(index)
        }
        return null
    }

    private inline fun XmlPullParser.children(visit: () -> Unit) {
        val parentDepth = depth
        while (nextToken() != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.END_TAG && depth == parentDepth) return
            visit()
        }
        throw IOException("Unexpected end of OOXML part")
    }
}
