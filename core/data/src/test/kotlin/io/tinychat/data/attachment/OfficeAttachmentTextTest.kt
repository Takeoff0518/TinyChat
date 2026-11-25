package io.tinychat.data.attachment

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficeAttachmentTextTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun `Word text preserves paragraphs and XML entities`() {
        val file = document(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>
                        <w:p><w:r><w:t>Budget &amp; scope</w:t></w:r></w:p>
                        <w:p><w:r><w:t>预算</w:t><w:tab/><w:t>2026</w:t></w:r></w:p>
                    </w:body>
                </w:document>
            """.trimIndent(),
        )
        assertEquals("Budget & scope\n预算\t2026", OfficeAttachmentText.read(file, InlineDocumentType.WORD))
    }

    @Test
    fun `Excel text resolves both shared strings and inline cells`() {
        val file = document(
            "xl/workbook.xml" to """
                <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet r:id="rId2" name="Q4"/></sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to """
                <Relationships>
                    <Relationship Id="rId2" Target="/xl/worksheets/sheet2.xml"/>
                </Relationships>
            """.trimIndent(),
            "xl/sharedStrings.xml" to "<sst><si><r><t>Quarter</t></r><r><t> four</t></r></si></sst>",
            "xl/worksheets/sheet2.xml" to """
                <worksheet><sheetData><row r="3">
                  <c r="A3" t="s">
                    <v>0</v>
                  </c>
                  <c r="C3" t="inlineStr"><is><t>Actual</t></is></c>
                  <c r="D3"><f>SUM(D1:D2)</f><v/></c>
                </row></sheetData></worksheet>
            """.trimIndent(),
        )
        assertEquals(
            "## Q4\nA3: Quarter four\tC3: Actual\tD3: =SUM(D1:D2)",
            OfficeAttachmentText.read(file, InlineDocumentType.WORKBOOK),
        )
    }

    @Test
    fun `PowerPoint follows the presentation order instead of ZIP order`() {
        val file = document(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <p:sldIdLst><p:sldId id="256" r:id="rId2"/><p:sldId id="257" r:id="rId1"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to """
                <Relationships>
                    <Relationship Id="rId1" Target="slides/slide1.xml"/>
                    <Relationship Id="rId2" Target="slides/slide2.xml"/>
                </Relationships>
            """.trimIndent(),
            "ppt/slides/slide1.xml" to slide("Summary"),
            "ppt/slides/slide2.xml" to slide("Introduction"),
        )
        assertEquals(
            "## Slide 1\nIntroduction\n\n## Slide 2\nSummary",
            OfficeAttachmentText.read(file, InlineDocumentType.PRESENTATION),
        )
    }

    @Test(expected = IOException::class)
    fun `Office XML document type declarations are rejected`() {
        val file = document("word/document.xml" to "<!DOCTYPE document []><document/>")
        OfficeAttachmentText.read(file, InlineDocumentType.WORD)
    }

    private fun slide(text: String) = """
        <slide xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
            <a:p><a:r><a:t>$text</a:t></a:r></a:p>
        </slide>
    """.trimIndent()

    private fun document(vararg parts: Pair<String, String>): File {
        val file = files.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, contents) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
