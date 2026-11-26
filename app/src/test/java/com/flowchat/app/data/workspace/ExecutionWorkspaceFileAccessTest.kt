package com.flowchat.app.data.workspace

import com.flowchat.app.domain.workspace.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionWorkspaceFileAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val location = WorkspaceLocation("content://qa/local", "local")
    @Test fun boundedReadsObserveActualLocalChangesWithoutChangingTheSource() {
        val root = temporary.newFolder()
        val text = "已写入😀\n".repeat(100000)
        val file = File(root, "result.txt").apply { writeText(text) }
        val before = WorkspacePaths.digest(file.readBytes())
        val files = ExecutionWorkspaceFileAccess(root)
        val result = WorkspaceReader(files).range(location, "result.txt", 0, 128)
        assertTrue(text.startsWith(result.text!!)); assertTrue(result.sourceBytesRead <= 129)
        assertFalse(result.eof); assertEquals(before, WorkspacePaths.digest(file.readBytes()))
        assertTrue(runCatching { files.write(location, "result.txt", byteArrayOf()) }.isFailure)
        assertTrue(runCatching { files.rename(location, "result.txt", "renamed") }.isFailure)
    }
    @Test fun absentExecutionRootIsNotCreatedByInspection() {
        val root = File(temporary.root, "absent")
        val files = ExecutionWorkspaceFileAccess(root)
        assertTrue(runCatching { files.list(location, "", 0, 50) }.isFailure)
        assertFalse(root.exists())
    }
    @Test fun directoryPagesAreBoundedAndTraversalCannotReadAnotherOwner() {
        val root = temporary.newFolder()
        repeat(63) { File(root, "$it.txt").writeText("$it") }
        val files = ExecutionWorkspaceFileAccess(root)
        val page = files.list(location, "", 0, 50)
        assertEquals(50, page.files.size); assertEquals(50, page.nextOffset)
        assertEquals(13, files.list(location, "", 50, 50).files.size)
        listOf("../other.txt", "/outside", "nested/../other", "C:/outside").forEach {
            assertTrue(it, runCatching { files.stat(location, it) }.isFailure)
        }
    }
    @Test fun linksAndDirectoriesCannotBeReadAsRegularFiles() {
        val root = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("outside") }
        val files = ExecutionWorkspaceFileAccess(root)
        // Windows may require developer mode for symbolic links; the device case also covers this.
        val link = File(root, "link").toPath()
        if (runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess)
            assertTrue(runCatching { files.stat(location, "link") }.isFailure)
        File(root, "folder").mkdir()
        assertTrue(runCatching { files.stat(location, "folder") }.isFailure)
    }
}
