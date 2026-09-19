package com.yuitag.finder.ble

/**
 * MCUmgr SMP (Simple Management Protocol) 协议常量与帧编解码。
 * 协议版本固定使用 v1（original protocol，nh_version=0）。
 *
 * 帧格式（小端，8 字节头）：
 *   [0]   op(低3位) | version(位3-4)
 *   [1]   flags (0)
 *   [2:3] len   (大端 uint16，CBOR 负载长度)
 *   [4:5] group (大端 uint16)
 *   [6]   seq
 *   [7]   id
 */
object McumgrProtocol {

    const val SMP_SERVICE_UUID = "8D53DC1D-1DB7-4CD3-868B-8A527460AA84"
    const val SMP_CHAR_UUID = "DA2E7828-FBCE-4E01-AE9E-261174997C48"

    // op
    const val OP_READ = 0
    const val OP_READ_RSP = 1
    const val OP_WRITE = 2
    const val OP_WRITE_RSP = 3

    // group
    const val GROUP_OS = 0
    const val GROUP_IMG = 1

    // img_mgmt 命令 id
    const val IMG_STATE = 0
    const val IMG_UPLOAD = 1
    const val IMG_ERASE = 5

    // os_mgmt 命令 id
    const val OS_RESET = 5

    const val HEADER_SIZE = 8

    data class Response(
        val op: Int,
        val group: Int,
        val seq: Int,
        val id: Int,
        val payload: CborValue
    ) {
        /** 顶层错误码（v1 协议下由 smp_on_err 生成 {"rc": int}），无则返回 null */
        val rc: Long? get() = rcOf(payload)

        /** v2 格式内联错误 {"err": {"group":G,"rc":R}}，无则返回 null */
        val err: Pair<Long, Long>? get() = errOf(payload)
    }

    fun buildRequest(op: Int, group: Int, id: Int, seq: Int, cbor: ByteArray): ByteArray {
        val len = cbor.size
        val out = ByteArray(HEADER_SIZE + len)
        // 字节 0：version=0（v1），op 占低 3 位
        out[0] = (op and 0x07).toByte()
        out[1] = 0 // flags
        out[2] = ((len shr 8) and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        out[4] = ((group shr 8) and 0xFF).toByte()
        out[5] = (group and 0xFF).toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = (id and 0xFF).toByte()
        System.arraycopy(cbor, 0, out, HEADER_SIZE, len)
        return out
    }

    fun parseResponse(data: ByteArray): Response? {
        if (data.size < HEADER_SIZE) return null
        val op = data[0].toInt() and 0x07
        val len = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val group = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val seq = data[6].toInt() and 0xFF
        val id = data[7].toInt() and 0xFF
        val payloadEnd = minOf(data.size, HEADER_SIZE + len)
        val cborBytes = data.copyOfRange(HEADER_SIZE, payloadEnd)
        val payload = if (cborBytes.isEmpty()) {
            CborValue.MapVal(LinkedHashMap())
        } else {
            try {
                Cbor.decode(cborBytes)
            } catch (_: Exception) {
                CborValue.MapVal(LinkedHashMap())
            }
        }
        return Response(op, group, seq, id, payload)
    }

    // --- 命令构造 ---

    /** image list（读 img_mgmt state） */
    fun imageListRequest(seq: Int): ByteArray =
        buildRequest(OP_READ, GROUP_IMG, IMG_STATE, seq, Cbor.encodeMap(emptyList()))

    /**
     * image upload（写 img_mgmt upload）。
     * 单核设备（如 nRF52832）image 恒为 0。
     * 首个分块（off==0）携带 len；后续分块仅携带 image, off, data，减少数据开销。
     */
    fun imageUploadRequest(
        seq: Int,
        off: Int,
        totalLen: Int,
        data: ByteArray
    ): ByteArray {
        val mapEntries = mutableListOf(
            "image" to Cbor.encodeUInt(0),
            "off" to Cbor.encodeUInt(off.toLong()),
            "data" to Cbor.encodeBytes(data)
        )
        if (off == 0) {
            mapEntries.add("len" to Cbor.encodeUInt(totalLen.toLong()))
        }
        return buildRequest(OP_WRITE, GROUP_IMG, IMG_UPLOAD, seq, Cbor.encodeMap(mapEntries))
    }

    /**
     * image state write（写 img_mgmt state，执行 test 或 confirm）。
     * @param hash 目标镜像的 SHA256 哈希
     * @param confirm true 为永久确认（confirm），false 为测试运行（test）
     */
    fun imageStateWriteRequest(seq: Int, hash: ByteArray, confirm: Boolean): ByteArray {
        val cbor = Cbor.encodeMap(
            listOf(
                "hash" to Cbor.encodeBytes(hash),
                "confirm" to Cbor.encodeBool(confirm)
            )
        )
        return buildRequest(OP_WRITE, GROUP_IMG, IMG_STATE, seq, cbor)
    }

    /** reset（写 os_mgmt reset） */
    fun resetRequest(seq: Int): ByteArray =
        buildRequest(OP_WRITE, GROUP_OS, OS_RESET, seq, Cbor.encodeMap(emptyList()))

    // --- 响应解析辅助 ---

    fun rcOf(payload: CborValue): Long? =
        (payload as? CborValue.MapVal)?.entries?.get("rc")?.asLong()

    fun offOf(payload: CborValue): Long? =
        (payload as? CborValue.MapVal)?.entries?.get("off")?.asLong()

    fun errOf(payload: CborValue): Pair<Long, Long>? {
        val err = (payload as? CborValue.MapVal)?.entries?.get("err") as? CborValue.MapVal
            ?: return null
        val g = err.entries["group"]?.asLong() ?: return null
        val rc = err.entries["rc"]?.asLong() ?: return null
        return g to rc
    }

    /** 判断响应是否表示成功（rc 缺失或为 0） */
    fun isOk(resp: Response): Boolean {
        resp.err?.let { return false }
        val rc = resp.rc ?: return true
        return rc == 0L
    }

    data class SlotInfo(
        val slot: Int,
        val version: String?,
        val hash: ByteArray?,
        val bootable: Boolean,
        val pending: Boolean,
        val confirmed: Boolean,
        val active: Boolean
    )

    /** 从 IMG_STATE 响应中解析各 slot 状态 */
    fun parseImages(payload: CborValue): List<SlotInfo> {
        val map = (payload as? CborValue.MapVal)?.entries ?: return emptyList()
        val imagesArray = (map["images"] as? CborValue.ArrayVal)?.items ?: return emptyList()
        return imagesArray.mapNotNull { item ->
            val slotMap = (item as? CborValue.MapVal)?.entries ?: return@mapNotNull null
            val slot = slotMap["slot"]?.asLong()?.toInt() ?: return@mapNotNull null
            val version = slotMap["version"]?.asText()
            val hash = slotMap["hash"]?.asBytes()
            val bootable = slotMap["bootable"]?.asBool() ?: true
            val pending = slotMap["pending"]?.asBool() ?: false
            val confirmed = slotMap["confirmed"]?.asBool() ?: false
            val active = slotMap["active"]?.asBool() ?: false
            SlotInfo(slot, version, hash, bootable, pending, confirmed, active)
        }
    }
}
