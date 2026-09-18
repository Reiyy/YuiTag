#ifndef YUITAG_BLE_H
#define YUITAG_BLE_H
/* ============================================================
 * YuiTag BLE 传输与 GATT 服务层接口 (Silicon Labs BGAPI)
 * ============================================================ */

#include "yuitag_device.h"
#include "sl_bt_api.h"
#include <stdbool.h>

/* --- BLE 事件处理 --- */

// BLE 栈事件总入口 (由 app.c 的 sl_bt_on_event 分发调用)
void yuitag_ble_on_event(sl_bt_msg_t *evt, YuiTagDevice *dev);

/* --- 广播控制 --- */

// 更新广播数据并重启广播 (rebuild_data: 是否重新组包广播载荷)
void yuitag_ble_update_advertising(YuiTagDevice *dev, bool rebuild_data);

/* --- 连接与链路控制 --- */

// 主动断开当前 BLE 连接
void yuitag_ble_disconnect(void);

// 查询当前是否有活跃连接
bool yuitag_ble_is_connected(void);

// 查询上一次广播启动是否失败 (用于延迟重试)
bool yuitag_ble_adv_failed(void);

/* --- GATT 数据库与特征值维护 --- */

// 更新参数通道读缓冲区数据
void yuitag_ble_update_param_value(YuiTagDevice *dev);

// 更新设备信息服务 (DIS: 序列号、硬件/固件版本等)
void yuitag_ble_update_dis_values(YuiTagDevice *dev);

// 更新 GATT 设备名称属性 (0x2A00)
void yuitag_ble_set_device_name(const char *name);

// 延迟申请优化连接参数 (在主循环中调用)
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev);

#endif // YUITAG_BLE_H
