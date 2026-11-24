package com.droidservicepro

internal val cscCodes = (
    "BTB BTE EVR MEO SFR ACR AFG AFR BKD BOG CAU CPW DKR ECT EGY EUX EUY FWD GLB ILO ILP " +
    "INS KSA LYS MET MID MM1 MOB MOT MSR MWD NPL O2C OPS PKD PLS PRT SEK SER SIM SIN SKZ SLK " +
    "STH SWC TEL THL TIM TOP TUN TUR VAU VIP WWA WWC WWD XFA XFE XFV XME XNZ XSA XSG XSP XTC XXV"
).split(' ').sorted()

internal data class CscReading(val code: String, val property: String)

internal enum class CscListSource { FIRMWARE, PUBLIC_REFERENCE, UNAVAILABLE }

internal data class DeviceReport(
    val model: String?,
    val manufacturer: String?,
    val android: String?,
    val build: String?,
    val baseband: String?,
    val activeCsc: CscReading?,
    val salesCsc: CscReading?,
    val multiCsc: CscReading?,
    val cscProperties: Map<String, String?>,
    val supportedCscs: List<String> = emptyList(),
    val supportedCscSource: CscListSource = CscListSource.UNAVAILABLE
) {
    companion object {
        private const val ACTIVE_PATH = "persist.sys.omc_path"
        private const val CSC_FIRMWARE = "ril.official_cscver"
        private val cscPropertyNames = listOf(
            ACTIVE_PATH,
            "persist.sys.omc_etcpath",
            "persist.sys.omcnw_path",
            "persist.sys.omcnw_path2",
            "ro.csc.sales_code",
            "ril.sales_code",
            "ro.boot.sales_code",
            CSC_FIRMWARE,
            "ro.csc.omc_code",
            "ro.boot.omc_code"
        )
        private val propertyLine = Regex("""^\[([^]]+)]\s*:\s*\[(.*)]$""")
        private val cscCode = Regex("[A-Z0-9]{3}")
        private val omcPath = Regex("""/(?:omc/|carriers/(?:single/)?)([A-Z0-9]{3})(?:/|$)""")

        fun fromProperties(output: String): DeviceReport {
            val properties = output.lineSequence().mapNotNull {
                propertyLine.matchEntire(it)?.destructured?.let { (name, value) -> name to value.trim() }
            }.toMap()
            fun value(name: String) = properties[name]?.takeUnless {
                it.isBlank() || it.equals("unknown", ignoreCase = true)
            }
            fun code(name: String) = value(name)?.takeIf(cscCode::matches)?.let { CscReading(it, name) }

            val active = value(ACTIVE_PATH)?.let(omcPath::find)?.groupValues?.get(1)
                ?.let { CscReading(it, ACTIVE_PATH) }
            val model = value("ro.product.model")
            val firmwareModel = model?.substringBefore('/')?.removePrefix("SM-")
            val firmwarePackage = firmwareModel?.let {
                Regex(Regex.escape(it) + "([A-Z0-9]{3})[A-Z0-9]{5}").matchEntire(value(CSC_FIRMWARE).orEmpty())
                    ?.groupValues?.get(1)?.let { code -> CscReading(code, CSC_FIRMWARE) }
            }
            return DeviceReport(
                model, value("ro.product.manufacturer"),
                value("ro.build.version.release"), value("ro.build.PDA") ?: value("ro.build.display.id"),
                value("gsm.version.baseband"), active, code("ro.csc.sales_code") ?: code("ril.sales_code"),
                firmwarePackage ?: code("ro.csc.omc_code"),
                cscPropertyNames.associateWith(properties::get)
            )
        }
    }
}
