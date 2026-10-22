package com.flowchat.app.data.attachment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

enum class AttachmentReadFailure {
    UnsupportedType,
    TooLarge,
    EmptyContent,
    InvalidImage,
    ImageDimensionsTooLarge,
    ReadFailed
}

class AttachmentReadException(
    val failure: AttachmentReadFailure,
    cause: Throwable? = null
) : IllegalArgumentException(failure.name, cause)

data class ExtractedAttachment(
    val name: String,
    val text: String = "",
    val imagePath: String? = null,
    val mimeType: String? = null
)

object AttachmentDocumentParser {
    const val MaxSourceBytes = 20 * 1024 * 1024
    const val MaxExtractedCharacters = 80_000

    val PickerMimeTypes = arrayOf(
        "image/*",
        "text/*",
        "application/json",
        "application/xml",
        "application/pdf",
        DocxMimeType,
        XlsxMimeType,
        PptxMimeType,
        OdtMimeType,
        OdsMimeType,
        OdpMimeType
    )

    fun isSupported(fileName: String, mimeType: String): Boolean {
        val extension = extensionOf(fileName)
        val normalizedMimeType = mimeType.lowercase()
        return normalizedMimeType.startsWith("text/") ||
            normalizedMimeType in SupportedMimeTypes ||
            extension in SupportedExtensions
    }

    fun extract(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        pdfTextExtractor: (ByteArray) -> String = {
            throw AttachmentReadException(AttachmentReadFailure.ReadFailed)
        }
    ): String {
        if (bytes.size > MaxSourceBytes) {
            throw AttachmentReadException(AttachmentReadFailure.TooLarge)
        }
        if (!isSupported(fileName, mimeType)) {
            throw AttachmentReadException(AttachmentReadFailure.UnsupportedType)
        }

        val extension = extensionOf(fileName)
        val extracted = try {
            when {
                extension == "pdf" || mimeType.equals("application/pdf", ignoreCase = true) ->
                    pdfTextExtractor(bytes)
                extension == "docx" || mimeType.equals(DocxMimeType, ignoreCase = true) ->
                    extractDocx(bytes)
                extension == "xlsx" || mimeType.equals(XlsxMimeType, ignoreCase = true) ->
                    extractXlsx(bytes)
                extension == "pptx" || mimeType.equals(PptxMimeType, ignoreCase = true) ->
                    extractPptx(bytes)
                extension in OpenDocumentExtensions || mimeType.lowercase() in OpenDocumentMimeTypes ->
                    extractOpenDocument(bytes)
                else -> decodePlainText(bytes)
            }
        } catch (error: AttachmentReadException) {
            throw error
        } catch (error: Exception) {
            throw AttachmentReadException(AttachmentReadFailure.ReadFailed, error)
        }

        val normalized = extracted
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) {
            throw AttachmentReadException(AttachmentReadFailure.EmptyContent)
        }
        return if (normalized.length <= MaxExtractedCharacters) {
            normalized
        } else {
            normalized.take(MaxExtractedCharacters).trimEnd() + "\n\n<content-truncated />"
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val documentXml = readZipEntries(bytes) { name -> name == "word/document.xml" }
            .getValue("word/document.xml")
        return extractBlockText(parseXml(documentXml), setOf("p"))
    }

    private fun extractXlsx(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let(::parseXml)
            ?.elements("si")
            ?.map(::inlineText)
            .orEmpty()
        val sheets = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy(::numericSuffix)
        return sheets.mapIndexedNotNull { index, name ->
            val rows = parseXml(entries.getValue(name)).elements("row").mapNotNull { row ->
                val values = row.childElements("c").map { cell ->
                    when (cell.getAttribute("t")) {
                        "s" -> cell.firstText("v")?.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                        "inlineStr" -> inlineText(cell)
                        else -> cell.firstText("v").orEmpty()
                    }
                }
                values.joinToString("\t").trimEnd().takeIf { it.isNotBlank() }
            }
            rows.takeIf { it.isNotEmpty() }?.joinToString(
                separator = "\n",
                prefix = "Sheet ${index + 1}\n"
            )
        }.joinToString("\n\n")
    }

