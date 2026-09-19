// ============================================================
// YuiTag Apple Find My 平台广播实现 (EFR32BG22 / Simplicity SDK)
// 实现 yuitag_platform_api.h 声明的 platform_findmy_adv_* 接口
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"
#include "yuitag_findmy.h"
#include "yuitag_adv.h"

#include "sl_bt_api.h"
#include <string.h>

static uint8_t s_adv_handle = 0xFF;

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
    yuitag_findmy_key_to_addr(key, addr.addr);
    sc = sl_bt_advertiser_set_random_address(s_adv_handle,
                                              sl_bt_gap_static_address,
                                              addr, NULL);
    if (sc != SL_STATUS_OK) {
        platform_log("[FINDMY] Set addr failed: 0x%04X", sc);
        return false;
    }

    uint8_t advData[31];
    yuitag_findmy_build_adv_data(key, status_byte, advData);
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
    yuitag_findmy_build_adv_data(key, status_byte, advData);
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