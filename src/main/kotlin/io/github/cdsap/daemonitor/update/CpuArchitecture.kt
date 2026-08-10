package io.github.cdsap.daemonitor.update

enum class CpuArchitecture(val token: String) {
    X64("x64"),
    ARM64("arm64"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun current(
            osArch: String = System.getProperty("os.arch"),
            osName: String = System.getProperty("os.name"),
        ): CpuArchitecture {
            val detected = from(osArch)
            // Under Rosetta, Java reports x86_64; prefer the host CPU for artifact selection.
            if (detected == X64 && osName.lowercase().contains("mac") && isAppleSiliconHost()) {
                return ARM64
            }
            return detected
        }

        fun from(osArch: String): CpuArchitecture {
            val arch = osArch.lowercase()
            return when {
                arch == "aarch64" || arch == "arm64" -> ARM64
                arch == "amd64" || arch == "x86_64" || arch == "x64" -> X64
                arch.startsWith("arm") -> ARM64
                else -> UNKNOWN
            }
        }

        private fun isAppleSiliconHost(): Boolean =
            runCatching {
                val process = ProcessBuilder("sysctl", "-n", "hw.optional.arm64")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor() == 0 && output == "1"
            }.getOrDefault(false)
    }
}
