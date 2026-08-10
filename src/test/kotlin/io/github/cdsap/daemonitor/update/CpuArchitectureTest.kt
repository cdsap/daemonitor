package io.github.cdsap.daemonitor.update

import kotlin.test.Test
import kotlin.test.assertEquals

class CpuArchitectureTest {

    @Test
    fun `maps common jvm arch names`() {
        assertEquals(CpuArchitecture.ARM64, CpuArchitecture.from("aarch64"))
        assertEquals(CpuArchitecture.ARM64, CpuArchitecture.from("arm64"))
        assertEquals(CpuArchitecture.X64, CpuArchitecture.from("x86_64"))
        assertEquals(CpuArchitecture.X64, CpuArchitecture.from("amd64"))
        assertEquals(CpuArchitecture.UNKNOWN, CpuArchitecture.from("ppc64"))
    }
}
