package com.music.bitchord.device

enum class BudsControlTransport {
    LEGACY,
    STANDARD,
    MODERN,
}

enum class DeviceFamily(
    val budsTransport: BudsControlTransport? = null,
    val supportsAnc: Boolean = false,
    val supportsAmbientMode: Boolean = false,
    val supportsAdaptiveMode: Boolean = false,
) {
    GALAXY_BUDS(
        budsTransport = BudsControlTransport.LEGACY,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS_PLUS(
        budsTransport = BudsControlTransport.STANDARD,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS_LIVE(
        budsTransport = BudsControlTransport.STANDARD,
        supportsAnc = true,
    ),
    GALAXY_BUDS_PRO(
        budsTransport = BudsControlTransport.STANDARD,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS2(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS2_PRO(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS_FE(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS3(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS3_PRO(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
        supportsAdaptiveMode = true,
    ),
    GALAXY_BUDS3_FE(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS_CORE(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS4(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
    ),
    GALAXY_BUDS4_PRO(
        budsTransport = BudsControlTransport.MODERN,
        supportsAnc = true,
        supportsAmbientMode = true,
        supportsAdaptiveMode = true,
    ),
    ;

    val isGalaxyBuds: Boolean
        get() = budsTransport != null

    val supportsAnyNoiseControl: Boolean
        get() = supportsAnc || supportsAmbientMode
}

data class DeviceProfile(
    val family: DeviceFamily,
    val displayName: String,
)

object DeviceRegistry {

    private fun normalize(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it == '+' }

    fun identify(rawName: String?): DeviceProfile? {
        if (rawName.isNullOrBlank()) return null
        val modelCode = Regex("""(?i)(?:^|[\s(])(?:SM[-\s]?)?R[-\s]?(\d{3}N?)(?:$|[\s)])""")
            .find(rawName)?.groupValues?.get(1)?.lowercase()
        val n = normalize(rawName)
        if ("motobuds" in n) return null
        val model = n.removePrefix("samsung").removePrefix("galaxy")
        return when {
            "buds4pro" in model || modelCode == "640" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS4_PRO, "Galaxy Buds4 Pro")
            "buds4" in model || modelCode == "540" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS4, "Galaxy Buds4")
            "buds3pro" in model || modelCode == "630" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS3_PRO, "Galaxy Buds3 Pro")
            "buds3fe" in model || modelCode == "420" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS3_FE, "Galaxy Buds3 FE")
            "buds3" in model || modelCode == "530" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS3, "Galaxy Buds3")
            "buds2pro" in model || modelCode == "510" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS2_PRO, "Galaxy Buds2 Pro")
            "buds2" in model || modelCode == "177" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS2, "Galaxy Buds2")
            "budscore" in model || modelCode == "410" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS_CORE, "Galaxy Buds Core")
            "budsfe" in model || modelCode == "400n" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS_FE, "Galaxy Buds FE")
            "budslive" in model || modelCode == "180" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS_LIVE, "Galaxy Buds Live")
            "budspro" in model || modelCode == "190" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS_PRO, "Galaxy Buds Pro")
            "budsplus" in model || "buds+" in model || modelCode == "175" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS_PLUS, "Galaxy Buds+")
            model == "buds" || modelCode == "170" ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS, "Galaxy Buds")
            else -> null
        }
    }
}
