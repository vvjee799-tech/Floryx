package com.flowchat.app.domain.workspace

import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.terminal.TerminalToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** Current-turn evidence for an explicitly selected single-file, read-only compatibility answer. */
internal class WorkspaceCompatibilityInspection(val location: WorkspaceLocation) {
    data class FileSource(val path: String, val version: String)
    data class Answer(val file: FileSource, val text: String)

    private var eligible = true
    private var requestedPath: String? = null
    private var file: FileSource? = null
    private var stat: JsonObject? = null
    private var phone: JsonObject? = null
    val canAnswer: Boolean get() = eligible && stat != null && phone != null

    fun observe(call: ChatToolCall, content: String, succeeded: Boolean) {
        if (!eligible) return
        when (call.name) {
            WorkspaceToolDefinitions.Status, WorkspaceToolDefinitions.ListFiles,
            WorkspaceToolDefinitions.AnswerCompatibility -> Unit
            TerminalToolDefinitions.Status -> if (succeeded) {
                val observed = parse(content)?.obj("phone")
                    ?.takeIf { it.string("source") == "current_android_app_Build_and_os_version" }
                if (observed != null) {
                    if (phone != null && phone != observed) invalidate() else phone = observed
                }
            }
            WorkspaceToolDefinitions.Stat, WorkspaceToolDefinitions.Read, WorkspaceToolDefinitions.Search ->
                observeFile(call, parse(content), succeeded)
            else -> invalidate()
        }
    }

    private fun observeFile(call: ChatToolCall, result: JsonObject?, succeeded: Boolean) {
        val path = parse(call.arguments)?.string("path")
        if (path == null || (requestedPath != null && path != requestedPath)) {
            invalidate()
            return
        }
        requestedPath = path
        if (!succeeded) {
            if (result?.string("error") == "source_changed") invalidate()
            return
        }
        val observedSource = result?.obj("source")
        val version = observedSource?.string("source_version")
        if (result == null || observedSource?.string("path") != path || version.isNullOrBlank() ||
            (file != null && version != file?.version)) {
            invalidate()
            return
        }
        file = FileSource(path, version)
        if (call.name == WorkspaceToolDefinitions.Stat) {
            stat = result.takeIf { interpreter(it) != null && (it.obj("inspection_hints") != null || wholePreview(it) != null) }
        }
    }

    internal fun invalidate() {
        eligible = false
        file = null
        stat = null
        phone = null
    }

    fun answer(arguments: String, chinese: Boolean): Answer? {
        if (!canAnswer) return null
        val source = file ?: return null
        val args = parse(arguments) ?: return null
        if (args.keys != setOf("path", "expected_source_version") || args.string("path") != source.path ||
            args.string("expected_source_version") != source.version) return null
        val evidence = stat ?: return null
        val facts = phone ?: return null
        val shell = interpreter(evidence) ?: return null
        return Answer(source, render(evidence, facts, shell, chinese))
    }

