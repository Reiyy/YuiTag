#ifndef YUITAG_CONFIG_H
#define YUITAG_CONFIG_H

// ============================================================
// YuiTag Starlight (nRF52832) 平台配置
// 架构: nRF Connect SDK / Zephyr RTOS
// 版本: Starlight (支持 FindMy / ECDH 绑定 / OTA)
// ============================================================

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// ---- 编译期日志开关与输出宏 ----
#ifndef YUITAG_LOG_ENABLE
#define YUITAG_LOG_ENABLE  0
#endif

#if YUITAG_LOG_ENABLE
#include <zephyr/sys/printk.h>
#define platform_log(fmt, ...)  printk(fmt "\n", ##__VA_ARGS__)
#else
#define platform_log(fmt, ...)  ((void)0)
#endif

// ---- 固件与设备信息 ----
#define FW_VERSION         "1.0.0|NCS"
#define HW_VERSION         "V1|NRF52"
#define MANUFACTURER       "Yuitwork Labs 唯晴科技"
#define MODEL_NUMBER       "YuiTag Starlight|YLS05P"
#define DEVICE_NAME_PREFIX "YuiTag Starlight_"

// ---- 基础广播发射功率 (单位: 0.1 dBm) ----
#ifndef BLE_TX_POWER
#define BLE_TX_POWER  0
#endif

// ---- 引入跨平台公共协议常量 ----
#include "yuitag_config_common.h"

#endif // YUITAG_CONFIG_H
