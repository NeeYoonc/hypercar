package io.github.hypercar.carui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.hypercar.session.HyperCarRepo

/**
 * DeX 暖机用的「空任务」Activity（全透明，不画任何内容）。
 *
 * 背景（2026-11-26 真机取证）：叠加视图（模拟辅助显示设备）那块屏上的窗口，只有在
 * SystemUI 的 Desk（AOSP 桌面窗口化）被「暖起来」之后才会挂上 **桌面标题栏**
 * （左上「齿轮 + ⌄」→ 向左对齐 / 向右对齐 / 发送至其他显示设备）。
 *
 * Desk 的暖机发生在 `DesktopTasksController.createDeskRoot$onCreated` → `activateDesk`
 * 里：那一刻该屏上必须已经有一个任务可以被 `addTaskToDesk`，否则 Desk 就停在
 * `visible=false sz=0`，窗口退回通用 freeform 标题栏（右侧「… — ⤢ ✕」、左侧空白，
 * 没有左/右半屏入口）。
 *
 * DexCaster 建好叠加屏时，DeX 桌面（SecondaryLauncher）的任务往往早就存在，再
 * `am start` 只会把它提到前台（`Activity not started, its current task has been
 * brought to the front`），不产生新任务，于是 Desk 永远暖不起来。这里用一块全透明
 * 的空 Activity 在建屏与建 Desk 的时间窗内制造一个新任务；Desk 激活、DeX 桌面接上来
 * 之后自己结束，画面不受影响。
 *
 * 只能用 shell 的 `am start` 拉起：应用自己在后台起 Activity 会被系统限制。
 */
class DexWarmupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HyperCarRepo.log("DEX-WARMUP: 空任务已建立 display=${display?.displayId}")
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                HyperCarRepo.log("DEX-WARMUP: 空任务结束")
                finishAndRemoveTask()
            }
        }, WARMUP_LIFETIME_MS)
    }

    private companion object {
        /** 活到 Desk 激活并把 DeX 桌面接上来为止；太短会让 Desk 退回 sz=0。 */
        const val WARMUP_LIFETIME_MS = 2_500L
    }
}
