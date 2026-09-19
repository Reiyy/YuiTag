#ifndef YUITAG_BLE_H
#define YUITAG_BLE_H

// ============================================================
// YuiTag BLE 协议栈与 GATT 通信层接口
// 硬件平台: Nordic nRF52810 / Zephyr Bluetooth Host
// 功能: 广播控制、GATT 核心服务与特征、连接参数协商、通知推送
// ============================================================

#include <zephyr/bluetooth/bluetooth.h>
#include "yuitag_device.h"
#include <stdbool.h>

// 初始化 BLE 驱动层上下文及连接参数更新工作项 (需在 bt_enable 前调用)
void yuitag_ble_init(YuiTagDevice *dev);

// 更新广播数据包内容并启动/重置广播
void yuitag_ble_update_advertising(YuiTagDevice *dev, bool rebuild_data);

// 主动断开当前 BLE 连接
void yuitag_ble_disconnect(void);

// 查询当前是否处于连接状态
bool yuitag_ble_is_connected(void);

// 查询广播启动是否发生错误
bool yuitag_ble_adv_failed(void);

// 触发参数特征与 DIS 服务相关值更新
void yuitag_ble_update_param_value(YuiTagDevice *dev);
void yuitag_ble_update_dis_values(YuiTagDevice *dev);
void yuitag_ble_set_device_name(const char *name);

// 尝试向主机发起连接参数更新请求 (遵循 Apple iOS 配件规范)
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev);

#endif // YUITAG_BLE_H
