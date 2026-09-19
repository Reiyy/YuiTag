// ============================================================
// YuiTag Apple Find My 平台广播实现 (nRF52832 / Zephyr)
// 实现 yuitag_platform_api.h 声明的 platform_findmy_adv_* 接口
// 使用独立 Identity (id 1) 配合 Extended Advertising 轮转随机地址
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"
#include "yuitag_findmy.h"
#include "yuitag_adv.h"

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/addr.h>
#include <zephyr/sys/util.h>

#include <errno.h>
#include <string.h>

// Find My 广播使用专用 Identity 1 (派生自轮转密钥的随机地址)
// Identity 0 保留供主 BLE 协议广播与 GATT 连接使用
#define FINDMY_ID 1

static struct bt_le_ext_adv *s_adv = NULL;

static bool ensure_identity(const uint8_t key[FINDMY_KEY_LEN])
{
    bt_addr_le_t addrs[CONFIG_BT_ID_MAX];
    size_t count = ARRAY_SIZE(addrs);

    bt_id_get(addrs, &count);
    if (count > FINDMY_ID) {
        return true;
    }

    bt_addr_le_t addr;
    addr.type = BT_ADDR_LE_RANDOM;
    yuitag_findmy_key_to_addr(key, addr.a.val);
    int err = bt_id_create(&addr, NULL);
    if (err < 0 && err != -EALREADY) {
        platform_log("[FINDMY] id_create failed: %d", err);
        return false;
    }
    return true;
}

bool platform_findmy_adv_start(const uint8_t key[28], uint8_t status_byte)
{
    int err;

    if (s_adv) {
        bt_le_ext_adv_stop(s_adv);
        bt_le_ext_adv_delete(s_adv);
        s_adv = NULL;
    }

    if (!ensure_identity(key)) {
        return false;
    }

    bt_addr_le_t addr;
    addr.type = BT_ADDR_LE_RANDOM;
    yuitag_findmy_key_to_addr(key, addr.a.val);
    err = bt_id_reset(FINDMY_ID, &addr, NULL);
    if (err < 0 && err != -EALREADY) {
        platform_log("[FINDMY] id_reset failed: %d", err);
        return false;
    }

    uint32_t units = (FINDMY_ADV_INTERVAL_MS * 8U) / 5U;
    struct bt_le_adv_param param = BT_LE_ADV_PARAM_INIT(
        BT_LE_ADV_OPT_USE_IDENTITY, units, units, NULL);
    param.id = FINDMY_ID;

    err = bt_le_ext_adv_create(&param, NULL, &s_adv);
    if (err) {
        platform_log("[FINDMY] ext_adv_create failed: %d", err);
        return false;
    }

    uint8_t raw[31];
    yuitag_findmy_build_adv_data(key, status_byte, raw);
    struct bt_data ad[] = {
        BT_DATA(BT_DATA_MANUFACTURER_DATA, &raw[2], 29),
    };

    err = bt_le_ext_adv_set_data(s_adv, ad, ARRAY_SIZE(ad), NULL, 0);
    if (err) {
        platform_log("[FINDMY] set_data failed: %d", err);
        return false;
    }

    err = bt_le_ext_adv_start(s_adv, BT_LE_EXT_ADV_START_DEFAULT);
    if (err) {
        platform_log("[FINDMY] adv start failed: %d", err);
        return false;
    }

    return true;
}

bool platform_findmy_adv_update_data(const uint8_t key[28], uint8_t status_byte)
{
    if (!s_adv) {
        return false;
    }

    uint8_t raw[31];
    yuitag_findmy_build_adv_data(key, status_byte, raw);
    struct bt_data ad[] = {
        BT_DATA(BT_DATA_MANUFACTURER_DATA, &raw[2], 29),
    };

    return (bt_le_ext_adv_set_data(s_adv, ad, ARRAY_SIZE(ad), NULL, 0) == 0);
}

void platform_findmy_adv_stop(void)
{
    if (s_adv) {
        bt_le_ext_adv_stop(s_adv);
        bt_le_ext_adv_delete(s_adv);
        s_adv = NULL;
    }
}
