package com.flowchat.app.data.attachment

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDocumentParserTest {
    @Test
    fun recognizesCommonDocumentFormats() {
        assertTrue(AttachmentDocumentParser.isSupported("notes.md", "text/markdown"))
        assertTrue(AttachmentDocumentParser.isSupported("report.pdf", "application/pdf"))
        assertTrue(AttachmentDocumentParser.isSupported("letter.docx", DocxMimeType))
        assertTrue(AttachmentDocumentParser.isSupported("budget.xlsx", XlsxMimeType))
        assertTrue(AttachmentDocumentParser.isSupported("slides.pptx", PptxMimeType))
        assertTrue(AttachmentDocumentParser.isSupported("draft.odt", OdtMimeType))
    }

    @Test
    fun delegatesPdfTextExtraction() {
        val source = byteArrayOf(1, 2, 3)

        val text = AttachmentDocumentParser.extract(
            fileName = "report.pdf",
            mimeType = "application/pdf",
            bytes = source,
            pdfTextExtractor = { bytes ->
                assertTrue(source.contentEquals(bytes))
                "PDF first page\nPDF second page"
            }
        )

        assertEquals("PDF first page\nPDF second page", text)
    }

    @Test
    fun extractsParagraphsFromDocx() {
        val bytes = zipOf(
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
        )

        val text = AttachmentDocumentParser.extract("letter.docx", DocxMimeType, bytes)

        assertEquals("First paragraph\nSecond paragraph", text)
    }

    @Test
    fun extractsRowsFromXlsx() {
        val bytes = zipOf(
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Name</t></si><si><t>Alice</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row>
                    <row><c><v>42</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent()
        )

        val text = AttachmentDocumentParser.extract("budget.xlsx", XlsxMimeType, bytes)

        assertEquals("Sheet 1\nName\tAlice\n42", text)
    }

    @Test
    fun preservesSparseXlsxColumnsIncludingMultiLetterReferences() {
        val bytes = zipOf(
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="2">
                    <c r="A2"><v>left</v></c><c r="C2"><v>middle</v></c>
                    <c r="AA2"><v>far</v></c><c r="AB2"><v>last</v></c>
                  </row></sheetData>
                </worksheet>
            """.trimIndent()
        )

        val text = AttachmentDocumentParser.extract("sparse.xlsx", XlsxMimeType, bytes)
        val columns = text.substringAfter('\n').split('\t')

        assertEquals(28, columns.size)
        assertEquals("left", columns[0])
        assertEquals("", columns[1])
        assertEquals("middle", columns[2])
        assertTrue(columns.subList(3, 26).all { it.isEmpty() })
        assertEquals("far", columns[26])
        assertEquals("last", columns[27])
    }

    @Test
    fun ignoresOutOfRangeXlsxColumnsWithoutExpandingOutput() {
        val bytes = zipOf(
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1">
                    <c r="A1"><v>left</v></c><c r="XFE1"><v>ignored</v></c>
                    <c r="C1"><v>right</v></c>
                  </row></sheetData>
                </worksheet>
            """.trimIndent()
        )

        assertEquals("Sheet 1\nleft\t\tright", AttachmentDocumentParser.extract("sparse.xlsx", XlsxMimeType, bytes))
    }

    @Test
    fun boundsOutputFromValidLastColumnAcrossManyRows() {
        val rows = (1..7).joinToString("") { row ->
            "<row r=\"$row\"><c r=\"XFD$row\"><v>x</v></c></row>"
        }
        val bytes = zipOf(
            "xl/worksheets/sheet1.xml" to
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>"
        )

        val text = AttachmentDocumentParser.extract("wide.xlsx", XlsxMimeType, bytes)

        assertTrue(text.length <= AttachmentDocumentParser.MaxExtractedCharacters + 24)
        assertTrue(text.endsWith("<content-truncated />"))
    }

    @Test
    fun extractsSlidesInNumericOrder() {
        val bytes = zipOf(
            "ppt/slides/slide2.xml" to slideXml("Second slide"),
            "ppt/slides/slide1.xml" to slideXml("First slide")
        )

        val text = AttachmentDocumentParser.extract("slides.pptx", PptxMimeType, bytes)

        assertEquals("Slide 1\nFirst slide\n\nSlide 2\nSecond slide", text)
    }

    @Test
    fun extractsParagraphsFromOpenDocument() {
        val bytes = zipOf(
            "content.xml" to """
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
                  <office:body><office:text>
                    <text:h>Heading</text:h>
                    <text:p>OpenDocument paragraph</text:p>
                  </office:text></office:body>
                </office:document-content>
            """.trimIndent()
        )

        val text = AttachmentDocumentParser.extract("draft.odt", OdtMimeType, bytes)

        assertEquals("Heading\nOpenDocument paragraph", text)
    }

    @Test
    fun decodesUtf16TextWithBom() {
        val body = "你好，FlowChat".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body

        val text = AttachmentDocumentParser.extract("notes.txt", "text/plain", bytes)

        assertEquals("你好，FlowChat", text)
    }

    private fun slideXml(text: String): String = """
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <p:cSld><a:t>$text</a:t></p:cSld>
        </p:sld>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val DocxMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XlsxMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PptxMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val OdtMimeType = "application/vnd.oasis.opendocument.text"
    }
}
