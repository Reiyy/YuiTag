package com.yuitag.finder.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_history",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["uid"],
            childColumns = ["deviceUid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("deviceUid"),
        Index("timestamp"),
        // 唯一索引，配合 INSERT OR IGNORE 实现去重
        Index(value = ["deviceUid", "timestamp"], unique = true)
    ]
)
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceUid: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Int,
    val confidence: Int,
    val timestamp: Long,
    val batteryStatus: Int,
    val counter: Int = 0,
    val periodIndex: Long? = null,        // 命中该记录的密钥所在 period 索引
    val hashedKey: String? = null,        // 命中该记录的哈希广告密钥 (Base64)
    val payloadType: String? = null,       // 负载格式诊断信息，如 "0x04 (88B)"、"flag(0x01) 89B"
    val address: String? = null,
    val shortAddress: String? = null,     // 精简地址（旧数据），仅列表页展示用
    val addressDetail: AddressComponents? = null,  // 结构化地址组件，列表页按所选层级动态展示
    val createdAt: Long = System.currentTimeMillis() / 1000
)

/** 地址显示层级（粗→细），展示时从所选层级向上逐级取第一个非空 */
enum class AddressLevel(val key: String, val label: String) {
    PROVINCE("province", "省"),
    CITY("city", "市"),
    DISTRICT("district", "区县"),
    TOWNSHIP("township", "街道/乡镇"),
    NEIGHBORHOOD("neighborhood", "社区/小区"),
    BUILDING("building", "建筑/楼宇"),
    STREET("street", "道路"),
    NUMBER("number", "门牌号");

    companion object {
        fun fromKey(key: String): AddressLevel =
            entries.firstOrNull { it.key == key } ?: STREET
    }
}

/** 结构化地址组件（Room 以 JSON 存储） */
data class AddressComponents(
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val township: String? = null,
    val neighborhood: String? = null,
    val building: String? = null,
    val street: String? = null,
    val number: String? = null
) {
    /** 从所选层级向上逐级取第一个非空值；全空返回空串 */
    fun resolve(level: AddressLevel): String {
        val field: (AddressLevel) -> String? = { l ->
            when (l) {
                AddressLevel.PROVINCE -> province
                AddressLevel.CITY -> city
                AddressLevel.DISTRICT -> district
                AddressLevel.TOWNSHIP -> township
                AddressLevel.NEIGHBORHOOD -> neighborhood
                AddressLevel.BUILDING -> building
                AddressLevel.STREET -> street
                AddressLevel.NUMBER -> number
            }
        }
        for (l in AddressLevel.entries.take(level.ordinal + 1).asReversed()) {
            // 兼容历史脏数据：高德缺失字段曾被误存为字面量 "[]" / "{}"
            val v = field(l)?.takeIf { it.isNotBlank() && it != "[]" && it != "{}" }
            if (v != null) return v
        }
        return ""
    }
}
