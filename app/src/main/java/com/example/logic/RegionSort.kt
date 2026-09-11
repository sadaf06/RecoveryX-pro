package com.example.logic

import com.example.data.model.Vehicle

// Kota region priority sorting (mirrors web app):
// RTO RJ-08 Bundi, RJ-17 Jhalawar, RJ-20 Kota, RJ-28 Baran, RJ-33 Ramganjmandi
// + nearby tehsils/towns matched in POS. Region vehicles first, then number A-Z.
object RegionSort {

    private val KOTA_RTO_PREFIXES = listOf("RJ08", "RJ17", "RJ20", "RJ28", "RJ33")

    private val KOTA_AREA_KEYWORDS = listOf(
        "kota", "ladpura", "digod", "pipalda", "sangod", "ramganj", "kanwas",
        "itawa", "kaithoon", "kaithun", "sultanpur", "mandana", "chechat",
        "khairabad", "keshoraipatan", "bundi", "lakheri", "indergarh", "nainwa",
        "hindoli", "kapren", "talera", "baran", "kishanganj", "shahbad",
        "chhabra", "chhipabarod", "atru", "mangrol", "anta", "siswali", "khanpur",
        "jhalawar", "jhalrapatan", "aklera", "pirawa", "bhawani", "dag",
        "gangdhar", "bakani", "suket", "manohar"
    )

    fun isKotaRegion(v: Vehicle): Boolean {
        val reg = v.vehicleNumber.uppercase().replace("[\\s-]+".toRegex(), "")
        if (KOTA_RTO_PREFIXES.any { reg.startsWith(it) }) return true
        val hay = "${v.pos} ${v.bankName}".lowercase()
        return KOTA_AREA_KEYWORDS.any { hay.contains(it) }
    }

    fun sortKotaFirst(list: List<Vehicle>): List<Vehicle> =
        list.sortedWith(
            compareByDescending<Vehicle> { isKotaRegion(it) }
                .thenBy { it.vehicleNumber.uppercase() }
        )
}
