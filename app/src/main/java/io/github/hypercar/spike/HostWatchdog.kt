package io.github.hypercar.spike

/**
 * `/proc/<pid>/stat` 的解析，给 `:spike` 服务判断宿主 App 进程是否还活着。
 *
 * 为什么用 starttime 而不是只看 pid 存在：pid 会被系统复用，只看 pid 会把「宿主已死、
 * 但 pid 被别的进程接管」误判成宿主还活着。starttime 是进程启动时刻的 jiffies，
 * 同一个 pid 复用后必然不同。
 */
internal object HostWatchdog {

    /**
     * 取 `/proc/<pid>/stat` 里第 22 个字段 starttime。
     *
     * comm（第 2 个字段）可能出现空格甚至括号，所以先截到最后一个 ')'；之后第 3 个字段
     * 落在下标 0，故第 22 个字段是下标 19。解析失败返回 null。
     */
    fun startTicks(stat: String): Long? = runCatching {
        stat.substringAfterLast(')').trim().split(' ').getOrNull(STARTTIME_INDEX)?.toLongOrNull()
    }.getOrNull()

    /** 字段 22 在「去掉 pid、comm」后的下标。 */
    private const val STARTTIME_INDEX = 19
}
