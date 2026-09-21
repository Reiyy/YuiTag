package com.reiyy.yuitag.data.local

import androidx.room.TypeConverter
import org.json.JSONObject

class Converters {
    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? =
        value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? =
        value?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    @TypeConverter
    fun fromAddressComponents(value: AddressComponents?): String? =
        value?.let {
            JSONObject().apply {
                it.province?.let { put("province", it) }
                it.city?.let { put("city", it) }
                it.district?.let { put("district", it) }
                it.township?.let { put("township", it) }
                it.neighborhood?.let { put("neighborhood", it) }
                it.building?.let { put("building", it) }
                it.street?.let { put("street", it) }
                it.number?.let { put("number", it) }
            }.toString()
        }

    @TypeConverter
    fun toAddressComponents(value: String?): AddressComponents? {
        if (value.isNullOrBlank()) return null
        return try {
            val o = JSONObject(value)
            fun opt(s: String): String? = o.optString(s)
                .takeIf { it.isNotEmpty() && it != "[]" && it != "{}" }
            AddressComponents(
                province = opt("province"),
                city = opt("city"),
                district = opt("district"),
                township = opt("township"),
                neighborhood = opt("neighborhood"),
                building = opt("building"),
                street = opt("street"),
                number = opt("number")
            )
        } catch (_: Exception) {
            null
        }
    }
}
