/* ============================================================
 * YuiTag 主应用逻辑与事件调度 (EFR32BG22)
 * ============================================================ */

#include "sl_bt_api.h"
#include "sl_bt_in_place_ota_dfu.h"
#include "gatt_db.h"
#include "sl_main_init.h"
#include "app_assert.h"
#include "app_timer.h"
#include "app.h"

// YuiTag 业务模块
#include "yuitag_config.h"
#include "yuitag_crypto.h"
#include "yuitag_platform.h"
#include "yuitag_device.h"
#include "yuitag_ble.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

/* --- 全局状态 --- */
static YuiTagDevice g_dev;
static bool         g_initialized = false;

// OTA DFU 授权标志 (已绑定用户发送 0x6F 指令验证后解锁)
static bool         g_ota_authorized = false;

// 连续快速上电出厂复位触发标记 (在延时前检测计数)
static bool         s_pending_factory_reset = false;

bool app_is_factory_reset_triggered(void)
{
    return s_pending_factory_reset;
}

// 心跳超时与电量采样周期定时器 (运行于 EM2 深度休眠模式)
static app_timer_t  s_periodic_timer;

/* --- 周期定时器回调 (唤醒主循环) --- */
static void periodic_timer_callback(app_timer_t *timer, void *data)
{
    (void)timer;
    (void)data;
    app_proceed();
}

/* --- 应用程序初始化 --- */
void app_init(void)
{
    // 初始化板级外设
    platform_init();

    // 连续上电重置检测在调试延时前执行，确保 7s 窗口在通电瞬间即刻记录有效计数
    s_pending_factory_reset = yuitag_device_power_cycle_check(&g_dev);

    // 开机延时 3s：供 SWD 调试器在进入深度休眠前连接 (仅冷启动执行)
    sl_sleeptimer_delay_millisecond(3000);

    // 初始化加密子系统
    yuitag_crypto_init();

    // 启动周期性心跳与电量检测定时器 (SDK自动处理EM2低功耗休眠)
    app_timer_start(&s_periodic_timer,
                    PERIODIC_CHECK_INTERVAL_MS,
                    periodic_timer_callback,
                    NULL,
                    true);
}

/* --- 主循环处理流程 (由 app_proceed 唤醒执行) --- */
void app_process_action(void)
{
    if (!app_is_process_required()) {
        return;
    }

    // 等待 BLE 栈 boot 事件完成初始化
    if (!g_initialized) {
        return;
    }

    yuitag_device_process_events(&g_dev);
}

/* --- 蓝牙栈底层事件总入口 (重写 SDK 弱符号实现) --- */
void sl_bt_on_event(sl_bt_msg_t *evt)
{
    if (SL_BT_MSG_ID(evt->header) == sl_bt_evt_system_boot_id) {
        g_initialized = false;
    }

    // 分发至 YuiTag BLE 处理层
    yuitag_ble_on_event(evt, &g_dev);

    // Boot 事件完成后在应用层执行闭环业务处理
    if (SL_BT_MSG_ID(evt->header) == sl_bt_evt_system_boot_id) {
        g_initialized = true;
        s_pending_factory_reset = false;

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
    }
}

/* --- OTA DFU 安全鉴权回调 (仅允许协议握手成功的已绑定设备升级) --- */
sl_bt_in_place_ota_dfu_security_sts_t sl_bt_in_place_ota_dfu_security_status(
    bd_addr address, uint8_t connection, uint8_t bonding)
{
    (void)address;
    (void)connection;
    (void)bonding;

    platform_log("[OTA-SEC] isBound=%d auth=%d bonding=%d",
                 g_dev.isBound, g_ota_authorized, bonding);

    if (g_dev.isBound && g_ota_authorized) {
        platform_log("[OTA-SEC] ACCEPT");
        return SL_BT_IN_PLACE_OTA_DFU_SECURITY_ACCEPT;
    }

    platform_log("[OTA-SEC] DENY");
    return SL_BT_IN_PLACE_OTA_DFU_SECURITY_DENY;
}

bool app_is_ota_authorized(void)
{
    return g_ota_authorized;
}

void app_ota_authorize(void)
{
    g_ota_authorized = true;
    platform_log("[OTA] Authorized");
}

void app_ota_deauthorize(void)
{
    g_ota_authorized = false;
}
