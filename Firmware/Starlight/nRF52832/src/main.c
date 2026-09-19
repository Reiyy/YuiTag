/* ============================================================
 * YuiTag 主程序入口 (nRF52832 / Zephyr RTOS)
 * ============================================================ */

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>

#include "yuitag_config.h"
#include "yuitag_platform.h"
#include "yuitag_crypto.h"
#include "yuitag_device.h"
#include "yuitag_buzzer_types.h"
#include "yuitag_ble.h"
#include "app.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

static YuiTagDevice g_dev;

static void bt_ready(int err)
{
    if (err) {
        platform_log("[BT] enable failed: %d", err);
        return;
    }

    platform_log("[BT] Ready");

    uint8_t mac[6];
    platform_get_mac(mac);
    yuitag_device_init(&g_dev, mac);

    /* 连续断电重启检测 (恢复出厂设置)；置于 device_init 之后以确保重置名称包含有效 shortUid */
    bool resetTriggered = yuitag_device_power_cycle_check(&g_dev);

    if (!resetTriggered) {
        yuitag_beep_startup();
    }

    yuitag_ble_set_device_name(g_dev.deviceName);
    yuitag_ble_update_dis_values(&g_dev);
    yuitag_ble_update_param_value(&g_dev);

    yuitag_ble_update_advertising(&g_dev, true);

#ifndef IS_SPARKS
    yuitag_findmy_init();
    yuitag_ble_update_param_value(&g_dev);
    if (g_dev.findmyEnabled) {
        if (!yuitag_findmy_enable()) {
            g_dev.findmyEnabled = false;
            platform_log("[FINDMY] Enable failed at boot, reverting flag");
            yuitag_ble_update_param_value(&g_dev);
        }
    }
#endif

    app_mark_initialized();
    platform_log("[BOOT] YuiTag initialized");
}

int main(void)
{
    platform_init();
    yuitag_crypto_init();
    app_init(&g_dev);
    yuitag_ble_init(&g_dev);

    int err = bt_enable(bt_ready);
    if (err) {
        platform_log("[BT] bt_enable failed: %d", err);
    }

    while (1) {
        k_sleep(K_FOREVER);
    }
    return 0;
}
