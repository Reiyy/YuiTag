// ============================================================
// YuiTag Apple Find My 平台广播实现 (EFR32BG22 / Simplicity SDK)
// 实现 yuitag_platform_api.h 声明的 platform_findmy_adv_* 接口
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"
#include "yuitag_findmy.h"

#include "sl_bt_api.h"
#include <string.h>

static uint8_t s_adv_handle = 0xFF;

static void build_findmy_adv_data(const uint8_t key[FINDMY_KEY_LEN],
                                  uint8_t status_byte,
                                  uint8_t buf[31])
{
    memset(buf, 0, 31);
    buf[0] = 0x1E;                          // 长度 (30 字节)
    buf[1] = 0xFF;                          // 广播类型：厂商自定义数据
    buf[2] = 0x4C;                          // Apple 厂商 ID (0x004C) 低字节
    buf[3] = 0x00;                          // Apple 厂商 ID 高字节
    buf[4] = 0x12;                          // 子类型：Offline Finding
    buf[5] = 0x19;                          // 数据长度 (25 字节)
    buf[6] = status_byte;                   // 状态字节 [7:6]电量 [5:0]滚动计数器
    memcpy(&buf[7], &key[6], 22);           // 公钥 X 坐标后 22 字节
    buf[29] = key[0] >> 6;                  // 公钥首字节高 2 位作为 hint
}

static void set_addr_from_key(const uint8_t key[FINDMY_KEY_LEN], bd_addr *addr)
{
    addr->addr[5] = key[0] | 0xC0;
    addr->addr[4] = key[1];
    addr->addr[3] = key[2];
    addr->addr[2] = key[3];
    addr->addr[1] = key[4];
    addr->addr[0] = key[5];
}

bool platform_findmy_adv_start(const uint8_t key[28], uint8_t status_byte)
{
    sl_status_t sc;

    if (s_adv_handle == 0xFF) {
        sc = sl_bt_advertiser_create_set(&s_adv_handle);
        if (sc != SL_STATUS_OK) {
            platform_log("[FINDMY] Create set failed: 0x%04X", sc);
            return false;
        }

        uint32_t interval_units = (FINDMY_ADV_INTERVAL_MS * 8U) / 5U;
        sl_bt_advertiser_set_timing(s_adv_handle,
                                     interval_units, interval_units, 0, 0);

        int16_t actual_power = 0;
        int16_t tx_powers[] = { 80, 70, 60, 50, 40, 30, 20, 10, 0 };
        for (size_t i = 0; i < sizeof(tx_powers) / sizeof(tx_powers[0]); i++) {
            if (tx_powers[i] > FINDMY_TX_POWER) continue;
            sl_status_t sc2 = sl_bt_advertiser_set_tx_power(s_adv_handle,
                                                              tx_powers[i], &actual_power);
            if (sc2 == SL_STATUS_OK) break;
        }
    } else {
        sl_bt_advertiser_stop(s_adv_handle);
    }

    bd_addr addr;
    set_addr_from_key(key, &addr);
    sc = sl_bt_advertiser_set_random_address(s_adv_handle,
                                              sl_bt_gap_static_address,
                                              addr, NULL);
    if (sc != SL_STATUS_OK) {
        platform_log("[FINDMY] Set addr failed: 0x%04X", sc);
        return false;
    }

    uint8_t advData[31];
    build_findmy_adv_data(key, status_byte, advData);
    sc = sl_bt_legacy_advertiser_set_data(s_adv_handle,
                                           sl_bt_advertiser_advertising_data_packet,
                                           sizeof(advData), advData);
    if (sc != SL_STATUS_OK) {
        platform_log("[FINDMY] Set adv data failed: 0x%04X", sc);
        return false;
    }

    sc = sl_bt_legacy_advertiser_start(s_adv_handle,
                                        sl_bt_legacy_advertiser_non_connectable);
    if (sc != SL_STATUS_OK) {
        platform_log("[FINDMY] Start adv failed: 0x%04X", sc);
        return false;
    }

    return true;
}

bool platform_findmy_adv_update_data(const uint8_t key[28], uint8_t status_byte)
{
    if (s_adv_handle == 0xFF) {
        return false;
    }

    uint8_t advData[31];
    build_findmy_adv_data(key, status_byte, advData);
    sl_status_t sc = sl_bt_legacy_advertiser_set_data(s_adv_handle,
                                                      sl_bt_advertiser_advertising_data_packet,
                                                      sizeof(advData), advData);
    return (sc == SL_STATUS_OK);
}

void platform_findmy_adv_stop(void)
{
    if (s_adv_handle != 0xFF) {
        sl_bt_advertiser_stop(s_adv_handle);
        sl_bt_advertiser_delete_set(s_adv_handle);
        s_adv_handle = 0xFF;
    }
}