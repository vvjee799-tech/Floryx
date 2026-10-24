package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val phone by lazy { temporary.newFolder("phone") }
    private val commands by lazy { temporary.newFolder("commands") }
    private val journals by lazy { temporary.newFolder("journals") }
    private val access by lazy { FakeFiles(phone) }
    private val location = WorkspaceLocation("content://test/tree/project", "测试项目", "Documents/测试项目")
    private fun engine() = WorkspaceEngine(access, commands, journals)
    private fun source(path: String, text: String) = File(phone, path).apply { parentFile?.mkdirs(); writeText(text) }
    private fun staged(owner: String, path: String) = File(commands, "$owner/phone/${location.key}/$path")
    private fun fails(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("Expected rejection", error)
        assertFalse(error is AssertionError)
    }

    @Test fun textEditsVerifyRevisionsAndPreserveConcurrentChanges() {
        val original = source("中文/资料 空格.txt", "原始资料")
        val engine = engine()
        val read = engine.read(location, "中文/资料 空格.txt")
        assertEquals("原始资料", read.text)
        assertEquals(64, read.revision.length)
        original.writeText("其他应用的新内容")
        val result = engine.write("chat1", location, "中文/资料 空格.txt", "Floryx 修改", read.revision)
        assertTrue(result.conflict)
        assertEquals("其他应用的新内容", original.readText())
        assertEquals("Floryx 修改", File(phone, result.path).readText())
        assertTrue(engine.pending("chat1").isEmpty())
    }

    @Test fun successfulEditUsesOriginalPathAndMissingRevisionCreatesCopy() {
        val file = source("file.txt", "old")
        val engine = engine()
        val result = engine.write("chat1", location, "file.txt", "new", engine.read(location, "file.txt").revision)
        assertEquals("file.txt", result.path)
        assertFalse(result.conflict)
        assertEquals("new", file.readText())
        val copy = engine.write("chat1", location, "file.txt", "without revision", null)
        assertTrue(copy.conflict)
        assertEquals("new", file.readText())
    }

    @Test fun commandImportsAndDeclaredOutputsSurviveEngineRecreation() {
        source("inputs/中文.txt", "input")
        var engine = engine()
        engine.importFiles("chat1", location, listOf("inputs/中文.txt"))
        assertEquals("input", staged("chat1", "inputs/中文.txt").readText())
        engine.prepareOutputs("chat1", location, listOf("results/输出.txt"))
        staged("chat1", "results/输出.txt").writeText("result")
        engine = engine()
        assertEquals("results/输出.txt", engine.pending("chat1").single().path)
        val saved = engine.publishFiles("chat1", location, listOf("results/输出.txt")).single()
        assertFalse(saved.conflict)
        assertEquals("result", File(phone, saved.path).readText())
        assertTrue(engine.pending("chat1").isEmpty())
    }

    @Test fun failedSaveRetainsActualBytesAndRetriesToOriginalBindingAfterRestart() {
        val engine = engine()
        access.failWrites = true
        fails { engine.write("chat1", location, "result.txt", "retained result", null) }
        val restored = engine()
        val pending = restored.pending("chat1").single()
        assertEquals(location, pending.location)
        assertNotNull(pending.error)
        assertTrue(restored.pending("chat2").isEmpty())
        fails { restored.publishPending("chat2", pending.localPath) }
        access.failWrites = false
        restored.publishPending("chat1", pending.localPath)
        assertEquals("retained result", File(phone, "result.txt").readText())
    }

    @Test fun cancellationLeavesRegisteredOutputsAvailableWithoutPublishingPartialFiles() {
        val engine = engine()
        engine.prepareOutputs("chat1", location, listOf("partial.txt"))
        staged("chat1", "partial.txt").writeText("partial")
        assertFalse(File(phone, "partial.txt").exists())
        assertEquals(1, engine().pending("chat1").size)
        staged("chat1", "partial.txt").appendText(" complete")
        engine().publishFiles("chat1", location, listOf("partial.txt"))
        assertEquals("partial complete", File(phone, "partial.txt").readText())
    }

    @Test fun differentConversationsNeverShareExecutionCopiesAndDetectPublishConflict() {
        source("input.txt", "same original")
        val engine = engine()
        for (owner in listOf("a", "b")) {
            engine.importFiles(owner, location, listOf("input.txt"))
            engine.prepareOutputs(owner, location, listOf("input.txt"))
            staged(owner, "input.txt").writeText(owner)
        }
        engine.publishFiles("a", location, listOf("input.txt"))
        val result = engine.publishFiles("b", location, listOf("input.txt")).single()
        assertTrue(result.conflict)
        assertEquals("a", File(phone, "input.txt").readText())
        assertEquals("b", File(phone, result.path).readText())
    }

    @Test fun unimportedOutputsNeverOverwriteExistingPhoneFiles() {
        source("existing.txt", "phone")
        val engine = engine()
        engine.executionDirectory("a", location)
        staged("a", "existing.txt").writeText("local")
        val result = engine.publishFiles("a", location, listOf("existing.txt")).single()
        assertTrue(result.conflict)
        assertEquals("phone", File(phone, "existing.txt").readText())
    }

    @Test fun reimportDoesNotDiscardUnsavedCommandChanges() {
        source("file.txt", "phone")
        val engine = engine()
        engine.importFiles("a", location, listOf("file.txt"))
        staged("a", "file.txt").writeText("unsaved")
        fails { engine.importFiles("a", location, listOf("file.txt")) }
        assertEquals("unsaved", staged("a", "file.txt").readText())
    }

    @Test fun revokedAndReadOnlyAccessAreReportedWithoutFallback() {
        source("file.txt", "readable")
        val engine = engine()
        access.permission = WorkspaceAccess.ReadOnly
        assertEquals("readable", engine.read(location, "file.txt").text)
        fails { engine.write("a", location, "new.txt", "denied", null) }
        access.permission = WorkspaceAccess.Unavailable
        fails { engine.read(location, "file.txt") }
        assertFalse(File(phone, "new.txt").exists())
    }

    @Test fun traversalInvalidOwnersAndBinaryTextAreRejected() {
        for (path in listOf("../outside", "/sdcard/file", "a/../file", "a//file", "a\\file", "content:uri", "a/./file", "a/\u0000")) {
            fails { WorkspacePaths.relative(path) }
        }
        fails { engine().executionDirectory("../a", location) }
        source("binary", "a\u0000b")
        fails { engine().read(location, "binary") }
        source("large", "x".repeat(WorkspaceEngine.TextLimit + 1))
        fails { engine().read(location, "large") }
    }

    @Test fun listingPaginatesAndRenamingChecksOriginalRevision() {
        repeat(55) { source("file$it.txt", "$it") }
        val engine = engine()
        val first = engine.list(location)
        assertEquals(50, first.files.size)
        assertEquals(50, first.nextOffset)
        val second = engine.list(location, offset = first.nextOffset!!)
        assertEquals(5, second.files.size)
        assertNull(second.nextOffset)
        val revision = engine.read(location, "file0.txt").revision
        engine.rename(location, "file0.txt", "改名.txt", revision)
        assertEquals("0", File(phone, "改名.txt").readText())
        fails { engine.rename(location, "file1.txt", "改名.txt", revision) }
    }

    @Test fun deletedDestinationIsTreatedAsConcurrentChange() {
        val source = source("file.txt", "old")
        val engine = engine()
        val read = engine.read(location, "file.txt")
        source.delete()
        val result = engine.write("a", location, "file.txt", "new", read.revision)
        assertTrue(result.conflict)
        assertFalse(source.exists())
    }

    private class FakeFiles(private val root: File) : WorkspaceFileAccess {
        var permission = WorkspaceAccess.ReadWrite
        var failWrites = false
        override fun status(location: WorkspaceLocation) = WorkspaceStatus(permission)
        override fun list(location: WorkspaceLocation, path: String, offset: Int, limit: Int): WorkspacePage {
            val all = (if (path.isEmpty()) root else File(root, path)).listFiles()!!.sortedBy { it.name }
            return WorkspacePage(all.drop(offset).take(limit).map { WorkspaceFile(it.name, it.name, it.isDirectory, it.length(), it.lastModified()) },
                (offset + limit).takeIf { it < all.size })
        }
        override fun read(location: WorkspaceLocation, path: String, maxBytes: Int) = File(root, path).inputStream().use { BoundedWorkspaceIo.read(it, maxBytes) }
        override fun exists(location: WorkspaceLocation, path: String) = File(root, path).exists()
        override fun write(location: WorkspaceLocation, path: String, bytes: ByteArray) {
            check(!failWrites) { "Simulated save failure" }
            File(root, path).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        }
        override fun rename(location: WorkspaceLocation, path: String, newName: String): String {
            val source = File(root, path)
            val destination = File(source.parentFile, newName)
            check(!destination.exists() && source.renameTo(destination))
            return destination.relativeTo(root).invariantSeparatorsPath
        }
    }
}
