package com.flowchat.app.data.terminal

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneShellFactsTest {
    private fun paths(value: JsonObject) = value.getValue("paths").jsonArray.map { it.jsonObject }
    private open class Available : PhoneShellFacts.Files {
        val calls = mutableListOf<String>()
        override fun kind(path: String, followLinks: Boolean): String {
            calls += "kind:$path:$followLinks"
            return if (path.endsWith("/sh") && !followLinks) "symbolic_link" else "regular_file"
        }
        override fun linkTarget(path: String): String { calls += "link:$path"; return "mksh" }
        override fun executable(path: String): Boolean { calls += "access:$path"; return true }
    }

    @Test fun fixedPathsReturnSymlinkAndPermissionObservationsWithoutAnExecutionClaim() {
        val files = Available(); val result = PhoneShellFacts.observe(files)
        val paths = paths(result)
        assertEquals(listOf("/system/bin/sh", "/system/bin/mksh"), paths.map { it.getValue("path").jsonPrimitive.content })
        assertEquals("mksh", paths.first().getValue("link_target").jsonPrimitive.content)
        assertEquals("symbolic_link", paths.first().getValue("entry_kind").jsonPrimitive.content)
        assertTrue(paths.all { it.getValue("target_kind").jsonPrimitive.content == "regular_file" })
        assertTrue(paths.all { it.getValue("executable_access_for_app_uid").jsonPrimitive.boolean })
        assertFalse(result.getValue("execution_tested").jsonPrimitive.boolean)
        assertEquals(6, files.calls.size)
        assertEquals(result, PhoneShellFacts.observe(Available()))
    }

    @Test fun permissionDeniedDoesNotBecomeMissingOrFalseExecuteAccess() {
        val result = PhoneShellFacts.observe(object : Available() {
            override fun kind(path: String, followLinks: Boolean): String = throw PhoneShellFacts.Unavailable(if (path.endsWith("/sh")) "missing" else "permission_denied")
        })
        val paths = paths(result)
        assertEquals("missing", paths.first().getValue("entry_status").jsonPrimitive.content)
        assertEquals("unknown", paths.last().getValue("entry_status").jsonPrimitive.content)
        assertEquals("permission_denied", paths.last().getValue("observation_error").jsonPrimitive.content)
        assertTrue(paths.all { "executable_access_for_app_uid" !in it && "target_status" !in it })
    }

    @Test fun danglingLinkAndDeniedAccessKeepTheirSeparateKnownAndUnknownParts() {
        val result = PhoneShellFacts.observe(object : Available() {
            override fun kind(path: String, followLinks: Boolean): String {
                if (followLinks) throw PhoneShellFacts.Unavailable("missing")
                return super.kind(path, false)
            }
            override fun executable(path: String): Boolean = throw PhoneShellFacts.Unavailable("permission_denied")
        })
        val paths = paths(result)
        assertEquals("present", paths.first().getValue("entry_status").jsonPrimitive.content)
        assertEquals("missing", paths.first().getValue("target_status").jsonPrimitive.content)
        assertEquals("present", paths.last().getValue("target_status").jsonPrimitive.content)
        assertEquals("unknown", paths.last().getValue("access_status").jsonPrimitive.content)
        assertTrue(paths.all { "executable_access_for_app_uid" !in it })
    }
}
