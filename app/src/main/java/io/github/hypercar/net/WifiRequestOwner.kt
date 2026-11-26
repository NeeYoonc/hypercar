package io.github.hypercar.net

/** Fail closed if the platform dump cannot identify the active local-only request. */
internal object WifiRequestOwner {
    fun matches(line: String, uid: Int, ssid: String): Boolean {
        if (!line.startsWith("WifiNetworkFactory: mActiveSpecificNetworkRequest NetworkRequest [")) return false
        if (uid < 10000 || ssid.isBlank() || ssid.any { it == '\n' || it == '\r' }) return false
        val capabilities = line.substringAfter("Capabilities: ", "").substringBefore(" Specifier:")
        return capabilities.isNotEmpty() && !capabilities.substringBefore(" Forbidden:").split('&').contains("INTERNET") &&
            line.contains("SSID Match pattern=PatternMatcher{LITERAL: $ssid}") &&
            Regex("RequestorUid: $uid(?: |])").containsMatchIn(line) &&
            // 必须跟运行期 applicationId 对齐：改包名后 dumpsys 里的 RequestorPkg 也会变。
            Regex("RequestorPkg: " + Regex.escape(io.github.hypercar.BuildConfig.APPLICATION_ID) + "(?: |])")
                .containsMatchIn(line)
    }
}
