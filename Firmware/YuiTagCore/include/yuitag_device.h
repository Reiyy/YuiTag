#ifndef YUITAG_DEVICE_H
#define YUITAG_DEVICE_H

// ============================================================
// YuiTag 设备状态机与业务逻辑接口 (跨平台核心)
// ============================================================

#include "yuitag_config.h"
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

typedef enum {
    YUITAG_NOTIFY_NONE = 0,
    YUITAG_NOTIFY_BIND,
    YUITAG_NOTIFY_CMD,
    YUITAG_NOTIFY_PARAM,
} yuitag_notify_ch_t;

typedef struct {
    yuitag_notify_ch_t channel;
    uint8_t data[16];
    uint8_t len;
} yuitag_response_t;

typedef struct {
    // ---- 设备标识 ----
    uint8_t  uid[UID_LEN];
    uint8_t  shortUid[SHORT_UID_LEN];
    char     deviceName[DEVICE_NAME_MAX_LEN + 1];
    char     serial[SERIAL_LEN + 1];

    // ---- 绑定与配对认证状态 ----
    bool     isBound;
    bool     waitingVerify;

    // ---- 运行时状态与参数 ----
    bool     connected;
    bool     ringActive;
    bool     findModeActive;
    bool     findmyEnabled;
#ifndef IS_SPARKS
    bool     otaAuthorized;
#endif
    uint16_t advInterval;
    uint16_t findModeInterval;
    uint16_t savedAdvInterval;
    uint8_t  batteryLevel;
    int8_t   txPower;
    int8_t   findmyTxPower;

    // ---- 时钟戳与防抖计时 ----
    uint32_t lastHeartbeatMs;
    uint32_t lastBatteryCheckMs;
    uint32_t powerCycleStartMs;

    // ---- 异步延迟动作标志 ----
    bool     pendingUnbindClear;
    bool     pendingBindAbort;
    bool     pendingBindSave;
    bool     pendingPwrCycleClear;
    bool     pendingParamSave;
    bool     pendingAdvRefresh;
    bool     pendingConnParamReset;
#ifndef IS_SPARKS
    bool     pendingFindmyChange;
#endif
} YuiTagDevice;

// ---- 生命周期 ----
void yuitag_device_init(YuiTagDevice *dev, const uint8_t mac[6]);
bool yuitag_device_power_cycle_check(YuiTagDevice *dev);
bool yuitag_device_tick(YuiTagDevice *dev);
bool yuitag_device_check_heartbeat_timeout(YuiTagDevice *dev);
void yuitag_device_on_connected(YuiTagDevice *dev);
void yuitag_device_on_disconnected(YuiTagDevice *dev);

// ---- 事件处理 (由 app.c 周期调用) ----
void yuitag_device_process_events(YuiTagDevice *dev);

// ---- 通信协议 ----
uint8_t yuitag_device_decrypt_validate(YuiTagDevice *dev,
                                       const uint8_t *cipherData, size_t len,
                                       uint8_t *outCmdId, uint8_t *outAction,
                                       uint8_t *outPlain);
uint8_t yuitag_device_dispatch_cmd(YuiTagDevice *dev, uint8_t cmdId, uint8_t action);

// ---- 绑定流程 ----
#ifdef IS_SPARKS
bool yuitag_device_bind_generate_key(YuiTagDevice *dev, uint8_t *aesKeyOut);
#else
bool yuitag_device_bind_process_peer_key(YuiTagDevice *dev,
                                          const uint8_t *peerPubKey, size_t peerKeyLen,
                                          uint8_t *tagPubKeyOut, size_t *pubKeyLenOut);
#endif
bool yuitag_device_bind_verify(YuiTagDevice *dev, const uint8_t *cipherData, size_t len);
void yuitag_device_clear_binding(YuiTagDevice *dev);
void yuitag_device_abort_bind(YuiTagDevice *dev);
void yuitag_device_persist_bind(YuiTagDevice *dev);
void yuitag_device_persist_params(YuiTagDevice *dev);

// ---- 参数通道 ----
size_t  yuitag_device_param_read(YuiTagDevice *dev, uint8_t *buf, size_t maxLen);
uint8_t yuitag_device_param_write(YuiTagDevice *dev, const uint8_t *cipherData, size_t len);

// ---- FindMy 辅助 (仅 Starlight) ----
#ifndef IS_SPARKS
bool    yuitag_device_findmy_period_config(const uint8_t *decryptedPayload, size_t len);
uint8_t yuitag_device_findmy_handle_time_sync(const uint8_t *plain, size_t len);
#endif

// ---- GATT 通用指令与参数处理流水线 ----
void    yuitag_device_handle_cmd_write(YuiTagDevice *dev, const uint8_t *inData, size_t inLen, yuitag_response_t *resp);
void    yuitag_device_handle_param_write(YuiTagDevice *dev, const uint8_t *inData, size_t inLen, yuitag_response_t *resp);

// ---- 电池电量转换模型 ----
uint8_t yuitag_battery_mv_to_percent(uint32_t battery_mv);

#endif // YUITAG_DEVICE_H
