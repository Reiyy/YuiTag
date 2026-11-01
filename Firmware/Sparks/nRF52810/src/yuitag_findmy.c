// ============================================================
// YuiTag Sparks (nRF52810) Apple Find My 模块实现
// 特性: 预置固定公钥组轮换 (5~50 个公钥)
//       无需 ECC 椭圆曲线计算，内存与 Flash 占用极低
//       使用独立 Identity 1 配合 Extended Advertising 轮转随机地址
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

#define FINDMY_ID 1

static struct bt_le_ext_adv *s_adv = NULL;
static int8_t s_findmy_tx_power = DEFAULT_FINDMY_TX_POWER;
static bool   s_advertising = false;

static uint8_t  s_key_count = 0;
static uint8_t  s_current_idx = 0;
static uint32_t s_period_seconds = FINDMY_DEFAULT_PERIOD_SEC;
static uint32_t s_last_rotate_ms = 0;
static uint8_t  s_battery_status = 0;
static uint8_t  s_last_error = 0;

// ============================================================
// 射频发射功率控制
// ============================================================
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

// ============================================================
// BLE Identity 1 随机地址配置
// ============================================================
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

// ============================================================
// 广播底层启动/更新/停止 (实现 platform_findmy_adv_*)
// ============================================================
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
    uint32_t opt = BT_LE_ADV_OPT_USE_IDENTITY;
    struct bt_le_adv_param param = BT_LE_ADV_PARAM_INIT(opt, units, units, NULL);
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

// ============================================================
// Sparks 固定密钥管理与周期轮换
// ============================================================
static bool s_keys_load_key(uint8_t index, uint8_t key_out[FINDMY_KEY_LEN])
{
    if (index >= s_key_count) {
        return false;
    }
    size_t loaded = platform_storage_load_bytes(NVM3_KEY_FINDMY_KEY_BASE + index, key_out, FINDMY_KEY_LEN);
    return (loaded == FINDMY_KEY_LEN);
}

bool yuitag_findmy_reload_keys(void)
{
    uint16_t count = platform_storage_load_u16(NVM3_KEY_FINDMY_KEY_COUNT, 0);
    if (count < FINDMY_MIN_KEYS || count > FINDMY_MAX_KEYS) {
        s_key_count = 0;
        platform_log("[FINDMY] No valid keys in storage (count=%d)", count);
        return false;
    }

    s_key_count = (uint8_t)count;
    uint16_t curIdx = platform_storage_load_u16(NVM3_KEY_FINDMY_CURRENT_IDX, 0);
    s_current_idx = (curIdx < s_key_count) ? (uint8_t)curIdx : 0;
    platform_log("[FINDMY] Keys ready: %d keys, current_idx=%d", s_key_count, s_current_idx);

    if (s_advertising) {
        uint8_t curKey[FINDMY_KEY_LEN];
        if (s_keys_load_key(s_current_idx, curKey)) {
            platform_findmy_adv_start(curKey, s_battery_status);
        }
    }
    return true;
}

void yuitag_findmy_init(void)
{
    s_advertising = false;
    s_last_error = 0;
    s_last_rotate_ms = platform_millis();

    uint32_t storedPeriod = 0;
    size_t loaded = platform_storage_load_bytes(NVM3_KEY_FINDMY_PERIOD_SEC,
                                                (uint8_t *)&storedPeriod, sizeof(storedPeriod));
    if (loaded == sizeof(storedPeriod) && storedPeriod >= FINDMY_PERIOD_MIN_SEC && storedPeriod <= FINDMY_PERIOD_MAX_SEC) {
        s_period_seconds = storedPeriod;
    } else {
        s_period_seconds = FINDMY_DEFAULT_PERIOD_SEC;
    }

    yuitag_findmy_reload_keys();
}

void yuitag_findmy_tick(void)
{
    if (!s_advertising || s_key_count < FINDMY_MIN_KEYS) {
        return;
    }

    uint32_t now = platform_millis();
    if ((now - s_last_rotate_ms) >= (s_period_seconds * 1000UL)) {
        s_last_rotate_ms = now;
        s_current_idx = (s_current_idx + 1) % s_key_count;

        uint8_t nextKey[FINDMY_KEY_LEN];
        if (s_keys_load_key(s_current_idx, nextKey)) {
            platform_findmy_adv_start(nextKey, s_battery_status);
            platform_log("[FINDMY] Rotated to key %d/%d", s_current_idx, s_key_count);
        } else {
            platform_log("[FINDMY] Failed to load key %d/%d", s_current_idx, s_key_count);
        }
    }
}