    private fun render(stat: JsonObject, phone: JsonObject, shell: String, chinese: Boolean): String {
        // A verified EOF also terminates a declaration on a final line without a newline.
        val completeText = wholePreview(stat)?.let { "$it\n" }
        val hints = stat.obj("inspection_hints")?.array("hints").orEmpty().filterIsInstance<JsonObject>()
        fun contexts(category: String) = completeText?.let(::listOf) ?: hints.filter { it.string("category") == category }
            .mapNotNull { it.obj("context")?.string("text") }
        fun declaredVersion(category: String, pattern: Regex) = contexts(category).flatMap { context ->
            pattern.findAll(context).map { it.groupValues[1] }.toList()
        }.distinct().singleOrNull()
        val platform = contexts("platform")
        val declaration = declaredVersion("platform", androidDeclaration)
        val declaredKernel = declaredVersion("kernel", kernelDeclaration)
        val kernelRelease = phone.string("kernel_release")?.takeIf { kernelName.matches(it) }
        val release = phone.string("android_release")?.let { androidRelease.matchEntire(it)?.groupValues?.get(1) }
        val model = phone.string("model")?.takeIf { modelName.matches(it) }
        val unvalidatedModel = model?.let {
            val name = Regex.escape(it)
            val missing = Regex("""\b(?:no[ \t]+$name[ \t]+validation|no[ \t]+validation[ \t]+for[ \t]+$name)(?![\w-]|\.[\w-])""",
                RegexOption.IGNORE_CASE)
            platform.any(missing::containsMatchIn)
        } == true
        val unvalidatedRelease = release?.let {
            val missing = Regex("""\bno[ \t]+validation[ \t]+for[ \t]+(?:[\w.-]+[ \t]+or[ \t]+)?Android[ \t]+$it(?![\w-]|\.[\w-])""",
                RegexOption.IGNORE_CASE)
            platform.any(missing::containsMatchIn)
        } == true
        val architecture = contexts("architecture")
        val abi = phone.array("supported_abis").orEmpty().filterIsInstance<JsonPrimitive>()
            .filter { it.isString && abiName.matches(it.content) }.map { it.content }.distinct().filter { name ->
                architecture.any { Regex("""(?<![\w-])${Regex.escape(name)}(?![\w-])""").containsMatchIn(it) }
            }
        val size = stat.obj("source")?.long("total_bytes")?.takeIf { it >= 0 }
        val structure = stat.obj("text_structure")
        val scanEnd = structure?.long("byte_end_exclusive")
        val whole = structure?.boolean("whole_file_scanned") == true && structure.long("byte_start") == 0L &&
            size != null && scanEnd == size
        val lines = structure?.long("whole_file_physical_lines")?.takeIf { whole && it >= 0 }
        val longest = structure?.long("longest_observed_line_bytes")?.takeIf {
            it >= 0 && scanEnd != null && it <= scanEnd
        }

        return buildList {
            add(if (chinese) "文件是 Shell 文本，文件头声明使用 $shell。" else "This is Shell text with $shell in its header.")
            if (size != null) add(if (chinese) "大小 ${decimal(size)} 字节。" else "Size: ${decimal(size)} bytes.")
            if (lines != null) add(if (chinese) "全文件有 ${decimal(lines)} 个物理行。" else "The file has ${decimal(lines)} physical lines.")
            if (longest != null) add(if (chinese) {
                val scope = if (whole) "全文件最长物理行" else "已扫描范围内最长的物理行片段"
                "$scope ${decimal(longest)} 字节。"
            } else {
                val scope = if (whole) "Longest physical line" else "Longest line segment in the scanned range"
                "$scope: ${decimal(longest)} bytes."
            })
            if (declaration != null) add(androidComparison(declaration, release, chinese))
            if (declaredKernel != null) add(kernelFacts(declaredKernel, kernelRelease, chinese))
            if (unvalidatedModel) add(if (chinese) "文件片段写明 $model 未经验证。" else "The file excerpt says $model has not been validated.")
            if (unvalidatedRelease) add(if (chinese) "文件片段写明 Android $release 未经验证。"
                else "The file excerpt says Android $release has not been validated.")
            if (abi.isNotEmpty()) {
                val names = abi.joinToString(if (chinese) "、" else ", ")
                add(if (chinese) "$names 与手机支持的 ABI 字面匹配。" else "$names literally matches the phone's supported ABI list.")
            }
            add(interpreterStatus(shell, phone, chinese))
            add(if (chinese) "外部命令、库及其 ABI 尚未分析。文件的 ABI 兼容性未知，实际运行情况未验证。"
                else "External commands, libraries and their ABIs have not been analyzed. ABI compatibility and runtime behavior remain unverified.")
        }.joinToString("\n")
    }

    private fun androidComparison(declaration: String, release: String?, chinese: Boolean): String = when {
        release == null -> if (chinese) "文件片段声明仅支持 Android $declaration。手机 Android 版本未知，暂无法核对。"
            else "The file excerpt specifies Android $declaration only. The phone's Android version is unknown."
        release == declaration -> if (chinese) "文件片段声明仅支持 Android $declaration，与手机的 Android 版本字面匹配。"
            else "The file excerpt specifies Android $declaration only, a literal match with the phone's Android version."
        else -> if (chinese) "文件片段声明仅支持 Android $declaration，与手机 Android $release 冲突。"
            else "The file excerpt specifies Android $declaration only, which conflicts with the phone's Android $release."
    }

    private fun kernelFacts(declaration: String, release: String?, chinese: Boolean): String = if (chinese) {
        val observed = if (release == null) "手机内核版本未知。" else "手机报告的内核版本为 $release。"
        "文件片段声明内核要求 $declaration。${observed}该声明的版本约束和内核兼容性均未确定。"
    } else {
        val observed = if (release == null) "The phone's kernel release is unknown." else "The phone reports kernel release $release."
        "The file excerpt states a kernel requirement of $declaration. $observed The version constraint and kernel compatibility remain undetermined."
    }

