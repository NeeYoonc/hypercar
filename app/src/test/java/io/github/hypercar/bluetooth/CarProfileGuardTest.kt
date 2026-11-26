package io.github.hypercar.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车机 HFP/A2DP 拦截的准入规则（纯函数，不碰蓝牙栈）。
 *
 * 现场依据：2026-11-01 11:05:10.840 手机自己回连 HFP → 11:05:13.038 车机
 * `Remote Terminated`，iAP2 从未打开；成功那次没有 HFP 先行。
 */
class CarProfileGuardTest {

    @Test fun manualSessionKeepsGuardWhenAutoConnectIsOff() {
        assertTrue(CarProfilePolicy.shouldGuard(true, false, true))
        assertFalse(CarProfilePolicy.shouldGuard(true, false, false))
    }

    @Test fun standbyRequiresAutoConnectAndReceiverProfileConsent() {
        assertTrue(CarProfilePolicy.shouldGuard(true, true, false))
        assertFalse(CarProfilePolicy.shouldGuard(false, true, false))
        assertFalse(CarProfilePolicy.shouldGuard(false, true, true))
        assertFalse(CarProfilePolicy.shouldGuard(false, false, true))
    }

    /**
     * 2026-11-26 实机：CarLife 会话里拦截 HFP/A2DP 会把车机 ACL 一起拆掉，
     * 三星车联随即 deInitAndRemoveCarlife 并 force-stop 高德，导航每次掉线都被杀。
     * 所以 CarLife 模式下无论待机/会话/自动连接，都不许再动车机蓝牙配置文件。
     */
    @Test fun carLifeSessionNeverTouchesCarProfiles() {
        for (autoConnect in listOf(true, false)) {
            for (hasSession in listOf(true, false)) {
                assertFalse(
                    "CarLife 会话必须保留车机蓝牙",
                    CarProfilePolicy.shouldGuard(true, autoConnect, hasSession, carLifeSession = true),
                )
            }
        }
        assertTrue("非 CarLife 会话的既有行为不变", CarProfilePolicy.shouldGuard(true, true, false))
    }

    @Test fun onlyTargetCarIsTouched() {
        assertTrue(
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9c:df:03:5d:fb:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
        assertFalse(
            "别的设备（耳机/手表）不碰",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "B0:A3:F2:D4:FA:30",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
    }

    @Test fun unknownTargetIsIgnored() {
        assertFalse(
            CarProfilePolicy.shouldEnforce(
                targetAddress = null,
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
        assertFalse(
            CarProfilePolicy.shouldEnforce(
                targetAddress = "  ",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000,
                lastEnforceAtMillis = 0,
            ),
        )
    }

    @Test fun throttlePreventsFightingWithCarReconnect() {
        val interval = CarProfilePolicy.MIN_INTERVAL_MILLIS
        assertFalse(
            "同一地址 3s 内不重复断",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000 + interval - 1,
                lastEnforceAtMillis = 10_000,
            ),
        )
        assertTrue(
            "超过节流窗口允许再断一次",
            CarProfilePolicy.shouldEnforce(
                targetAddress = "9C:DF:03:5D:FB:90",
                eventAddress = "9C:DF:03:5D:FB:90",
                nowMillis = 10_000 + interval,
                lastEnforceAtMillis = 10_000,
            ),
        )
    }
}
