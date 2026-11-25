package com.droidservicepro

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import java.io.File

/** Pair to the target's temporary Wireless debugging pairing port, then read it over TLS ADB. */
internal suspend fun readWifiDiagnostics(
    context: Context,
    host: String,
    pairingPort: Int,
    pairingCode: String,
    connectPort: Int
): DeviceReport {
    require(host.isNotBlank()) { "Enter the phone's IP address." }
    require(pairingPort in 1..65535) { "Pairing port must be between 1 and 65535." }
    require(connectPort in 1..65535) { "Wireless debugging port must be between 1 and 65535." }
    require(pairingCode.matches(Regex("\\d{6}"))) { "Pairing code must contain six digits." }

    // The library needs an exportable RSA key for TLS certificate creation.
    // Store it in the app-private no-backup directory, never in external storage.
    val keyProvider = FileKeyProvider(
        keyFile = File(context.noBackupFilesDir, "wireless-adb-key"),
        identityComment = "droidservicepro@android"
    )
    AdbClient.pairTls(host, pairingPort, pairingCode, keyProvider).getOrElse { throw it }

    return AdbClient.connectTls(host, connectPort, keyProvider).use { adb ->
        val dump = adb.shell("getprop").stdoutText
        val propertyNames = listOf(
            "persist.sys.omc_path", "persist.sys.omc_etcpath", "persist.sys.omcnw_path",
            "persist.sys.omcnw_path2", "ro.csc.sales_code", "ril.sales_code",
            "ro.boot.sales_code", "ril.official_cscver", "ro.csc.omc_code", "ro.boot.omc_code"
        )
        val extra = adb.shell(propertyNames.joinToString("\n") { "getprop $it" }).stdoutText
            .lineSequence().toList()
        val supplemental = propertyNames.mapIndexedNotNull { index, name ->
            extra.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { "[$name]: [$it]" }
        }
        val report = DeviceReport.fromProperties(
            if (supplemental.isEmpty()) dump else dump + "\n" + supplemental.joinToString("\n")
        )
        if (report.model == null && report.android == null) {
            throw IllegalStateException("The phone connected but returned no Android properties.")
        }

        var supported = emptyList<String>()
        for (path in cscListPaths) {
            val parsed = try {
                parseWifiCscList(adb.shell("cat $path").stdoutText)
            } catch (_: Exception) {
                emptyList()
            }
            if (parsed.isNotEmpty()) {
                supported = parsed
                break
            }
        }
        if (supported.isNotEmpty()) {
            return@use report.copy(supportedCscs = supported, supportedCscSource = CscListSource.FIRMWARE)
        }
        val reference = MultiCscCatalog.codesFor(report.multiCsc?.code)
        if (reference.isNotEmpty()) {
            return@use report.copy(
                supportedCscs = reference,
                supportedCscSource = CscListSource.PUBLIC_REFERENCE
            )
        }
        report
    }
}

private val cscListPaths = listOf(
    "/odm/etc/omc/sales_code_list.dat",
    "/system/odm/etc/omc/sales_code_list.dat",
    "/prism/etc/sales_code_list.data",
    "/product/omc/sales_code_list.dat",
    "/optics/configs/carriers/sales_code_list.dat",
    "/system/omc/sales_code_list.dat")

private fun parseWifiCscList(raw: String): List<String> = raw.lineSequence()
    .map { it.trim().removePrefix("single/").removePrefix("multi/") }
    .filter { it.matches(Regex("[A-Z]{3}")) }
    .distinct()
    .toList()
