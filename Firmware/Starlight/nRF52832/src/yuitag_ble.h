#ifndef YUITAG_BLE_H
#define YUITAG_BLE_H
// ============================================================
// YuiTag BLE 协议栈接口 - Zephyr Bluetooth Host
// 处理: BLE 广播、GATT 服务与特征、通知推送、连接参数协商
// ============================================================

#include <zephyr/bluetooth/bluetooth.h>
#include "yuitag_device.h"
#include <stdbool.h>

// 初始化 GATT 服务数据库与连接回调 (需在 bt_enable 之前调用)
void yuitag_ble_init(YuiTagDevice *dev);

// 更新广播数据包并重启广播
void yuitag_ble_update_advertising(YuiTagDevice *dev, bool rebuild_data);

void yuitag_ble_disconnect(void);
bool yuitag_ble_is_connected(void);
bool yuitag_ble_adv_failed(void);

void yuitag_ble_update_param_value(YuiTagDevice *dev);
void yuitag_ble_update_dis_values(YuiTagDevice *dev);
void yuitag_ble_set_device_name(const char *name);

// 尝试向主机申请更新连接参数 (符合 Apple 规范)
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev);

// 主动向主机申请快速连接参数 (15ms 间隔，从机延迟 0，供 OTA 高速传输)
void yuitag_ble_set_fast_conn_params(void);

#endif // YUITAG_BLE_H