    private fun extractPptx(bytes: ByteArray): String {
        val entries = readZipEntries(bytes) { name ->
            name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
        }
        return entries.keys
            .sortedBy(::numericSuffix)
            .mapIndexedNotNull { index, name ->
                val document = parseXml(entries.getValue(name))
                val paragraphs = extractBlockText(document, setOf("p"))
                    .takeIf { it.isNotBlank() }
                    ?: document.elements("t")
                        .map { it.textContent.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                paragraphs.takeIf { it.isNotBlank() }?.let { "Slide ${index + 1}\n$it" }
            }
            .joinToString("\n\n")
    }

    private fun extractOpenDocument(bytes: ByteArray): String {
        val contentXml = readZipEntries(bytes) { name -> name == "content.xml" }
            .getValue("content.xml")
        return extractBlockText(parseXml(contentXml), setOf("h", "p", "table-row"))
    }

    private fun decodePlainText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) ->
                bytes.copyOfRange(3, bytes.size).toString(StandardCharsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) ->
                bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) ->
                bytes.copyOfRange(2, bytes.size).toString(StandardCharsets.UTF_16BE)
            else -> decodeUtf8OrGb18030(bytes)
        }.also { text ->
            if ('\u0000' in text) {
                throw AttachmentReadException(AttachmentReadFailure.UnsupportedType)
            }
        }
    }

    private fun decodeUtf8OrGb18030(bytes: ByteArray): String {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { bytes.toString(charset("GB18030")) }
    }

    private fun readZipEntries(
        bytes: ByteArray,
        include: (String) -> Boolean
    ): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalUncompressedBytes = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && include(name)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalUncompressedBytes += count
                        if (totalUncompressedBytes > MaxOfficeXmlBytes) {
                            throw AttachmentReadException(AttachmentReadFailure.TooLarge)
                        }
                        output.write(buffer, 0, count)
                    }
                    entries[name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        if (entries.isEmpty()) {
            throw AttachmentReadException(AttachmentReadFailure.ReadFailed)
        }
        return entries
    }

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { isXIncludeAware = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun extractBlockText(document: Document, blockNames: Set<String>): String {
        val blocks = mutableListOf<String>()
        fun visit(node: Node) {
            if (node is Element && node.localNameOrNodeName() in blockNames) {
                inlineText(node).trim().takeIf { it.isNotBlank() }?.let(blocks::add)
                return
            }
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(document.documentElement)
        return blocks.joinToString("\n")
    }

    private fun inlineText(element: Element): String {
        val output = StringBuilder()
        fun visit(node: Node) {
            if (node.nodeType == Node.TEXT_NODE) {
                output.append(node.nodeValue.orEmpty())
                return
            }
            when (node.localNameOrNodeName()) {
                "t" -> output.append(node.textContent)
                "tab" -> output.append('\t')
                "br", "cr", "line-break" -> output.append('\n')
                else -> {
                    val children = node.childNodes
                    for (index in 0 until children.length) visit(children.item(index))
                }
            }
        }
        visit(element)
        return output.toString()
    }

    private fun Document.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childElements(localName: String): List<Element> {
        val children = childNodes
        return (0 until children.length).mapNotNull { index ->
            (children.item(index) as? Element)?.takeIf { it.localNameOrNodeName() == localName }
        }
    }

    private fun Element.firstText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length == 0) null else nodes.item(0).textContent.trim()
    }

    private fun Node.localNameOrNodeName(): String = localName ?: nodeName.substringAfter(':')

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    private fun numericSuffix(name: String): Int =
        Regex("(\\d+)(?=\\.xml$)").find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private const val MaxOfficeXmlBytes = 32 * 1024 * 1024
    private const val DocxMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XlsxMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val PptxMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val OdtMimeType = "application/vnd.oasis.opendocument.text"
    private const val OdsMimeType = "application/vnd.oasis.opendocument.spreadsheet"
    private const val OdpMimeType = "application/vnd.oasis.opendocument.presentation"

    private val OpenDocumentExtensions = setOf("odt", "ods", "odp")
    private val OpenDocumentMimeTypes = setOf(OdtMimeType, OdsMimeType, OdpMimeType)
    private val SupportedMimeTypes = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/pdf",
        DocxMimeType,
        XlsxMimeType,
        PptxMimeType,
        OdtMimeType,
        OdsMimeType,
        OdpMimeType
    )
    private val SupportedExtensions = setOf(
        "txt", "md", "markdown", "json", "xml", "csv", "tsv", "yaml", "yml",
        "kt", "kts", "java", "js", "ts", "py", "html", "css", "toml", "ini", "log",
        "pdf", "docx", "xlsx", "pptx", "odt", "ods", "odp"
    )
}
