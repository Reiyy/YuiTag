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
#include <zephyr/bluetooth/hci.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/util.h>

#if defined(CONFIG_BT_HCI_VS) || defined(CONFIG_BT_CTLR) || defined(CONFIG_BT_LL_SOFTDEVICE)
#include <zephyr/bluetooth/hci_vs.h>
#endif

#include <errno.h>
#include <string.h>

// Find My 广播使用专用 Identity 1 (派生自轮转密钥的随机地址)
// Identity 0 保留供主 BLE 协议广播与 GATT 连接使用
#define FINDMY_ID 1

static struct bt_le_ext_adv *s_adv = NULL;
static int8_t   s_findmy_tx_power = DEFAULT_FINDMY_TX_POWER;
static uint16_t s_findmy_adv_interval_ms = FINDMY_ADV_INTERVAL_DEFAULT_MS;
static uint8_t  s_cached_key[FINDMY_KEY_LEN];
static uint8_t  s_cached_status_byte = 0;
static bool     s_has_cached_key = false;

static void set_ext_adv_tx_power(struct bt_le_ext_adv *adv, int8_t power_dbm)
{
    if (!adv) return;
    int8_t valid_powers[] = { 4, 3, 0, -4, -8, -12, -16, -20, -40 };
    int8_t selected = -40;
    for (size_t i = 0; i < ARRAY_SIZE(valid_powers); i++) {
        if (power_dbm >= valid_powers[i]) {
            selected = valid_powers[i];
            break;
        }
    }

#if defined(BT_HCI_OP_VS_WRITE_TX_POWER_LEVEL)
    struct bt_hci_cp_vs_write_tx_power_level *cp;
    struct net_buf *buf, *rsp = NULL;

    buf = bt_hci_cmd_alloc(K_FOREVER);
    if (buf) {
        cp = net_buf_add(buf, sizeof(*cp));
        cp->handle_type = BT_HCI_VS_LL_HANDLE_TYPE_ADV;
        cp->handle = sys_cpu_to_le16(bt_le_ext_adv_get_index(adv));
        cp->tx_power_level = selected;

        int err = bt_hci_cmd_send_sync(BT_HCI_OP_VS_WRITE_TX_POWER_LEVEL, buf, &rsp);
        if (err) {
            platform_log("[FINDMY] Set TX power (%d dBm, handle=%d) failed: %d",
                         selected, bt_le_ext_adv_get_index(adv), err);
        } else {
            int8_t actual = selected;
            if (rsp && rsp->len >= sizeof(struct bt_hci_rp_vs_write_tx_power_level)) {
                struct bt_hci_rp_vs_write_tx_power_level *rp = (void *)rsp->data;
                actual = rp->selected_tx_power;
            }
            platform_log("[FINDMY] Set TX power: %d dBm (actual: %d dBm, handle=%d)",
                         selected, actual, bt_le_ext_adv_get_index(adv));
        }
        if (rsp) {
            net_buf_unref(rsp);
        }
    }
#else
    ARG_UNUSED(selected);
#endif
}

void platform_findmy_adv_set_tx_power(int8_t power_dbm)
{
    s_findmy_tx_power = power_dbm;
    if (s_adv) {
        set_ext_adv_tx_power(s_adv, power_dbm);
    }
}

void platform_findmy_adv_set_interval(uint16_t interval_ms)
{
    s_findmy_adv_interval_ms = interval_ms;
    if (s_adv && s_has_cached_key) {
        platform_findmy_adv_start(s_cached_key, s_cached_status_byte);
    }
}

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

    uint32_t units = ((uint32_t)s_findmy_adv_interval_ms * 8U) / 5U;
    uint32_t opt = BT_LE_ADV_OPT_USE_IDENTITY;
    struct bt_le_adv_param param = BT_LE_ADV_PARAM_INIT(
        opt, units, units, NULL);
    param.id = FINDMY_ID;

    err = bt_le_ext_adv_create(&param, NULL, &s_adv);
    if (err) {
        platform_log("[FINDMY] ext_adv_create failed: %d", err);
        return false;
    }

    set_ext_adv_tx_power(s_adv, s_findmy_tx_power);

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

    memcpy(s_cached_key, key, FINDMY_KEY_LEN);
    s_cached_status_byte = status_byte;
    s_has_cached_key = true;

    return true;
}

bool platform_findmy_adv_update_data(const uint8_t key[28], uint8_t status_byte)
{
    if (!s_adv) {
        return false;
    }

    memcpy(s_cached_key, key, FINDMY_KEY_LEN);
    s_cached_status_byte = status_byte;

    uint8_t raw[31];
    yuitag_findmy_build_adv_data(key, status_byte, raw);
    struct bt_data ad[] = {
        BT_DATA(BT_DATA_MANUFACTURER_DATA, &raw[2], 29),
    };

    return (bt_le_ext_adv_set_data(s_adv, ad, ARRAY_SIZE(ad), NULL, 0) == 0);
}

void platform_findmy_adv_stop(void)
{
    s_has_cached_key = false;
    if (s_adv) {
        bt_le_ext_adv_stop(s_adv);
        bt_le_ext_adv_delete(s_adv);
        s_adv = NULL;
    }
}
