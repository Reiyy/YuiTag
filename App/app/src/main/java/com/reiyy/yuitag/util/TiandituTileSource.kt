package com.reiyy.yuitag.util

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * 天地图 (Tianditu) WMTS 瓦片源。
 *
 * 官方服务地址：https://lbs.tianditu.gov.cn/server/MapService.html
 * 使用 `_w` 系列（EPSG:900913 球面墨卡托，与 OSM 同一瓦片网格、WGS-84 坐标，无需坐标转换）。
 *
 * 图层：
 *  - vec 矢量底图 / cva 矢量注记
 *  - img 影像底图 / cia 影像注记
 *  - ter 地形晕渲 / cta 地形注记
 *
 * 密钥说明：
 * key 为 URL 参数 tk，需在天地图控制台 (console.tianditu.gov.cn) 申请【服务端】API Key，
 * 独立配置，不与逆地理编码所需的【浏览器端】Key 复用。不填时上层回退 OSM。
 */
class TiandituTileSource(
    name: String,
    private val layer: String,
    key: String,
    subdomains: Array<String> = DEFAULT_SUBDOMAINS,
    maxZoom: Int = MAX_ZOOM
) : OnlineTileSourceBase(
    name,
    MIN_ZOOM,
    maxZoom,
    TILE_SIZE,
    "",
    subdomains.map { buildTileUrl(it, layer, key) }.toTypedArray(),
    "© 天地图",
    TileSourcePolicy()
) {

    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return getBaseUrl()
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }

    companion object {
        const val MIN_ZOOM = 1
        const val MAX_ZOOM = 18
        /** 地形晕渲底图(ter)服务端最高仅提供到 14 级，超过后瓦片缺失 */
        const val MAX_ZOOM_TERRAIN = 14
        const val TILE_SIZE = 256

        const val STYLE_VECTOR = "vector"
        const val STYLE_SATELLITE = "satellite"
        const val STYLE_TERRAIN = "terrain"

        private val DEFAULT_SUBDOMAINS = arrayOf("t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7")

        private fun buildTileUrl(subdomain: String, layer: String, key: String): String =
            "https://$subdomain.tianditu.gov.cn/${layer}_w/wmts?" +
                "SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer&STYLE=default&" +
                "TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk=$key"
    }
}
