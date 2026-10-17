package com.music.bitchord.device

enum class DeviceFamily {
    GALAXY_BUDS2,
    GALAXY_BUDS3_PRO,
    MOTO_BUDS_PLUS,
    HK_ONYX_STUDIO_9,
}

data class DeviceProfile(
    val family: DeviceFamily,
    val displayName: String,
)

object DeviceRegistry {

    // Quita espacios, guiones, parentesis, etc. Deja letras, numeros y '+'.
    private fun normalize(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() || it == '+' }

    fun identify(rawName: String?): DeviceProfile? {
        if (rawName.isNullOrBlank()) return null
        val n = normalize(rawName)
        return when {
            "buds3pro" in n ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS3_PRO, "Galaxy Buds3 Pro")
            "buds2" in n && "buds2pro" !in n && "moto" !in n ->
                DeviceProfile(DeviceFamily.GALAXY_BUDS2, "Galaxy Buds2")
            "motobuds+" in n || "motobudsplus" in n ->
                DeviceProfile(DeviceFamily.MOTO_BUDS_PLUS, "Moto Buds+")
            "onyxstudio9" in n ->
                DeviceProfile(DeviceFamily.HK_ONYX_STUDIO_9, "Onyx Studio 9")
            else -> null
        }
    }
}