void yuitag_findmy_update_battery(uint8_t battery_percent)
{
    uint8_t battery_bits;
    if (battery_percent > 80) {
        battery_bits = 0x00;
    } else if (battery_percent > 50) {
        battery_bits = 0x40;
    } else if (battery_percent > 30) {
        battery_bits = 0x80;
    } else {
        battery_bits = 0xC0;
    }

    uint8_t old_battery = s_battery_status & 0xC0;
    if (old_battery == battery_bits) {
        return;
    }

    s_battery_status = battery_bits | (s_battery_status & 0x3F);
    if (s_advertising && s_key_count > 0) {
        uint8_t curKey[FINDMY_KEY_LEN];
        if (s_keys_load_key(s_current_idx, curKey)) {
            platform_findmy_adv_update_data(curKey, s_battery_status);
        }
    }
}

bool yuitag_findmy_enable(void)
{
    if (s_key_count < FINDMY_MIN_KEYS) {
        platform_log("[FINDMY] Cannot enable: keys not configured (%d)", s_key_count);
        return false;
    }

    uint8_t curKey[FINDMY_KEY_LEN];
    if (!s_keys_load_key(s_current_idx, curKey)) {
        platform_log("[FINDMY] Failed to load key %d", s_current_idx);
        return false;
    }

    if (platform_findmy_adv_start(curKey, s_battery_status)) {
        s_advertising = true;
        s_last_rotate_ms = platform_millis();
        platform_log("[FINDMY] Enabled (key %d/%d)", s_current_idx, s_key_count);
        return true;
    }
    return false;
}

void yuitag_findmy_disable(void)
{
    platform_findmy_adv_stop();
    s_advertising = false;
    platform_log("[FINDMY] Disabled");
}

bool yuitag_findmy_is_enabled(void)
{
    return s_advertising;
}

void yuitag_findmy_set_period(uint32_t seconds)
{
    if (seconds < FINDMY_PERIOD_MIN_SEC) seconds = FINDMY_PERIOD_MIN_SEC;
    if (seconds > FINDMY_PERIOD_MAX_SEC) seconds = FINDMY_PERIOD_MAX_SEC;
    if (seconds == s_period_seconds) return;

    s_period_seconds = seconds;
    platform_storage_save_bytes(NVM3_KEY_FINDMY_PERIOD_SEC,
                                (uint8_t *)&s_period_seconds, sizeof(s_period_seconds));
    s_last_rotate_ms = platform_millis();
    platform_log("[FINDMY] Period set to %lu sec", seconds);
}

uint32_t yuitag_findmy_get_period(void)
{
    return s_period_seconds;
}

uint32_t yuitag_findmy_period_index_to_seconds(uint8_t index)
{
    switch (index) {
        case 0:  return 900;
        case 1:  return 1800;
        case 2:  return 3600;
        case 3:  return 7200;
        default: return 3600;
    }
}

uint8_t yuitag_findmy_period_seconds_to_index(uint32_t seconds)
{
    if (seconds <= 900)  return 0;
    if (seconds <= 1800) return 1;
    if (seconds <= 3600) return 2;
    return 3;
}

bool yuitag_findmy_get_current_key(uint8_t keyOut[FINDMY_KEY_LEN])
{
    if (s_key_count < FINDMY_MIN_KEYS) return false;
    return s_keys_load_key(s_current_idx, keyOut);
}

uint8_t yuitag_findmy_get_status(void)
{
    uint8_t status = 0;
    if (s_advertising)                  status |= 0x01;
    if (s_key_count >= FINDMY_MIN_KEYS) status |= 0x04;
    return status;
}

uint8_t yuitag_findmy_get_last_error(void)
{
    return s_last_error;
}

void yuitag_findmy_clear_seed(void)
{
    yuitag_findmy_disable();
    for (uint8_t i = 0; i < s_key_count && i < FINDMY_MAX_KEYS; i++) {
        platform_storage_remove(NVM3_KEY_FINDMY_KEY_BASE + i);
    }
    s_key_count = 0;
    s_current_idx = 0;
    platform_storage_remove(NVM3_KEY_FINDMY_KEY_COUNT);
    platform_storage_remove(NVM3_KEY_FINDMY_CURRENT_IDX);
    platform_storage_remove(NVM3_KEY_FINDMY_KEYS_BLOB);
    platform_log("[FINDMY] Keys cleared");
}

bool yuitag_findmy_has_keys(void)
{
    return (s_key_count >= FINDMY_MIN_KEYS);
}

uint8_t yuitag_findmy_get_key_count(void)
{
    return s_key_count;
}

uint8_t yuitag_findmy_get_current_key_index(void)
{
    return s_current_idx;
}