    private fun interpreterStatus(path: String, phone: JsonObject, chinese: Boolean): String {
        val observation = phone.obj("system_shells")?.takeIf {
            it.string("source") == "app_uid_filesystem_metadata" && it.boolean("execution_tested") == false
        }?.array("paths").orEmpty().filterIsInstance<JsonObject>().singleOrNull { it.string("path") == path }
        return when {
            observation == null -> if (chinese) "$path 的手机元数据未知。" else "Phone metadata for $path is unknown."
            observation.string("entry_status") == "missing" ->
                if (chinese) "手机观察结果为 $path 路径缺失。" else "The phone reported $path missing."
            observation.string("observation_error") == "permission_denied" ->
                if (chinese) "$path 的元数据读取被拒绝，路径状态未知。" else "Access to $path metadata was denied. Its status is unknown."
            observation.string("entry_status") != "present" ->
                if (chinese) "$path 的路径状态未知。" else "The status of $path is unknown."
            observation.string("target_status") == "missing" ->
                if (chinese) "$path 目录项存在，目标缺失。" else "The $path entry exists and its target is missing."
            observation.string("target_observation_error") == "permission_denied" ->
                if (chinese) "$path 目录项存在，目标元数据读取被拒绝。" else "The $path entry exists. Access to its target metadata was denied."
            observation.string("target_status") != "present" ->
                if (chinese) "$path 目录项存在，目标状态未知。" else "The $path entry exists. Its target status is unknown."
            observation.boolean("executable_access_for_app_uid") == true ->
                if (chinese) "$path 存在，应用 UID 有执行权限。" else "$path exists and the app UID has execute permission."
            observation.boolean("executable_access_for_app_uid") == false ->
                if (chinese) "$path 存在，应用 UID 无执行权限。" else "$path exists and the app UID lacks execute permission."
            observation.string("access_observation_error") == "permission_denied" ->
                if (chinese) "$path 存在，执行权限探测被拒绝。" else "$path exists. Its execute permission check was denied."
            else -> if (chinese) "$path 存在，应用 UID 的执行权限未知。" else "$path exists. Execute permission for the app UID is unknown."
        }
    }

    private companion object {
        val androidDeclaration = Regex(
            """(?:Compatibility:\s*Android\s+|Android\s+support:\s*)([1-9][0-9]{0,2})\s+only(?=\s*[;.,\r\n])""",
            RegexOption.IGNORE_CASE
        )
        val kernelDeclaration = Regex(
            """\bkernel[ \t]+requirement[ \t]*:[ \t]*([0-9]+(?:\.[0-9]+)+)(?=[ \t]*(?:[;\r\n]|\.(?=[ \t\r\n])))""",
            RegexOption.IGNORE_CASE
        )
        val androidRelease = Regex("""([1-9][0-9]{0,2})(?:\.[0-9]+)*""")
        val abiName = Regex("[A-Za-z0-9_-]{1,32}")
        val modelName = Regex("[A-Za-z0-9_.-]{1,32}")
        val kernelName = Regex("[A-Za-z0-9_.+@-]{1,128}")
        val shellShebang = Regex("""^#![ \t]*(/[A-Za-z0-9_/.-]+/(?:sh|mksh))(?:[ \t\r\n]|$)""")

        fun wholePreview(stat: JsonObject): String? = stat.obj("preview")?.takeIf {
            it.boolean("whole_file_returned") == true && it.long("byte_start") == 0L
        }?.string("text")

        fun interpreter(stat: JsonObject): String? {
            val scope = stat.obj("compatibility_evidence") ?: return null
            val preview = stat.obj("preview") ?: return null
            if (scope.string("format_hint") != "script_shebang" || scope.string("external_commands_and_libraries_abi") != "not_analyzed" ||
                scope.string("abi_compatibility") != "not_established" || scope.string("runtime_compatibility") != "not_verified" ||
                preview.long("byte_start") != 0L) return null
            return shellShebang.find(preview.string("text").orEmpty())?.groupValues?.get(1)
        }

        fun decimal(value: Long): String = String.format(Locale.US, "%,d", value)
        fun parse(value: String): JsonObject? = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
        fun JsonObject.obj(key: String) = this[key] as? JsonObject
        fun JsonObject.array(key: String) = this[key] as? JsonArray
        fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    }
}
