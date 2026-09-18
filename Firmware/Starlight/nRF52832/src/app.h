#ifndef APP_H
#define APP_H

/* ============================================================
 * YuiTag 应用层调度接口 (工作队列与周期定时器)
 * ============================================================ */

#include "yuitag_device.h"
#include <stdbool.h>

/* 初始化工作队列及周期定时器 */
void app_init(YuiTagDevice *dev);

/* 触发应用事件处理 (可在 ISR 或 BLE 回调中调用) */
void app_proceed(void);

/* 标记应用就绪 (在蓝牙就绪及设备初始化完成后调用) */
void app_mark_initialized(void);

/* OTA DFU 授权状态管理 (由 CMD_OTA_AUTHORIZE 激活，断开连接时清除) */
void app_ota_authorize(void);
void app_ota_deauthorize(void);
bool app_is_ota_authorized(void);

#endif /* APP_H */
