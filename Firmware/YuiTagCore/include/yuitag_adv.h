#ifndef YUITAG_ADV_H
#define YUITAG_ADV_H

// ============================================================
// YuiTag 广播数据载荷构造 (跨平台核心)
// 负责: 主广播包 (AD) 与扫描响应包 (SD) 的字段选择、
//       厂商数据 / 电量服务数据 / 诊断数据布局、
//       Apple Find My 离线寻物广播载荷与随机地址推导。
// 各平台仅需将构造出的段 (seg) 映射到自身 BLE 协议栈 API
// (Zephyr: struct bt_data; SiLabs: LTV 序列化)。
// ============================================================

#include "yuitag_config_common.h"
#include "yuitag_device.h"
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// ---- AD 结构类型值 (与 BLE 规范 Assigned Numbers 一致) ----
#define YUITAG_ADV_TYPE_FLAGS     0x01  // Flags
#define YUITAG_ADV_TYPE_NAME      0x09  // Complete Local Name
#define YUITAG_ADV_TYPE_SVC16     0x16  // Service Data - 16-bit UUID
#define YUITAG_ADV_TYPE_MFR       0xFF  // Manufacturer Specific Data

// 诊断服务数据 UUID (仅 YUITAG_FINDMY_DEBUG 构建)
#define YUITAG_ADV_DIAG_UUID16    0xFFF0

// 单个 AD 结构段: type + 载荷指针 + 载荷长度 (不含 LTV 长度字节)
typedef struct {
    uint8_t        type;
    const uint8_t *data;
    uint8_t        len;
} yuitag_adv_seg_t;

// 构造主广播包字段段序列 (flags + 厂商数据 + [绑定后]电量 + [debug]诊断)
// 载荷写入调用方提供的 storage, 返回段数量; segs 指针仅在 storage 生命周期内有效
// 建议: segs 容量 >= 4, storage >= 32 字节
size_t yuitag_adv_build(const YuiTagDevice *dev,
                        yuitag_adv_seg_t *segs, size_t maxSegs,
                        uint8_t *storage, size_t storageSize);

// 构造扫描响应包字段段序列 (完整设备名)
// 建议: segs 容量 >= 1, storage >= DEVICE_NAME_MAX_LEN + 1 字节
size_t yuitag_adv_build_scan_rsp(const YuiTagDevice *dev,
                                 yuitag_adv_seg_t *segs, size_t maxSegs,
                                 uint8_t *storage, size_t storageSize);

// ---- Apple Find My 广播载荷与地址 (仅 Starlight) ----
#ifndef IS_SPARKS
// 构造 31 字节 Find My 离线寻物广播原始载荷 (含 LTV 头)
void yuitag_findmy_build_adv_data(const uint8_t key[FINDMY_KEY_LEN],
                                  uint8_t status_byte,
                                  uint8_t buf[31]);
// 由轮转公钥 X 推导静态随机地址: [k5..k1, k0|0xC0]
void yuitag_findmy_key_to_addr(const uint8_t key[FINDMY_KEY_LEN],
                               uint8_t addrOut[6]);
#endif // !IS_SPARKS

#endif // YUITAG_ADV_H
