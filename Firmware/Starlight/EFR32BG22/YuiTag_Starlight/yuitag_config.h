#ifndef YUITAG_CONFIG_H
#define YUITAG_CONFIG_H

/**
 * @file yuitag_config.h
 * @brief YuiTag 固件全局配置与协议常量定义
 * @platform Silicon Labs EFR32BG22 / Simplicity Studio v5
 * @version Starlight (支持 FindMy / ECDH 绑定 / OTA)
 */

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

/* --- 编译开关 --- */
#ifndef YUITAG_LOG_ENABLE
#define YUITAG_LOG_ENABLE  0        // 日志输出开关：1 开启，0 关闭
#endif

#if YUITAG_LOG_ENABLE
#include "sl_component_catalog.h"
#if defined(SL_CATALOG_APP_LOG_PRESENT)
#include "app_log.h"
#define platform_log(fmt, ...)  app_log_info(fmt APP_LOG_NL, ##__VA_ARGS__)
#else
#define platform_log(fmt, ...)  ((void)0)
#endif
#else
#define platform_log(fmt, ...)  ((void)0)
#endif

/* --- 固件与设备信息 --- */
#define FW_VERSION                 "1.0.0|SSv5"             // 固件版本 | SDK版本
#define HW_VERSION                 "V1|BG22"                 // 硬件版本 | 芯片型号
#define MANUFACTURER               "Yuitwork Labs 唯晴科技"   // 制造商名称
#define MODEL_NUMBER               "YuiTag Starlight|YLS05P" // 产品型号 | 内部代号
#define DEVICE_NAME_PREFIX         "YuiTag Starlight_"       // 默认蓝牙名称前缀

#ifndef HW_TX_POWER_MIN
#define HW_TX_POWER_MIN            (-28)   // BG22 硬件支持最低发射功率 (dBm)
#endif
#ifndef HW_TX_POWER_MAX
#define HW_TX_POWER_MAX            6       // BG22 硬件支持最高发射功率 (dBm)
#endif

#ifndef BLE_TX_POWER
#define BLE_TX_POWER               0       // 普通广播默认发射功率 (单位 0.1 dBm，默认 0 dBm)
#endif


/* --- 引入跨平台公共协议常量 --- */
#include "yuitag_config_common.h"

#endif // YUITAG_CONFIG_H
