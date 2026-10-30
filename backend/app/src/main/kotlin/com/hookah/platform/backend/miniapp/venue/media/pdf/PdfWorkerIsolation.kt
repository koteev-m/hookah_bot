package com.hookah.platform.backend.miniapp.venue.media.pdf

import java.nio.file.Files
import java.nio.file.Path

internal object PdfWorkerIsolation {
    const val MEMORY_BYTES = 384L * 1024 * 1024
    const val MAX_PIDS = 64L

    fun requireEnforced() {
        check(System.getProperty("os.name") == "Linux") { "PDF_OS_LIMITS_REQUIRED" }

        fun cgroup(name: String) = Files.readString(Path.of("/sys/fs/cgroup", name)).trim()
        check(cgroup("memory.max").toLong() in 1..MEMORY_BYTES)
        check(cgroup("memory.swap.max") == "0")
        check(cgroup("pids.max").toLong() in 1..MAX_PIDS)
        val cpu = cgroup("cpu.max").split(' ').map { it.toLong() }
        check(cpu.size == 2 && cpu[0] > 0 && cpu[0] <= cpu[1])
        check(
            Files.readAllLines(Path.of("/proc/self/limits")).any {
                it.matches(Regex("Max core file size\\s+0\\s+0\\s+bytes\\s*"))
            },
        )
        val status =
            Files.readString(Path.of("/proc/self/status")).lineSequence().associate {
                it.substringBefore(':') to it.substringAfter(':').trim()
            }
        check(status.getValue("Uid").split(Regex("\\s+")).all { it == "10001" })
        check(status.getValue("CapEff").all { it == '0' } && status["NoNewPrivs"] == "1" && status["Seccomp"] == "2")
        check(
            Files.readAllLines(Path.of("/proc/self/mountinfo")).any {
                val columns = it.split(' ')
                columns[4] == "/" && "ro" in columns[5].split(',')
            },
        )
        Files.list(Path.of("/sys/class/net")).use { interfaces ->
            check(
                interfaces.filter { Files.isDirectory(it) }.allMatch {
                    it.fileName.toString() == "lo" ||
                        (Files.readString(it.resolve("flags")).trim().removePrefix("0x").toInt(16) and 1) == 0
                },
            )
        }
        check(Files.readAllLines(Path.of("/proc/net/route")).drop(1).all { it.isBlank() })
        check(
            Files.readAllLines(Path.of("/proc/net/if_inet6")).all {
                it.trim().split(Regex("\\s+")).last() == "lo"
            },
        )
    }
}
