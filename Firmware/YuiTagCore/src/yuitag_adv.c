// ============================================================
// YuiTag 广播数据载荷构造实现 (跨平台核心)
// 所有广播字段的字节布局在此唯一定义, 各平台不得自行拼装。
// ============================================================

#include "yuitag_config.h"
#include "yuitag_adv.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

#include <string.h>

// Flags: LE General Discoverable + BR/EDR Not Supported
#define YUITAG_ADV_FLAGS_VALUE    0x06

// 厂商数据协议版本字节
#define YUITAG_ADV_MFR_VERSION    0x01

size_t yuitag_adv_build(const YuiTagDevice *dev,
                        yuitag_adv_seg_t *segs, size_t maxSegs,
                        uint8_t *storage, size_t storageSize)
{
    if (!dev || !segs || !storage || maxSegs < 3) {
        return 0;
    }

    size_t pos   = 0;
    size_t count = 0;

    // ---- Flags ----
    if (pos + 1 > storageSize) {
        return 0;
    }
    storage[pos] = YUITAG_ADV_FLAGS_VALUE;
    segs[count].type = YUITAG_ADV_TYPE_FLAGS;
    segs[count].data = &storage[pos];
    segs[count].len  = 1;
    pos += 1;
    count++;

    // ---- 厂商自定义数据 ----
    // 绑定:   [CompanyID(2)][版本(1)][ShortUID(2)]            = 5 字节
    // 未绑定: [CompanyID(2)][版本(1)][ShortUID(2)][标志=0x00] = 6 字节
    size_t mfrLen = dev->isBound ? 5 : 6;
    if (pos + mfrLen > storageSize) {
        return 0;
    }
    storage[pos + 0] = YUITAG_COMPANY_ID & 0xFF;
    storage[pos + 1] = (YUITAG_COMPANY_ID >> 8) & 0xFF;
    storage[pos + 2] = YUITAG_ADV_MFR_VERSION;
    storage[pos + 3] = dev->shortUid[0];
    storage[pos + 4] = dev->shortUid[1];
    if (!dev->isBound) {
        storage[pos + 5] = 0x00;
    }
    segs[count].type = YUITAG_ADV_TYPE_MFR;
    segs[count].data = &storage[pos];
    segs[count].len  = (uint8_t)mfrLen;
    pos += mfrLen;
    count++;

    // ---- 电量服务数据 (仅已绑定状态广播, UUID 0x180F) ----
    if (dev->isBound) {
        if (pos + 3 > storageSize) {
            return 0;
        }
        storage[pos + 0] = UUID16_BATT & 0xFF;
        storage[pos + 1] = (UUID16_BATT >> 8) & 0xFF;
        storage[pos + 2] = dev->batteryLevel;
        segs[count].type = YUITAG_ADV_TYPE_SVC16;
        segs[count].data = &storage[pos];
        segs[count].len  = 3;
        pos += 3;
        count++;
    }

#ifdef YUITAG_FINDMY_DEBUG
    // ---- 诊断服务数据 (UUID 0xFFF0, 仅 debug 构建) ----
    // [UUID16(2)][findmyEnabled][状态掩码][最近错误码][保留=0][FindMy地址(6)]
    if (count < maxSegs) {
        if (pos + 12 > storageSize) {
            return 0;
        }
        storage[pos + 0] = YUITAG_ADV_DIAG_UUID16 & 0xFF;
        storage[pos + 1] = (YUITAG_ADV_DIAG_UUID16 >> 8) & 0xFF;
#ifndef IS_SPARKS
        storage[pos + 2] = dev->findmyEnabled ? 1 : 0;
        storage[pos + 3] = yuitag_findmy_get_status();
        storage[pos + 4] = yuitag_findmy_get_last_error();
        storage[pos + 5] = 0;
        yuitag_findmy_get_current_addr(&storage[pos + 6]);
#else
        memset(&storage[pos + 2], 0, 10);
#endif
        segs[count].type = YUITAG_ADV_TYPE_SVC16;
        segs[count].data = &storage[pos];
        segs[count].len  = 12;
        pos += 12;
        count++;
    }
#endif

    return count;
}

size_t yuitag_adv_build_scan_rsp(const YuiTagDevice *dev,
                                 yuitag_adv_seg_t *segs, size_t maxSegs,
                                 uint8_t *storage, size_t storageSize)
{
    if (!dev || !segs || !storage || maxSegs < 1) {
        return 0;
    }

    size_t nameLen = strlen(dev->deviceName);
    if (nameLen > DEVICE_NAME_MAX_LEN) {
        nameLen = DEVICE_NAME_MAX_LEN;
    }
    if (nameLen > storageSize) {
        return 0;
    }

    memcpy(storage, dev->deviceName, nameLen);
    segs[0].type = YUITAG_ADV_TYPE_NAME;
    segs[0].data = storage;
    segs[0].len  = (uint8_t)nameLen;
    return 1;
}

#ifndef IS_SPARKS
void yuitag_findmy_build_adv_data(const uint8_t key[FINDMY_KEY_LEN],
                                  uint8_t status_byte,
                                  uint8_t buf[31])
{
    memset(buf, 0, 31);
    buf[0] = 0x1E;                    // LTV 长度 (30 字节)
    buf[1] = 0xFF;                    // AD 类型: 厂商自定义数据
    buf[2] = 0x4C;                    // Apple 厂商 ID (0x004C) 低字节
    buf[3] = 0x00;                    // Apple 厂商 ID 高字节
    buf[4] = 0x12;                    // 子类型: Offline Finding
    buf[5] = 0x19;                    // 数据长度 (25 字节)
    buf[6] = status_byte;             // 状态字节 [7:6]电量 [5:0]滚动计数器
    memcpy(&buf[7], &key[6], 22);     // 公钥 X 坐标后 22 字节
    buf[29] = key[0] >> 6;            // 公钥首字节高 2 位作为 hint
}

void yuitag_findmy_key_to_addr(const uint8_t key[FINDMY_KEY_LEN],
                               uint8_t addrOut[6])
{
    addrOut[0] = key[5];
    addrOut[1] = key[4];
    addrOut[2] = key[3];
    addrOut[3] = key[2];
    addrOut[4] = key[1];
    addrOut[5] = key[0] | 0xC0;       // 静态随机地址标志 (最高两位置 1)
}
#endif // !IS_SPARKS
