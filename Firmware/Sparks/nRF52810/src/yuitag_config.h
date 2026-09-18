#ifndef YUITAG_CONFIG_H
#define YUITAG_CONFIG_H

// ============================================================
// YuiTag Sparks (nRF52810) 平台配置
// 架构: nRF Connect SDK / Zephyr RTOS
// ============================================================

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// ---- 版本标识: Sparks (低配版，无 FindMy / 无 ECDH / 无 OTA) ----
#define IS_SPARKS

// ---- 编译期日志开关与输出宏 ----
#ifndef YUITAG_LOG_ENABLE
#define YUITAG_LOG_ENABLE           0       // 0: 关闭日志输出, 1: 开启日志输出
#endif

#if YUITAG_LOG_ENABLE
#include <zephyr/sys/printk.h>
#define platform_log(fmt, ...)  printk(fmt "\n", ##__VA_ARGS__)
#else
#define platform_log(fmt, ...)  ((void)0)
#endif

// ---- 固件与设备信息 ----
#define FW_VERSION                  "1.0.0|NCS"
#define HW_VERSION                  "V1|NRF52"
#define MANUFACTURER                "Yuitwork Labs 唯晴科技"
#define MODEL_NUMBER                "YuiTag Sparks|YLS05N"
#define DEVICE_NAME_PREFIX          "YuiTag Sparks_"

// ---- 引入跨平台公共协议常量 ----
#include "yuitag_config_common.h"

#endif // YUITAG_CONFIG_H
