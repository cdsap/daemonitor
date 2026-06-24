package com.gradlewatcher.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactorTest {

    @Test
    fun `masks -P and -D secret values, leaves safe flags`() {
        val cmd = "gradlew publish -Psigning.password=abc -Dtoken=xyz -PsafeFlag=ok --info"
        val out = Redactor.redactCommandLine(cmd)
        assertTrue(out.contains("-Psigning.password=***"), out)
        assertTrue(out.contains("-Dtoken=***"), out)
        assertTrue(out.contains("-PsafeFlag=ok"), out)
        assertTrue(out.contains("--info"), out)
        assertTrue(!out.contains("abc") && !out.contains("xyz"), out)
    }

    @Test
    fun `masks long-option --key=value secrets`() {
        val out = Redactor.redactToken("--repository-password=hunter2")
        assertEquals("--repository-password=***", out)
    }

    @Test
    fun `masks credentials embedded in URLs`() {
        val out = Redactor.redactToken("https://user:pw@repo.example.com/path")
        assertEquals("https://***:***@repo.example.com/path", out)
    }

    @Test
    fun `leaves non-sensitive tokens unchanged`() {
        assertEquals("clean", Redactor.redactToken("clean"))
        assertEquals("-Dfile.encoding=UTF-8", Redactor.redactToken("-Dfile.encoding=UTF-8"))
    }

    @Test
    fun `masks tab-separated secret flags, not just space-separated`() {
        val out = Redactor.redactCommandLine("java\t-Dapi.token=SECRET\t-jar app.jar")
        assertTrue(out.contains("-Dapi.token=***"), out)
        assertTrue(!out.contains("SECRET"), out)
    }

    @Test
    fun `does not over-redact benign keys containing the substring key`() {
        assertEquals("-Dmonkey.count=5", Redactor.redactToken("-Dmonkey.count=5"))
        assertEquals("-Dkeystore.path=/x", Redactor.redactToken("-Dkeystore.path=/x"))
        // a real key-named property is still masked
        assertEquals("-Psigning.key=***", Redactor.redactToken("-Psigning.key=abc"))
    }

    @Test
    fun `redacts secrets inside a log line`() {
        val line = "2026-06-23T10:25:24 [INFO] [Build] running with -Papikey=deadbeef now"
        val out = Redactor.redactLogLine(line)
        assertTrue(out.contains("-Papikey=***"), out)
        assertTrue(!out.contains("deadbeef"), out)
    }
}
