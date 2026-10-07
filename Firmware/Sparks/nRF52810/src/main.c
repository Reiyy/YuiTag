// ============================================================
// YuiTag 固件程序入口与启动时序
// 硬件平台: Nordic nRF52810 / nRF Connect SDK (Zephyr)
// ============================================================

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>

#include "yuitag_config.h"
#include "yuitag_platform.h"
#include "yuitag_crypto.h"
#include "yuitag_device.h"
#include "yuitag_buzzer_types.h"
#include "yuitag_ble.h"
#include "yuitag_findmy.h"
#include "app.h"

static YuiTagDevice g_dev;

// 蓝牙协议栈异步初始化就绪回调
static void bt_ready(int err)
{
    if (err) {
        platform_log("[BT] enable failed: %d", err);
        return;
    }

    platform_log("[BT] Ready");

    // 1. 获取芯片硬件 MAC 地址并初始化设备状态机与配置
    uint8_t mac[6];
    platform_get_mac(mac);
    yuitag_device_init(&g_dev, mac);

    // 2. 检测短时连续快速上电 (防抖窗口内达 5 次触发恢复出厂)
    bool resetTriggered = yuitag_device_power_cycle_check(&g_dev);

    // 3. 正常开机蜂鸣提示音 (若触发出厂复位则跳过，避免覆盖复位提示音)
    if (!resetTriggered) {
        yuitag_beep_startup();
    }

    // 4. 同步设备名称至 BLE 协议栈及 DIS 信息服务
    yuitag_ble_set_device_name(g_dev.deviceName);
    yuitag_ble_update_dis_values(&g_dev);
    yuitag_ble_update_param_value(&g_dev);

    // 5. 启动 BLE 广播 (未绑定时慢广播，已绑定时常态广播)
    yuitag_ble_update_advertising(&g_dev, true);

    // 6. 初始化 Find My 模块并在使能时启动轮换广播
    yuitag_findmy_init();
    yuitag_ble_update_param_value(&g_dev);
    if (g_dev.findmyEnabled) {
        if (!yuitag_findmy_enable()) {
            g_dev.findmyEnabled = false;
            platform_log("[FINDMY] Enable failed at boot, reverting flag");
            yuitag_ble_update_param_value(&g_dev);
        } else {
            yuitag_findmy_update_battery(g_dev.batteryLevel);
        }
    }

    // 7. 标记系统初始化完成，使能异步工作队列事件调度
    app_mark_initialized();
    platform_log("[BOOT] YuiTag initialized");
}

int main(void)
{
    // 基础硬件平台与外设初始化 (NVS 存储挂载、PWM 蜂鸣器引脚)
    platform_init();

    // 软件加密工具层初始化
    yuitag_crypto_init();

    // 异步工作队列调度器与定时器初始化
    app_init(&g_dev);

    // 注册 BLE 协议栈连接与 GATT 回调
    yuitag_ble_init(&g_dev);

    // 异步启动 Zephyr 蓝牙协议栈
    int err = bt_enable(bt_ready);
    if (err) {
        platform_log("[BT] bt_enable failed: %d", err);
    }

    // 主线程永久休眠，完全由中断与系统工作队列驱动，达到最大程度低功耗
    while (1) {
        k_sleep(K_FOREVER);
    }
    return 0;
}