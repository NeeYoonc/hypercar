package io.github.hypercar.spike

import org.junit.Assert.*
import org.junit.Test

class HostWatchdogTest {

    /** 按 /proc/<pid>/stat 的真实字段顺序拼一行：pid (comm) state ... starttime(22) ... */
    private fun statLine(comm: String, starttime: Long): String {
        val fields = mutableListOf("4242", "($comm)")
        for (field in 3..22) fields += if (field == 22) starttime.toString() else "0"
        return fields.joinToString(" ")
    }

    @Test fun starttime_is_the_22nd_field() {
        assertEquals(987654321L, HostWatchdog.startTicks(statLine("io.github.hypercar", 987654321L)))
    }

    @Test fun comm_with_spaces_and_parens_still_parses_the_right_field() {
        // 内核会把 comm 原样放进括号里，含空格或 ')' 时不能按第一个 ')' 截断。
        assertEquals(12345L, HostWatchdog.startTicks(statLine("a b) c", 12345L)))
        assertEquals(678L, HostWatchdog.startTicks(statLine("(weird name)", 678L)))
    }

    @Test fun reused_pid_has_a_different_starttime() {
        val old = HostWatchdog.startTicks(statLine("io.github.hypercar", 100L))
        val reused = HostWatchdog.startTicks(statLine("io.github.hypercar", 900L))
        assertEquals(100L, old)
        assertEquals(900L, reused)
        assertNotEquals(old, reused)
    }

    @Test fun truncated_or_malformed_stat_returns_null() {
        assertNull(HostWatchdog.startTicks(""))
        assertNull(HostWatchdog.startTicks("4242 (x) S 0 0"))
        val nonNumericStarttime = (listOf("4242", "(x)") + (3..21).map { "0" } + listOf("abc")).joinToString(" ")
        assertNull(HostWatchdog.startTicks(nonNumericStarttime))
    }
}
