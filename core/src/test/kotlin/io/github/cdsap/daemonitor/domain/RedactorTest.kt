package io.github.cdsap.daemonitor.domain

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

    // Fixtures mirror redactor_test.go.
    @Test
    fun `nested token parity`() {
        val cases = listOf(
            "--action_env=GITHUB_TOKEN=alpha" to "--action_env=GITHUB_TOKEN=***",
            "--repo_env=API_SECRET=beta" to "--repo_env=API_SECRET=***",
            "--test_env=DB_PASSWORD=gamma" to "--test_env=DB_PASSWORD=***",
            "--client_env=SECRET_KEY=delta" to "--client_env=SECRET_KEY=***",
            "--jvmopt=-Dmy.token=epsilon" to "--jvmopt=-Dmy.token=***",
            "--host_jvmopt=-Dsigning.key=zeta" to "--host_jvmopt=-Dsigning.key=***",
            "--jvmopt=--host_jvmopt=-Dmy.token=alpha=beta" to "--jvmopt=--host_jvmopt=-Dmy.token=***",
            "--action_env=PATH=/bin" to "--action_env=PATH=/bin",
            "--jvmopt=-Dmonkey.count=5" to "--jvmopt=-Dmonkey.count=5",
            "--host_jvmopt=-Dkeystore.path=/x" to "--host_jvmopt=-Dkeystore.path=/x",
            "--repo_env=ENDPOINT=https://user:pw@host/path" to "--repo_env=ENDPOINT=https://***:***@host/path",
            "--safe=GITHUB_TOKEN=example" to "--safe=GITHUB_TOKEN=example",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, Redactor.redactToken(input), input)
            assertEquals("bazel $expected --info", Redactor.redactCommandLine("bazel $input --info"), input)
            assertEquals("[INFO] $expected done", Redactor.redactLogLine("[INFO] $input done"), input)
        }
    }

    // Fixtures mirror redactor_test.go.
    @Test
    fun `sensitive option parity`() {
        val cases = listOf(
            "java --password secret --token=inline --safe value" to "java --password *** --token=*** --safe value",
            "java\t--token\talpha -Psigning.key beta -Dmy.password gamma --safe ok" to "java --token *** -Psigning.key *** -Dmy.password *** --safe ok",
            "java --monkey.count 5 --keystore.path /x --token" to "java --monkey.count 5 --keystore.path /x --token",
            " \t " to " \t ",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, Redactor.redactCommandLine(input), input)
        }
    }

    // Fixtures mirror redactor_test.go.
    @Test
    fun `log credentials parity`() {
        val cases = listOf(
            "Authorization: Bearer abc.def_123-xyz" to "Authorization: Bearer ***",
            "Proxy-Authorization: Basic dXNlcjpwYXNz==" to "Proxy-Authorization: Basic ***",
            "received bearer\tabc and BASIC xyz== now" to "received bearer\t*** and BASIC *** now",
            "  SECRET_KEY=alpha\tDB_PASSWORD=beta API_TOKEN=gamma=delta PATH=/bin  " to "  SECRET_KEY=***\tDB_PASSWORD=*** API_TOKEN=*** PATH=/bin  ",
            "secret_key=alpha monkey=5 keystore=/x" to "secret_key=*** monkey=5 keystore=/x",
            "Authorization: Bearer" to "Authorization: Bearer",
            "ordinary log\twith  spacing" to "ordinary log\twith  spacing",
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, Redactor.redactLogLine(input), input)
        }
    }
}
