package com.reiyy.yuitag.util

import com.reiyy.yuitag.data.local.AddressComponents

/**
 * 通用地理编码/逆地理编码解析结果实体。
 */
data class GeocodeAddress(
    val formattedAddress: String,
    val province: String,
    val city: String,
    val district: String,
    val township: String,
    val neighborhood: String,
    val building: String,
    val street: String,
    val number: String
) {
    /** 格式化显示地址；无 formattedAddress 时按层级组合 */
    fun display(): String {
        return formattedAddress.ifEmpty {
            listOf(province, city, district, township, street)
                .filter { it.isNotEmpty() }.joinToString("")
        }
    }

    /** 转换为数据库和展示所用的结构化组件 */
    fun toAddressComponents(): AddressComponents = AddressComponents(
        province = province.takeIf { it.isNotEmpty() },
        city = city.takeIf { it.isNotEmpty() },
        district = district.takeIf { it.isNotEmpty() },
        township = township.takeIf { it.isNotEmpty() },
        neighborhood = neighborhood.takeIf { it.isNotEmpty() },
        building = building.takeIf { it.isNotEmpty() },
        street = street.takeIf { it.isNotEmpty() },
        number = number.takeIf { it.isNotEmpty() }
    )
}

/** 兼容历史高德类型的类型别名 */
typealias AmapAddress = GeocodeAddress

/**
 * 逆地理编码服务提供商通用接口。
 */
interface GeocodeProvider {
    /** 从内存或磁盘缓存获取已解析的地址，不发起网络请求 */
    fun getCached(lat: Double, lon: Double): GeocodeAddress?

    /** 查询指定坐标的地址信息 */
    suspend fun lookup(lat: Double, lon: Double): GeocodeAddress?
}
