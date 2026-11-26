package com.flowchat.app.data.workspace

import com.flowchat.app.data.network.toOpenAiRequest
import com.flowchat.app.domain.chat.ScreenObservationContext
import com.flowchat.app.domain.chat.ScreenVisionContext
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatRequestMessage
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.domain.workspace.*
import java.io.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import com.flowchat.app.domain.chat.TaskExecutionHistory
import com.flowchat.app.domain.model.ChatToolCall

class WorkspaceReadingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val location = WorkspaceLocation("content://test/tree/reading", "reading")
    private val root by lazy { temporary.newFolder("source") }
    private val files by lazy { Sources(root) }
    private val reader by lazy { WorkspaceReader(files) }
    private fun put(name: String, text: String) = put(name, text.toByteArray())
    private fun put(name: String, bytes: ByteArray) = File(root, name).apply { writeBytes(bytes) }
    private fun error(code: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected $code, got $failure", failure is WorkspaceReadFailure)
        assertEquals(code, (failure as WorkspaceReadFailure).code)
    }

    @Test fun oldFullReadFailsButLargeHeadMiddleAndTailAreActuallyBounded() {
        val original = ByteArray(6_500_000) { 'x'.code.toByte() }
        "#!/system/bin/sh\n# synthetic inspection only\n".toByteArray().copyInto(original)
        "CPU=arm64-v8a; Android=14; model=test-only\n".toByteArray().copyInto(original, 3_200_000)
        "末尾核查：并不证明当前手机兼容".toByteArray().copyInto(original, original.size - 48)
        put("analysis.sh", original)
        val engine = WorkspaceEngine(files, temporary.newFolder("commands"), temporary.newFolder("journal"))
        assertNotNull(runCatching { engine.read(location, "analysis.sh") }.exceptionOrNull())
        files.wholeReads = 0
        for (start in listOf(0L, 3_200_000L, 6_499_940L)) {
            val range = engine.readRange(location, "analysis.sh", start, 512)
            assertArrayEquals(original.copyOfRange(range.start.toInt(), range.end.toInt()), requireNotNull(range.text).toByteArray())
            assertTrue(range.sourceBytesRead <= 516)
        }
        assertEquals(0, files.wholeReads)
        assertTrue(files.bytesRead < 1600)
    }

    @Test fun textOutputLimitNeverSkipsConsumedButUnreturnedBytes() {
        val text = "a中文😀无换行".repeat(5000)
        put("long.txt", text)
        val combined = StringBuilder(); var next = 0L; var pages = 0
        do {
            val range = reader.range(location, "long.txt", next, 65536)
            assertEquals(next, range.start); assertTrue(range.text!!.length <= 12000)
            combined.append(range.text); assertTrue(range.end > next)
            assertEquals(range.text.toByteArray().size.toLong(), range.end - range.start)
            next = range.end; pages++
            assertTrue(pages < 30)
            if (range.eof) break
        } while (true)
        assertEquals(text, combined.toString()); assertTrue(pages > 1)
        assertEquals(0, files.wholeReads)
    }

    @Test fun everyUtf8OffsetReturnsWholeCharactersAndCorrectActualRange() {
        val original = "A中😀文Z".toByteArray(); put("utf8", original)
        for (offset in 0..original.size) {
            val result = reader.range(location, "utf8", offset.toLong(), 4)
            assertNotNull(result.text)
            assertTrue(result.start <= offset)
            assertArrayEquals(original.copyOfRange(result.start.toInt(), result.end.toInt()), result.text!!.toByteArray())
            if (offset < original.size) assertTrue(result.end > offset)
            else assertTrue(result.eof)
        }
    }

    @Test fun binaryInvalidUtf8EmptyAndNoNewlineHaveExplicitFiniteResults() {
        put("elf", byteArrayOf(0x7f, 69, 76, 70) + ByteArray(1000))
        val binary = reader.range(location, "elf", maxBytes = 65536)
        assertNull(binary.text); assertEquals("elf", binary.formatHint)
        assertEquals(512L, binary.end); assertEquals(1024, binary.hex!!.length); assertFalse(binary.eof)
        put("broken", byteArrayOf(65, 0xff.toByte(), 66))
        assertEquals("41ff42", reader.range(location, "broken").hex)
        put("empty", ""); val empty = reader.range(location, "empty")
        assertEquals("", empty.text); assertEquals(0L, empty.end); assertTrue(empty.eof)
        put("text", "中文结尾"); assertEquals("中文结尾", reader.range(location, "text").text)
        assertTrue(reader.range(location, "text").eof)
    }

    @Test fun changedSourceRejectsBeforeAndDuringReadWithoutWholeFileHashes() {
        put("file", "initial")
        val old = reader.source(location, "file")
        files.generation++
        error("source_changed") { reader.range(location, "file", expected = old.version) }
        files.duringRead = { files.generation++ }
        error("source_changed") { reader.range(location, "file") }
        files.duringRead = null; files.versionAvailable = false
        val weak = reader.range(location, "file")
        assertNull(weak.source.version); assertEquals("unavailable", weak.source.versionConfidence)
        assertEquals(0, files.wholeReads)
    }

    @Test fun nonSeekSourceReportsStreamingCostAndCancellationClosesTheHandle() {
        put("stream", "x".repeat(100_000) + "中文末尾"); files.streaming = true
        val range = reader.range(location, "stream", 100_000, 32)
        assertEquals("中文末尾", range.text); assertEquals("stream_skip", range.accessMode)
        assertEquals(99_997L, range.skippedBytes); assertTrue(range.eof)
        var checks = 0; val priorClosed = files.closed
        val failure = runCatching { reader.range(location, "stream", 90_000, 32, checkCancelled = {
            if (++checks == 4) throw CancellationException("user stopped")
        }) }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(priorClosed + 1, files.closed)
        error("stream_skip_limit") { WorkspaceReader.skip(ByteArrayInputStream(byteArrayOf()), WorkspaceReader.MaxStreamSkip + 1) {} }
    }

    @Test fun byteReadJsonDistinguishesReturnedCoverageFromStreamPrefixAndRemainingSuffix() {
        put("stream", "x".repeat(200_000) + "中文末尾")
        files.streaming = true
        val middle = WorkspaceManager.rangeJson(reader.range(location, "stream", 9_000, 9_000))
        assertEquals(8_997L, middle.getValue("skipped_bytes").jsonPrimitive.long)
        assertEquals(9_000L, middle.getValue("byte_start").jsonPrimitive.long)
        assertEquals(18_000L, middle.getValue("byte_end_exclusive").jsonPrimitive.long)
        assertEquals(9_000L, middle.getValue("bytes_returned").jsonPrimitive.long)
        assertEquals(9_004L, middle.getValue("source_bytes_read").jsonPrimitive.long)
        assertEquals("x".repeat(9_000), middle.getValue("text").jsonPrimitive.content)
        assertEquals("contiguous", middle.getValue("range_coverage").jsonPrimitive.content)
        assertEquals(182_012L, middle.getValue("source_bytes_after_range").jsonPrimitive.long)
        assertEquals(18_000L, middle.getValue("next_byte_offset").jsonPrimitive.long)
        assertTrue(middle.getValue("truncated").jsonPrimitive.boolean)

        val readTool = WorkspaceToolDefinitions.attach(
            ChatRequest(model = "deepseek-flash", messages = listOf(ChatRequestMessage(role = "system", content = "")),
                temperature = 0.0, topP = 1.0, maxTokens = 8192),
            location, commandsEnabled = false
        ).tools.single { it.name == WorkspaceToolDefinitions.Read }
        assertTrue(readTool.description.contains("source_bytes_read is the total raw bytes buffered from that position"))
        assertTrue(readTool.description.contains("including bytes_returned"))
        assertTrue(readTool.description.contains("not separate overhead above bytes_returned"))

        val tail = WorkspaceManager.rangeJson(reader.range(location, "stream", 199_997, 15))
        assertEquals("xxx中文末尾", tail.getValue("text").jsonPrimitive.content)
        assertEquals("contiguous", tail.getValue("range_coverage").jsonPrimitive.content)
        assertEquals(18L, tail.getValue("source_bytes_read").jsonPrimitive.long)
        assertEquals(0L, tail.getValue("source_bytes_after_range").jsonPrimitive.long)
        assertFalse(tail.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(tail.getValue("whole_file_returned").jsonPrimitive.boolean)
        assertFalse("next_byte_offset" in tail)
    }

    @Test fun searchFindsAcrossChunksAndContinuationWithBoundedContexts() {
        val token = "适配架构：arm64-v8a"
        put("search", "x".repeat(16380) + token + "y".repeat(19000) + token + "tail")
        val first = reader.search(location, "search", token, maxScanBytes = 16390)
        assertTrue(first.hits.isEmpty()); assertFalse(first.eof); assertNotNull(first.nextOffset)
        val second = reader.search(location, "search", token, first.nextOffset!!, 65536)
        assertEquals(2, second.hits.size); assertTrue(second.eof)
        assertEquals(16380L, second.hits[0].start)
        assertTrue(second.hits.all { it.context.text!!.contains(token) })
        assertEquals(0, files.wholeReads)
        put("overlap", "aaaaa")
        val a = reader.search(location, "overlap", "aaa", maxResults = 1)
        val b = reader.search(location, "overlap", "aaa", a.nextOffset!!)
        assertEquals(listOf(0L), a.hits.map { it.start }); assertEquals(listOf(1L, 2L), b.hits.map { it.start })
    }

    @Test fun permissionAndNonprogressAreErrorsAndInvalidBoundsNeverOpenSources() {
        put("file", "data"); files.permitted = false
        assertTrue(runCatching { reader.range(location, "file") }.exceptionOrNull() is SecurityException)
        files.permitted = true; val before = files.opened
        for (action in listOf<() -> Unit>(
            { reader.range(location, "file", -1) }, { reader.range(location, "file", maxBytes = 65537) },
            { reader.search(location, "file", "") }, { reader.search(location, "file", "abc", maxScanBytes = 2) }))
            assertNotNull(runCatching(action).exceptionOrNull())
        assertEquals(before, files.opened)
        files.noProgress = true
        error("source_no_progress") { reader.range(location, "file") }
        assertEquals(files.opened, files.closed)
    }

    @Test fun literalSearchEvidenceDoesNotUpgradeSamplesOrKeywordAbsenceToSemanticCoverage() {
        val bytes = ByteArray(6_500_000) { 'x'.code.toByte() }
        "#!/system/bin/sh\n# Synthetic installer description\n".toByteArray().copyInto(bytes)
        "CPU=arm64-v8a;\n".toByteArray().copyInto(bytes, 3_200_000)
        put("analysis.sh", bytes)
        fun search(query: String, offset: Long = 0, budget: Int = 6_500_000, limit: Int = 8) =
            WorkspaceManager.searchJson(reader.search(location, "analysis.sh", query, offset, budget, limit), query, limit)
        val partial = search("CPU", budget = 2_000_000)
        assertEquals(0, partial.getValue("match_count").jsonPrimitive.int)
        assertFalse(partial.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
        assertEquals("not_found_in_scanned_range_only", partial.getValue("query_status").jsonPrimitive.content)
        assertEquals("scan_limit", partial.getValue("search_stop_reason").jsonPrimitive.content)
        val declaration = search("CPU", partial.getValue("next_byte_offset").jsonPrimitive.long)
        assertEquals("CPU", declaration.getValue("query").jsonPrimitive.content)
        assertEquals(1, declaration.getValue("match_count").jsonPrimitive.int)
        assertTrue(declaration.getValue("eof").jsonPrimitive.boolean)
        assertFalse(declaration.getValue("whole_file_scanned").jsonPrimitive.boolean)
        assertTrue(declaration.getValue("hits").jsonArray.single().jsonObject.getValue("context").jsonObject.getValue("text").jsonPrimitive.content.contains("CPU=arm64-v8a"))
        val install = search("install")
        assertEquals(1, install.getValue("match_count").jsonPrimitive.int)
        assertEquals("found", install.getValue("query_status").jsonPrimitive.content)
        assertFalse(install.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
        val noCommand = search("echo")
        assertTrue(noCommand.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
        assertEquals("echo", noCommand.getValue("query").jsonPrimitive.content)
        assertTrue(noCommand.getValue("conclusion_scope").jsonPrimitive.content.contains("not a syntax"))
        val missingPrefix = search("#!/", 6_499_488)
        assertTrue(missingPrefix.getValue("eof").jsonPrimitive.boolean)
        assertFalse(missingPrefix.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
        val tail = WorkspaceManager.rangeJson(reader.range(location, "analysis.sh", 6_499_488, 512))
        assertTrue(tail.getValue("eof").jsonPrimitive.boolean)
        assertFalse(tail.getValue("whole_file_returned").jsonPrimitive.boolean)
        assertEquals(0, files.wholeReads)
    }

    @Test fun literalMissesKeepDependencyAbiUnknownInLaterModelRequests() {
        val baseline = ByteArray(6_500_000) { 'x'.code.toByte() }
        "#!/system/bin/sh\n".toByteArray().copyInto(baseline)
        "CPU=arm64-v8a;\n".toByteArray().copyInto(baseline, 3_200_000)
        "Compatibility: Android 14 only.\n".toByteArray().copyInto(baseline, 6_499_850)
        val withExternalCommand = baseline.copyOf()
        // Synthetic input only. The command is outside the samples and the eight queried literals.
        "; /opt/floryx-example/bin/vendor_probe; ".toByteArray().copyInto(withExternalCommand, 1_600_000)
        val provider = ProviderConfig(displayName = "DeepSeek", baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-flash")
        val initial = WorkspaceToolDefinitions.attach(ChatRequest(
            model = "deepseek-flash",
            messages = listOf(ChatRequestMessage("user", "Inspect the file's format and phone compatibility.")),
            temperature = 0.0, topP = 1.0, maxTokens = 8192
        ), location, commandsEnabled = true)
        val queries = listOf("curl", "busybox", "getprop", "python", "=", " the ", "Android", "arm64")
        val scanResults = listOf(baseline, withExternalCommand).map { bytes ->
            val file = put("analysis.sh", bytes)
            val path = file.name
            val preview = reader.range(location, path, maxBytes = 512)
            val stat = WorkspaceManager.statJson(preview.source, preview, reader.inspectionHints(location, path))
            val statCall = ChatToolCall("stat", WorkspaceToolDefinitions.Stat, buildJsonObject { put("path", path) }.toString())
            val expectedResults = mutableMapOf(statCall.id to stat)
            var request = initial.copy(messages = initial.messages + listOf(
                ChatRequestMessage("assistant", toolCalls = listOf(statCall)),
                ChatRequestMessage("tool", stat.toString(), toolCallId = statCall.id)
            ))
            queries.chunked(4).flatMap { batch ->
                val calls = batch.map { query -> ChatToolCall("search-${queries.indexOf(query)}", WorkspaceToolDefinitions.Search,
                    buildJsonObject { put("path", path); put("query", query); put("max_scan_bytes", bytes.size) }.toString()) }
                val results = batch.map { query ->
                    WorkspaceManager.searchJson(reader.search(location, path, query, maxScanBytes = bytes.size), query, 8)
                }
                val responses = calls.zip(results).map { (call, result) ->
                    expectedResults[call.id] = result
                    ChatRequestMessage("tool", result.toString(), toolCallId = call.id)
                }
                request = ScreenObservationContext.referenceRepeatedControls(ScreenVisionContext.retainRecent(
                    request.copy(messages = request.messages + ChatRequestMessage("assistant", toolCalls = calls) + responses)
                ))
                val payload = Json.parseToJsonElement(Json.encodeToString(request.toOpenAiRequest(provider))).jsonObject
                val sent = payload.getValue("messages").jsonArray.map { it.jsonObject }
                    .filter { it.getValue("role").jsonPrimitive.content == "tool" }
                    .associate { it.getValue("tool_call_id").jsonPrimitive.content to
                        Json.parseToJsonElement(it.getValue("content").jsonPrimitive.content).jsonObject }
                assertEquals(expectedResults, sent)
                val evidence = sent.getValue(statCall.id).getValue("compatibility_evidence").jsonObject
                assertEquals("not_analyzed", evidence.getValue("external_commands_and_libraries_abi").jsonPrimitive.content)
                assertEquals("not_established", evidence.getValue("abi_compatibility").jsonPrimitive.content)
                assertTrue(results.all { it.getValue("whole_file_scanned").jsonPrimitive.boolean })
                results.map { it.getValue("match_count").jsonPrimitive.int }
            }
        }
        assertEquals(listOf(0, 0, 0, 0, 1, 0, 1, 1), scanResults.first())
        assertEquals(scanResults.first(), scanResults.last())
    }

    @Test fun resultLimitAndCaseSensitivityRemainExplicitWithoutLosingContinuationHits() {
        put("matches", "CPU=arm\ncpu=other\nCPU=x86\n")
        val first = reader.search(location, "matches", "CPU", maxResults = 1)
        val result = WorkspaceManager.searchJson(first, "CPU", 1)
        assertEquals("result_limit", result.getValue("search_stop_reason").jsonPrimitive.content)
        assertFalse(result.getValue("whole_file_scanned").jsonPrimitive.boolean)
        val last = reader.search(location, "matches", "CPU", first.nextOffset!!)
        assertEquals(listOf(0L, 18L), (first.hits + last.hits).map { it.start })
        assertEquals("literal_case_sensitive", result.getValue("match_mode").jsonPrimitive.content)
        put("small", "complete")
        assertTrue(WorkspaceManager.rangeJson(reader.range(location, "small")).getValue("whole_file_returned").jsonPrimitive.boolean)
        put("empty", "")
        val empty = WorkspaceManager.searchJson(reader.search(location, "empty", "CPU"), "CPU", 8)
        assertTrue(empty.getValue("whole_file_scanned").jsonPrimitive.boolean)
        assertTrue(empty.getValue("query_absent_in_whole_file").jsonPrimitive.boolean)
    }

    @Test fun boundedInspectionObtainsRequirementsOutsideTheHeaderWithoutWholeFileReads() {
        val bytes = ByteArray(6_500_000) { 'x'.code.toByte() }
        "#!/system/bin/sh\n".toByteArray().copyInto(bytes)
        "CPU=arm64-v8a; validation-device=synthetic-only;\n".toByteArray().copyInto(bytes, 3_200_000)
        "Compatibility: Android 14 only; kernel unverified\n".toByteArray().copyInto(bytes, 6_499_850)
        put("requirements", bytes)
        val hints = reader.inspectionHints(location, "requirements")
        assertTrue(hints.eof); assertEquals(6_500_000L, hints.scannedBytes)
        val architecture = hints.hints.single { it.category == "architecture" }
        assertEquals(3_200_000L, architecture.offset)
        assertTrue(architecture.context.text!!.contains("CPU=arm64-v8a"))
        assertTrue(hints.hints.any { it.category == "platform" && it.context.text!!.contains("Android 14 only") })
        assertTrue(hints.hints.all { it.context.end-it.context.start <= 256 })
        assertEquals(0, files.wholeReads)
        val partial = reader.inspectionHints(location, "requirements", maxScanBytes = 2_000_000)
        assertFalse(partial.eof); assertEquals(2_000_000L, partial.scannedBytes)
        assertFalse(partial.hints.any { it.category == "architecture" })
    }

    @Test fun completeSmallShellPreviewCanAnswerCompatibilityWithoutAnotherScan() {
        val path = "small-check.sh"
        put(path, """
            #!/system/bin/sh
            # Synthetic inspection specimen, never execute.
            # CPU: arm64-v8a. Android support: 14 only. Kernel requirement: 5.10.
            # No validation for V2302A or Android 16. These declarations cannot prove compatibility.
            exit 0
        """.trimIndent() + "\n")
        val preview = reader.range(location, path, maxBytes = 512)
        val stat = WorkspaceManager.statJson(preview.source, preview, hints = null)
        assertEquals(231L, preview.end)
        assertTrue(stat.getValue("preview").jsonObject.getValue("whole_file_returned").jsonPrimitive.boolean)
        assertFalse("inspection_hints" in stat)

        val inspection = WorkspaceCompatibilityInspection(location)
        inspection.observe(ChatToolCall("stat", WorkspaceToolDefinitions.Stat, """{"path":"$path"}"""), stat.toString(), true)
        assertFalse(inspection.canAnswer)
        inspection.observe(ChatToolCall("phone", "terminal_status", "{}"), """
            {"phone":{"source":"current_android_app_Build_and_os_version","model":"V2302A","android_release":"16",
            "supported_abis":["arm64-v8a"],"kernel_release":"5.15.1975.15.197-@Coolpak@Kugouzei_NB_LKM",
            "system_shells":{"source":"app_uid_filesystem_metadata","execution_tested":false,"paths":[
            {"path":"/system/bin/sh","entry_status":"present","target_status":"present","executable_access_for_app_uid":true},
            {"path":"/system/bin/mksh","entry_status":"missing"}]}}}
        """.trimIndent(), true)
        assertTrue("The complete preview and phone facts should enable the answer", inspection.canAnswer)
        val arguments = """{"path":"$path","expected_source_version":"${preview.source.version}"}"""
        val answer = requireNotNull(inspection.answer(arguments, chinese = true)).text
        for (fact in listOf("Shell 文本", "231 字节", "Android 14，与手机 Android 16 冲突", "V2302A 未经验证",
            "Android 16 未经验证", "arm64-v8a 与手机支持的 ABI 字面匹配", "内核要求 5.10",
            "5.15.1975.15.197-@Coolpak@Kugouzei_NB_LKM", "版本约束和内核兼容性均未确定",
            "/system/bin/sh 存在，应用 UID 有执行权限", "文件的 ABI 兼容性未知", "实际运行情况未验证")) {
            assertTrue(fact, fact in answer)
        }
        assertFalse("mksh" in answer)
        val english = requireNotNull(inspection.answer(arguments, chinese = false)).text
        assertTrue("kernel requirement of 5.10" in english)
        assertTrue("version constraint and kernel compatibility remain undetermined" in english)
        assertEquals(0, files.wholeReads)
    }

    @Test fun inspectionMarkersCrossChunksFoldAsciiAndRetainOnlyBoundedContexts() {
        put("requirements", "x".repeat(16383) + "cPu=aarch64\n" + "x".repeat(250) + "CPU=x86\n" + "x".repeat(250) + "CPU=other\n")
        val hints = reader.inspectionHints(location, "requirements")
        val architectures = hints.hints.filter { it.category == "architecture" }
        assertEquals(2, architectures.size)
        assertEquals(16383L, architectures.first().offset)
        assertTrue(architectures.first().context.text!!.contains("cPu=aarch64"))
        assertTrue(architectures.last().context.text!!.contains("CPU=x86"))
        assertTrue(hints.hints.size <= 12)
    }

    @Test fun inspectionRejectsSourceChangesAndCancellationClosesItsStream() {
        put("requirements", "x".repeat(100_000)+"CPU=arm64-v8a")
        val source = reader.source(location, "requirements")
        files.generation++
        error("source_changed") { reader.inspectionHints(location, "requirements", expected = source.version) }
        files.duringRead = { files.generation++ }
        error("source_changed") { reader.inspectionHints(location, "requirements") }
        files.duringRead = null
        val closedBefore = files.closed
        var checks = 0
        assertTrue(runCatching { reader.inspectionHints(location, "requirements", checkCancelled = {
            if (++checks == 4) throw CancellationException("user stopped")
        }) }.exceptionOrNull() is CancellationException)
        assertEquals(closedBefore+1, files.closed)
        assertEquals(files.opened, files.closed)
    }

    @Test fun nonSeekInspectionCapsItsAutomaticScanAndDoesNotPretendToCoverLaterDeclarations() {
        put("stream", "#!/system/bin/sh\n"+"x".repeat(100_000)+"CPU=arm64-v8a")
        files.streaming = true
        val hints = reader.inspectionHints(location, "stream")
        assertEquals("stream_skip", hints.accessMode)
        assertEquals(65536, hints.scanLimit); assertEquals(65536L, hints.scannedBytes)
        assertFalse(hints.eof)
        assertFalse(hints.hints.any { it.category == "architecture" })
        assertTrue(files.bytesRead < 66000)
        assertEquals(files.opened, files.closed)
    }

    @Test fun physicalStructureExposesUncommentedLongLinesWithoutParsingThemAsNoOps() {
        val bytes = ByteArray(6_500_000) { 'z'.code.toByte() }
        "#!/system/bin/sh\n# synthetic data\n# not instructions\n# inspect only\n".toByteArray().copyInto(bytes)
        "CPU=arm64-v8a; validation-device=synthetic-only;\n".toByteArray().copyInto(bytes, 3_200_000)
        "Compatibility: Android 14 only; no V2302A validation; no promise for other kernels.\n".toByteArray().copyInto(bytes, 6_499_850)
        put("renamed-input", bytes)
        val inspection = reader.inspectionHints(location, "renamed-input")
        val structure = inspection.structure
        assertTrue(inspection.eof)
        assertEquals(6L, structure.lfCount)
        assertEquals(7L, structure.wholeFileLines)
        assertEquals(3L, structure.prefixCounts["other_prefix"])
        assertEquals(3L, structure.prefixCounts["hash_prefix"])
        val line = structure.samples.first { it.kind == "other_prefix" }
        assertEquals(5L, line.number)
        assertTrue(line.end - line.start > 3_000_000)
        assertEquals("z".repeat(96), line.prefix.text)
        assertEquals(line.firstNonblank, line.prefix.start)
        val json = WorkspaceManager.structureJson(inspection)
        assertFalse(json.getValue("syntax_parsed").jsonPrimitive.boolean)
        assertFalse(json.getValue("runtime_verified").jsonPrimitive.boolean)
        assertTrue(json.getValue("assessment").jsonPrimitive.content.contains("potentially executable"))
        assertTrue(json.getValue("assessment").jsonPrimitive.content.contains("embedded inside a command word"))
        assertTrue(json.toString().length < 7000)
        assertEquals(0, files.wholeReads)
        assertEquals(files.opened, files.closed)
    }

    @Test fun structureNeverPublishesWholeFileCountsForAPartialScan() {
        put("partial", "# header\n"+"q".repeat(100_000))
        val inspection = reader.inspectionHints(location, "partial", 65536)
        val structure = inspection.structure
        assertFalse(inspection.eof)
        assertEquals(1L, structure.lfCount)
        assertEquals(2L, structure.observedLines)
        assertNull(structure.wholeFileLines)
        assertFalse(structure.samples.last().lfTerminated)
        val json = WorkspaceManager.structureJson(inspection)
        assertFalse("whole_file_physical_lines" in json)
        assertFalse(json.getValue("whole_file_scanned").jsonPrimitive.boolean)
        assertTrue(files.bytesRead <= 65536)
    }

    @Test fun prefixCategoriesDoNotClaimToParseMultilineShellSyntaxOrHideLaterInput() {
        put("quoted", "#!/system/bin/sh\n"+"# header\n".repeat(100)+"cat <<EOF\n# here-document data\nEOF\n\t  \n")
        val structure = reader.inspectionHints(location, "quoted").structure
        assertEquals(101L, structure.prefixCounts["hash_prefix"])
        assertEquals(2L, structure.prefixCounts["other_prefix"])
        assertEquals(1L, structure.prefixCounts["space_or_tab_only"])
        assertEquals(listOf("cat <<EOF", "EOF"), structure.samples.filter { it.kind == "other_prefix" }.map { it.prefix.text })
        assertEquals(5, structure.samples.size)
        assertTrue(structure.samples.all { it.prefix.end - it.prefix.start <= 96 })
        assertTrue(WorkspaceManager.structureJson(reader.inspectionHints(location, "quoted")).getValue("scope").jsonPrimitive.content.contains("not a parsed shell comment"))
    }

    @Test fun structurePreservesCaseAndUsesHexWhenAPrefixCutsThroughUtf8() {
        put("encoding", "\tUPPER=Z"+"中".repeat(200)+"\r\n\r\n")
        val inspection = reader.inspectionHints(location, "encoding")
        val structure = inspection.structure
        assertEquals(2L, structure.wholeFileLines)
        assertEquals(2L, structure.prefixCounts["other_prefix"])
        val first = structure.samples.first().prefix
        assertEquals(1L, first.start)
        assertNull(first.text)
        assertTrue(first.hex!!.startsWith("55505045523d"))
        assertEquals("\r", structure.samples.last().prefix.text)
        assertEquals(609L, structure.longestObservedLineBytes)
    }

    @Test fun emptyAndLfTerminatedSourcesDoNotInventAnExtraPhysicalLine() {
        put("empty-structure", "")
        assertEquals(0L, reader.inspectionHints(location, "empty-structure").structure.wholeFileLines)
        put("terminated", "# comment\n\n")
        val structure = reader.inspectionHints(location, "terminated").structure
        assertEquals(2L, structure.wholeFileLines)
        assertEquals(2L, structure.lfCount)
        assertFalse(structure.prefixCounts.containsKey("other_prefix"))
    }

    @Test fun compactStatKeepsLiteralAbiSeparateFromUnanalysedDependencyAbiAndSurvivesHistory() {
        val bytes = ByteArray(6_500_000) { 'q'.code.toByte() }
        "#!/system/bin/sh\n# Synthetic installer description\n# inspect file data only\n".toByteArray().copyInto(bytes)
        "CPU=arm64-v8a; validation-device=synthetic-only;\n".toByteArray().copyInto(bytes, 3_200_000)
        "Compatibility: Android 14 only; no V2302A validation; no promise for other kernels.\n".toByteArray().copyInto(bytes, 6_499_850)
        put("another-name.sh", bytes)
        val source = reader.source(location, "another-name.sh")
        val result = WorkspaceManager.statJson(source, reader.range(location, source.path, maxBytes = 512), reader.inspectionHints(location, source.path))
        val evidence = result.getValue("compatibility_evidence").jsonObject
        assertTrue(evidence.getValue("architecture_literal_contexts").jsonPrimitive.int > 0)
        assertEquals("not_analyzed", evidence.getValue("external_commands_and_libraries_abi").jsonPrimitive.content)
        assertEquals("not_established", evidence.getValue("abi_compatibility").jsonPrimitive.content)
        val contexts = result.getValue("inspection_hints").jsonObject.getValue("hints").jsonArray.map { it.jsonObject.getValue("context").jsonObject }
        assertTrue(contexts.all { it["source"] == null && it.getValue("source_ref").jsonPrimitive.content == "stat.source" })
        assertTrue(contexts.any { it.getValue("text").jsonPrimitive.content.contains("CPU=arm64-v8a") })
        val content = result.toString()
        assertTrue("stat result characters=${content.length}", content.length < 12_000)
        val call = ChatToolCall("stat-compact", "workspace_stat", """{"path":"another-name.sh"}""")
        val saved = TaskExecutionHistory.completed(TaskExecutionHistory.pending(call), content)
        assertEquals(result, Json.parseToJsonElement(saved.result).jsonObject)
        assertEquals(content, saved.result)
        assertEquals(0, files.wholeReads)
    }

    @Test fun compactSourceReferencesCreditOnlyMatchingStatSourceAndDoNotWeakenReadValidation() {
        put("scoped", "#!/system/bin/sh\n"+"m".repeat(4000)+"CPU=arm64-v8a\n")
        val source = reader.source(location, "scoped")
        val result = WorkspaceManager.statJson(source, reader.range(location, source.path, maxBytes = 512), reader.inspectionHints(location, source.path))
        val call = ChatToolCall("references", "workspace_stat", """{"path":"scoped"}""")
        val valid = requireNotNull(WorkspaceInformationEvidence.capture(call, "qa", location, result))
        assertTrue(valid.ranges.any { it.start > 512 })
        val wrongRoot = JsonObject(result + ("source" to WorkspaceManager.sourceJson(source.copy(version = "another-version"))))
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, wrongRoot))
        val missingRoot = JsonObject(result - "source")
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, missingRoot))
        val wrongRef = Json.parseToJsonElement(result.toString().replace("stat.source", "wrong.reference")).jsonObject
        assertNull(WorkspaceInformationEvidence.capture(call, "qa", location, wrongRef))
        val read = Json.parseToJsonElement(WorkspaceManager.rangeJson(reader.range(location, source.path, maxBytes = 512)).toString()).jsonObject
        assertNotNull(WorkspaceInformationEvidence.capture(call.copy(name = "workspace_read"), "qa", location, read))
        val refOnlyRead = JsonObject((read - "source") + ("source_ref" to JsonPrimitive("stat.source")))
        assertNull(WorkspaceInformationEvidence.capture(call.copy(name = "workspace_read"), "qa", location, refOnlyRead))
    }

    @Test fun scriptOrBinaryFormatAndAbsentArchitectureMarkersCannotEstablishRuntimeAbi() {
        for ((name, bytes) in listOf("simple" to "#!/system/bin/sh\nexit 0\n".toByteArray(),
            "native" to (byteArrayOf(0x7f,69,76,70)+ByteArray(800)), "data" to "plain text".toByteArray())) {
            put(name, bytes)
            val preview = reader.range(location, name, maxBytes = 512)
            val result = WorkspaceManager.statJson(preview.source, preview, null)
            val evidence = result.getValue("compatibility_evidence").jsonObject
            assertEquals(0, evidence.getValue("architecture_literal_contexts").jsonPrimitive.int)
            assertEquals("not_established", evidence.getValue("abi_compatibility").jsonPrimitive.content)
            assertEquals("not_verified", evidence.getValue("runtime_compatibility").jsonPrimitive.content)
            assertEquals("not_checked", evidence.getValue("interpreter_availability").jsonPrimitive.content)
            assertFalse("inspection_hints" in result)
        }
    }

    @Test fun statGuidanceReflectsWholeFileVsPartialScanAndStaysBounded() {
        val bytes = ByteArray(6_500_000) { 'x'.code.toByte() }
        "#!/system/bin/sh\n".toByteArray().copyInto(bytes)
        "CPU=arm64-v8a; validation-device=synthetic-only;\n".toByteArray().copyInto(bytes, 3_200_000)
        "Compatibility: Android 14 only; no V2302A validation; no promise for other kernels.\n".toByteArray().copyInto(bytes, 6_499_850)
        put("analysis.sh", bytes)
        val source = reader.source(location, "analysis.sh")
        val preview = reader.range(location, "analysis.sh", maxBytes = 512)

        val wholeStat = WorkspaceManager.statJson(source, preview, reader.inspectionHints(location, "analysis.sh"))
        val wholeGuidance = wholeStat.getValue("inspection_guidance").jsonPrimitive.content
        assertTrue(wholeGuidance.contains("eof=true"))
        assertTrue(wholeGuidance.contains(WorkspaceToolDefinitions.AnswerCompatibility))
        assertTrue("suggested_search_limits" in wholeStat)
        assertTrue("suggested_tail_read" in wholeStat)
        assertTrue("stat result characters=${wholeStat.toString().length}", wholeStat.toString().length < 12_000)

        val partialStat = WorkspaceManager.statJson(source, preview, reader.inspectionHints(location, "analysis.sh", maxScanBytes = 2_000_000))
        val partialGuidance = partialStat.getValue("inspection_guidance").jsonPrimitive.content
        assertTrue(partialGuidance.contains("next_byte_offset"))
        assertFalse(partialGuidance.contains("eof=true"))
    }

    private class Sources(private val root: File) : WorkspaceFileAccess {
        var wholeReads = 0; var bytesRead = 0; var opened = 0; var closed = 0
        var generation = 1; var versionAvailable = true; var streaming = false; var permitted = true; var noProgress = false
        var duringRead: (() -> Unit)? = null
        override fun status(location: WorkspaceLocation) = WorkspaceStatus(if (permitted) WorkspaceAccess.ReadWrite else WorkspaceAccess.Unavailable)
        override fun stat(location: WorkspaceLocation, path: String): WorkspaceSource {
            if (!permitted) throw SecurityException("permission revoked")
            val f = File(root, path)
            return WorkspaceSource(path, f.length(), generation.toLong(), "application/octet-stream", if (versionAvailable) "${f.length()}:$generation" else null,
                if (versionAvailable) "size_mtime_weak" else "unavailable")
        }
        override fun openRead(location: WorkspaceLocation, path: String, byteOffset: Long, checkCancelled: () -> Unit): WorkspaceInput {
            val raw = File(root, path).inputStream(); opened++
            val input = object : FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    duringRead?.invoke()
                    if (noProgress) return 0
                    return super.read(b, off, len).also { if (it > 0) bytesRead += it }
                }
                override fun close() { closed++; super.close() }
            }
            return try {
                if (streaming) WorkspaceInput(input, "stream_skip", WorkspaceReader.skip(input, byteOffset, checkCancelled))
                else { raw.channel.position(byteOffset); WorkspaceInput(input, "seek") }
            } catch (error: Throwable) { input.close(); throw error }
        }
        override fun read(location: WorkspaceLocation, path: String, maxBytes: Int): ByteArray {
            wholeReads++; return File(root, path).inputStream().use { BoundedWorkspaceIo.read(it, maxBytes) }
        }
        override fun list(location: WorkspaceLocation, path: String, offset: Int, limit: Int) = error("unused")
        override fun exists(location: WorkspaceLocation, path: String) = File(root, path).exists()
        override fun write(location: WorkspaceLocation, path: String, bytes: ByteArray) = error("inspection must not write")
        override fun rename(location: WorkspaceLocation, path: String, newName: String) = error("inspection must not rename")
    }
}
