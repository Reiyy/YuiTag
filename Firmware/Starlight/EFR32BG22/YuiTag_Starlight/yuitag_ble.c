/* ============================================================
 * YuiTag BLE 传输与 GATT 服务层实现 (Silicon Labs EFR32BG22)
 * ============================================================ */

#include "yuitag_ble.h"
#include "yuitag_crypto.h"
#include "yuitag_platform.h"
#include "yuitag_adv.h"
#include "yuitag_buzzer_types.h"
#include "gatt_db.h"
#include "app.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

#include <string.h>

/* --- 模块私有状态 --- */
static uint8_t  s_adv_handle = 0xFF;       // 广播集句柄
static uint8_t  s_connection = 0xFF;       // 当前连接句柄 (0xFF 表示未连接)
static bool     s_adv_started = false;     // 广播是否已启动
static bool     s_adv_connectable = false; // 当前广播可连接标志
static bool     s_adv_data_set = false;    // 广播数据是否已配置
static bool     s_adv_failed = false;      // 广播启动失败标志 (供主循环重试)

// 连接参数延迟优化控制 (避免干扰连接初期的 GATT 发现)
static uint32_t s_conn_start_ms = 0;
static bool     s_conn_param_updated = false;

// 各通道 CCCD 通知使能状态
static bool     s_cccd_bind_enabled = false;
static bool     s_cccd_cmd_enabled   = false;
static bool     s_cccd_param_enabled = false;

// ATT MTU (默认 23，协商后动态更新)
static uint16_t s_mtu = 23;

/* --- 广播数据组包 --- */

// 将 YuiTagCore 构造的 AD 段序列化为 LTV 格式原始字节流
// (段布局/字段选择逻辑统一在 yuitag_adv.c, 此处仅做字节序列化)
static size_t serialize_segs_ltv(const yuitag_adv_seg_t *segs, size_t count,
                                 uint8_t *buf, size_t maxSize)
{
    size_t pos = 0;
    for (size_t i = 0; i < count; i++) {
        if (pos + 2 + segs[i].len > maxSize) {
            return 0;
        }
        buf[pos++] = (uint8_t)(segs[i].len + 1);
        buf[pos++] = segs[i].type;
        memcpy(&buf[pos], segs[i].data, segs[i].len);
        pos += segs[i].len;
    }
    return pos;
}

/* --- 广播启停与参数配置 --- */

static void start_advertising(YuiTagDevice *dev, bool connectable)
{
    sl_status_t sc;
    uint8_t advData[31];
    uint8_t scanResp[31];
    size_t advLen, scanLen;

    s_adv_failed = true;

    if (s_adv_started) {
        sl_bt_advertiser_stop(s_adv_handle);
        s_adv_started = false;
    }

    // 设置广播间隔 (单位: 0.625ms)
    uint32_t interval_units = ((uint32_t)dev->advInterval * 8U) / 5U;
    sc = sl_bt_advertiser_set_timing(s_adv_handle,
                                     interval_units, interval_units,
                                     0, 0);
    if (sc != SL_STATUS_OK) {
        platform_log("[ADV] Set timing failed: 0x%04X", sc);
        return;
    }

    // 仅在载荷或连接属性改变时更新广播数据，避免无效重设
    if (!s_adv_data_set || connectable != s_adv_connectable) {
        // 主广播包字段段 (flags + 厂商数据 + [绑定后]电量 + [debug]诊断)
        uint8_t adStorage[32];
        yuitag_adv_seg_t adSegs[4];
        size_t adSegCount = yuitag_adv_build(dev, adSegs, 4,
                                             adStorage, sizeof(adStorage));
        advLen = serialize_segs_ltv(adSegs, adSegCount, advData, sizeof(advData));

        // 扫描响应包: 完整设备名称
        uint8_t sdStorage[DEVICE_NAME_MAX_LEN + 1];
        yuitag_adv_seg_t sdSegs[1];
        size_t sdSegCount = yuitag_adv_build_scan_rsp(dev, sdSegs, 1,
                                                       sdStorage, sizeof(sdStorage));
        scanLen = (sdSegCount == 1) ? serialize_segs_ltv(sdSegs, 1, scanResp, sizeof(scanResp)) : 0;

        if (advLen == 0 || scanLen == 0) {
            platform_log("[ADV] Failed to build adv data");
            return;
        }

        sc = sl_bt_legacy_advertiser_set_data(s_adv_handle,
                                              sl_bt_advertiser_advertising_data_packet,
                                              advLen, advData);
        if (sc != SL_STATUS_OK) {
            platform_log("[ADV] Set adv data failed: 0x%04X", sc);
            return;
        }

        sc = sl_bt_legacy_advertiser_set_data(s_adv_handle,
                                              sl_bt_advertiser_scan_response_packet,
                                              scanLen, scanResp);
        if (sc != SL_STATUS_OK) {
            platform_log("[ADV] Set scan response failed: 0x%04X", sc);
            return;
        }

        s_adv_data_set = true;
    }

    // 未连接时广播可连接；已连接时广播仅可扫描 (供 RSSI 测距)
    uint8_t mode = connectable ? sl_bt_legacy_advertiser_connectable
                                : sl_bt_legacy_advertiser_scannable;
    sc = sl_bt_legacy_advertiser_start(s_adv_handle, mode);
    if (sc != SL_STATUS_OK) {
        platform_log("[ADV] Start failed: 0x%04X", sc);
        return;
    }

    s_adv_started = true;
    s_adv_connectable = connectable;
    s_adv_failed = false;
    platform_log("[ADV] Mode=%s interval=%dms connectable=%d",
                 dev->isBound ? "Normal" : "Initial",
                 dev->advInterval, connectable);
}

void yuitag_ble_update_advertising(YuiTagDevice *dev, bool rebuild_data)
{
    if (rebuild_data) {
        s_adv_data_set = false;
    }

    if (s_connection != 0xFF) {
        // 已连接状态下重启为仅可扫描模式以供测距
        start_advertising(dev, false);
        return;
    }

    // 未连接状态启动可连接广播
    start_advertising(dev, true);
}

bool yuitag_ble_adv_failed(void)
{
    return s_adv_failed;
}

/* --- 连接与通知控制 --- */
void yuitag_ble_disconnect(void)
{
    if (s_connection != 0xFF) {
        sl_status_t sc = sl_bt_connection_close(s_connection);
        if (sc == SL_STATUS_OK) {
            platform_log("[CONN] Disconnect OK");
        } else {
            platform_log("[CONN] Disconnect failed: 0x%04X", sc);
        }
    }
}

bool yuitag_ble_is_connected(void)
{
    return s_connection != 0xFF;
}

static void send_notify(uint16_t characteristic, const uint8_t *data, size_t len)
{
    if (s_connection == 0xFF) {
        return;
    }
    if (characteristic == gattdb_bind_channel && !s_cccd_bind_enabled) return;
    if (characteristic == gattdb_cmd_channel  && !s_cccd_cmd_enabled)   return;
    if (characteristic == gattdb_param_channel && !s_cccd_param_enabled) return;
    if (len > (size_t)(s_mtu - 3)) {
        platform_log("[GATT] Notify len=%d exceeds MTU-%d, dropped", len, s_mtu);
        return;
    }
    sl_bt_gatt_server_send_notification(s_connection, characteristic, len, data);
}

static void notify_status(uint16_t characteristic, uint8_t status)
{
    uint8_t resp[2] = { 0x00, status };
    send_notify(characteristic, resp, 2);
}

/* --- GATT 写入事件处理 --- */

static void handle_bind_channel_write(YuiTagDevice *dev,
                                       const uint8_t *data, size_t len)
{
    if (dev->isBound) {
        platform_log("[BIND] Ignored: already bound");
        return;
    }

    // 绑定已在进行中则拒绝重入
    if (dev->waitingVerify) {
        platform_log("[BIND] Ignored: bind already in progress");
        return;
    }

    uint8_t tagPubKey[ECDH_PUBKEY_LEN];
    size_t pubKeyLen = 0;

    if (!yuitag_device_bind_process_peer_key(dev, data, len, tagPubKey, &pubKeyLen)) {
        uint8_t fail = STATUS_FAILED;
        send_notify(gattdb_bind_channel, &fail, 1);
        dev->pendingBindAbort = true;
        app_proceed();
        return;
    }

    // 通过通知向 App 发送 Tag 公钥 (需 ATT MTU >= 68)
    if (pubKeyLen > (size_t)(s_mtu - 3)) {
        platform_log("[BIND] MTU too small for pubkey (%d bytes, MTU=%u)",
                     pubKeyLen, s_mtu);
        dev->pendingBindAbort = true;
        app_proceed();
        return;
    }
    send_notify(gattdb_bind_channel, tagPubKey, pubKeyLen);
    platform_log("[BIND] Sent Tag pubkey (%d bytes)", pubKeyLen);
}

static void handle_cmd_channel_write(YuiTagDevice *dev,
                                     const uint8_t *data, size_t len)
{
    // 阶段 1: 绑定确认密文验证
    if (dev->waitingVerify && yuitag_crypto_has_aes_key() && !dev->isBound) {
        platform_log("[VERIFY] Received encrypted verify data (%d bytes)", len);

        if (yuitag_device_bind_verify(dev, data, len)) {
            uint8_t resp = BIND_VERIFY_OK;
            send_notify(gattdb_cmd_channel, &resp, 1);
            yuitag_ble_update_advertising(dev, true);
            yuitag_ble_update_dis_values(dev);
            app_proceed();
        } else {
            uint8_t resp = BIND_VERIFY_FAIL;
            send_notify(gattdb_cmd_channel, &resp, 1);
            dev->pendingBindAbort = true;
            app_proceed();
        }
        return;
    }

    // 未绑定状态拒绝常规控制指令
    if (!dev->isBound) {
        platform_log("[CMD] Rejected: unbound");
        notify_status(gattdb_cmd_channel, STATUS_FAILED);
        return;
    }

    // 阶段 2: 常规加密业务指令
    uint8_t cmdId = 0, action = 0;
    uint8_t plain[CMD_DECRYPT_BUF_MAX_LEN];
    uint8_t status = yuitag_device_decrypt_validate(dev, data, len, &cmdId, &action, plain);
    if (status != STATUS_SUCCESS) {
        notify_status(gattdb_cmd_channel, status);
        return;
    }

    if (cmdId == CMD_BIND_VERIFY) {
        return;
    }

#ifndef IS_SPARKS
    if (cmdId == CMD_FINDMY_TIME_SYNC) {
        uint8_t syncStatus = yuitag_device_findmy_handle_time_sync(plain, len);
        notify_status(gattdb_cmd_channel, syncStatus);
        if (syncStatus == STATUS_SUCCESS) {
            app_proceed();
        }
        return;
    }
#endif

    status = yuitag_device_dispatch_cmd(dev, cmdId, action);

#ifndef IS_SPARKS
    if (cmdId == CMD_FINDMY_STATUS && status == STATUS_SUCCESS) {
        uint8_t notifyBuf[10];
        notifyBuf[0] = CMD_FINDMY_STATUS;
        notifyBuf[1] = yuitag_findmy_is_time_synced() ? 1 : 0;
        uint32_t curTime = yuitag_findmy_get_current_time();
        uint32_t curPeriod = yuitag_findmy_get_current_period();
        notifyBuf[2] = (uint8_t)(curTime & 0xFF);
        notifyBuf[3] = (uint8_t)((curTime >> 8) & 0xFF);
        notifyBuf[4] = (uint8_t)((curTime >> 16) & 0xFF);
        notifyBuf[5] = (uint8_t)((curTime >> 24) & 0xFF);
        notifyBuf[6] = (uint8_t)(curPeriod & 0xFF);
        notifyBuf[7] = (uint8_t)((curPeriod >> 8) & 0xFF);
        notifyBuf[8] = (uint8_t)((curPeriod >> 16) & 0xFF);
        notifyBuf[9] = (uint8_t)((curPeriod >> 24) & 0xFF);
        send_notify(gattdb_param_channel, notifyBuf, sizeof(notifyBuf));
        platform_log("[FINDMY] Status sent: synced=%d time=%lu period=%lu",
                     notifyBuf[1], curTime, curPeriod);
        return;
    }
#endif

    // 心跳指令无需回包，仅唤醒主循环刷新超时
    if (cmdId == CMD_HEARTBEAT && status == STATUS_NO_REPLY) {
        app_proceed();
        return;
    }

    notify_status(gattdb_cmd_channel, status);

    // 解绑操作延迟至主循环处理，避免在 BLE 回调中进行 NVM3 操作与广播重启
    if (dev->pendingUnbindClear) {
        app_proceed();
        return;
    }

    // 广播参数或状态变更时刷新广播
    if (dev->pendingAdvRefresh) {
        dev->pendingAdvRefresh = false;
        yuitag_ble_update_advertising(dev, true);
    }

    platform_log("[CMD] cmdId=0x%02X action=0x%02X -> status=0x%02X",
                 cmdId, action, status);
}

static void handle_param_channel_write(YuiTagDevice *dev,
                                       const uint8_t *data, size_t len)
{
    if (!dev->isBound) {
        platform_log("[PARAM] Rejected: unbound");
        notify_status(gattdb_param_channel, STATUS_FAILED);
        return;
    }

    uint8_t status = yuitag_device_param_write(dev, data, len);
    notify_status(gattdb_param_channel, status);

    if (status == STATUS_SUCCESS) {
        if (dev->pendingParamSave) {
            app_proceed();
        }
        if (dev->pendingAdvRefresh) {
            dev->pendingAdvRefresh = false;
            yuitag_ble_update_advertising(dev, true);
        }
        yuitag_ble_update_param_value(dev);
    }
}

/* --- GATT 数据库维护与特征值写入 --- */
static void gatt_write_val(uint16_t handle, const void *data, size_t len)
{
    sl_status_t sc = sl_bt_gatt_server_write_attribute_value(handle, 0, len, (const uint8_t *)data);
    if (sc != SL_STATUS_OK) {
        platform_log("[GATT] write handle=%u len=%u failed: 0x%04X", handle, len, sc);
    }
}

void yuitag_ble_update_param_value(YuiTagDevice *dev)
{
    uint8_t buf[32];
    size_t paramLen = yuitag_device_param_read(dev, buf, sizeof(buf));
    if (paramLen > 0) {
        gatt_write_val(gattdb_param_channel, buf, paramLen);
    }
}

void yuitag_ble_update_dis_values(YuiTagDevice *dev)
{
    gatt_write_val(gattdb_model_number_string, MODEL_NUMBER, strlen(MODEL_NUMBER));
    gatt_write_val(gattdb_serial_number_string, dev->serial, strlen(dev->serial));
    gatt_write_val(gattdb_hardware_revision_string, HW_VERSION, strlen(HW_VERSION));
    gatt_write_val(gattdb_firmware_revision_string, FW_VERSION, strlen(FW_VERSION));
#if defined(gattdb_software_revision_string)
    gatt_write_val(gattdb_software_revision_string, YUITAG_CORE_VERSION, strlen(YUITAG_CORE_VERSION));
#endif
    gatt_write_val(gattdb_manufacturer_name_string, MANUFACTURER, strlen(MANUFACTURER));
    gatt_write_val(gattdb_device_name, dev->deviceName, strlen(dev->deviceName));
}

void yuitag_ble_set_device_name(const char *name)
{
    gatt_write_val(gattdb_device_name, name, strlen(name));
}

/* --- BLE 栈事件主入口 --- */
void yuitag_ble_on_event(sl_bt_msg_t *evt, YuiTagDevice *dev)
{
    switch (SL_BT_MSG_ID(evt->header)) {

    // 系统开机启动事件
    case sl_bt_evt_system_boot_id: {
        platform_log("[BOOT] YuiTag starting...");

        sl_status_t sc = sl_bt_advertiser_create_set(&s_adv_handle);
        if (sc != SL_STATUS_OK) {
            platform_log("[BOOT] Adv set create failed: 0x%04X", sc);
            return;
        }

        int16_t adv_actual_power = 0;
        sc = sl_bt_advertiser_set_tx_power(s_adv_handle, BLE_TX_POWER, &adv_actual_power);
        if (sc != SL_STATUS_OK) {
            platform_log("[ADV] Set TX power failed: 0x%04X", sc);
        }

        uint8_t mac[6];
        platform_get_mac(mac);
        yuitag_device_init(dev, mac);

        // 开机提示音在首次电量采样后播放，避免蜂鸣器瞬态拉低采样电压；
        // 若连续快速上电触发了出厂复位，则跳过开机提示音，避免覆盖复位提示音
        if (!app_is_factory_reset_triggered()) {
            yuitag_beep_startup();
        }

        yuitag_ble_update_dis_values(dev);
        yuitag_ble_update_param_value(dev);
        yuitag_ble_update_advertising(dev, false);

        platform_log("[BOOT] YuiTag initialized");
        app_proceed();
        break;
    }

    // 连接建立事件
    case sl_bt_evt_connection_opened_id: {
        s_connection = evt->data.evt_connection_opened.connection;
        s_conn_start_ms = platform_millis();
        s_conn_param_updated = false;
        s_cccd_bind_enabled = false;
        s_cccd_cmd_enabled  = false;
        s_cccd_param_enabled = false;
        s_mtu = 23;
        yuitag_device_on_connected(dev);

        // 申请扩展 MTU 以支持 65 字节公钥传输和更高效的 OTA
        sl_bt_gatt_set_max_mtu(247, NULL);

        // 连接建立后协议栈自动停止广播，重启为可扫描广播以维持测距
        yuitag_ble_update_advertising(dev, false);
        break;
    }

    // 连接断开事件
    case sl_bt_evt_connection_closed_id: {
        s_connection = 0xFF;
        s_adv_started = false;
        s_cccd_bind_enabled = false;
        s_cccd_cmd_enabled  = false;
        s_cccd_param_enabled = false;
        s_mtu = 23;
        app_ota_deauthorize();
        yuitag_device_on_disconnected(dev);

        // 断开后重启常规可连接广播
        yuitag_ble_update_advertising(dev, false);
        break;
    }

    // MTU 协商完成
    case sl_bt_evt_gatt_mtu_exchanged_id: {
        s_mtu = evt->data.evt_gatt_mtu_exchanged.mtu;
        platform_log("[GATT] MTU exchanged: %u", s_mtu);
        break;
    }

    // GATT 特征值写入
    case sl_bt_evt_gatt_server_attribute_value_id: {
        uint16_t attr = evt->data.evt_gatt_server_attribute_value.attribute;
        size_t valLen = evt->data.evt_gatt_server_attribute_value.value.len;
        const uint8_t *valData = evt->data.evt_gatt_server_attribute_value.value.data;

        if (attr == gattdb_bind_channel) {
            handle_bind_channel_write(dev, valData, valLen);
        } else if (attr == gattdb_cmd_channel) {
            handle_cmd_channel_write(dev, valData, valLen);
        } else if (attr == gattdb_param_channel) {
            handle_param_channel_write(dev, valData, valLen);
        }
        break;
    }

    // CCCD 客户端配置状态变化
    case sl_bt_evt_gatt_server_characteristic_status_id: {
        uint16_t chr = evt->data.evt_gatt_server_characteristic_status.characteristic;
        uint8_t flags = evt->data.evt_gatt_server_characteristic_status.status_flags;
        bool notif = (flags & 0x01) != 0;

        if (chr == gattdb_bind_channel) {
            s_cccd_bind_enabled = notif;
        } else if (chr == gattdb_cmd_channel) {
            s_cccd_cmd_enabled = notif;
        } else if (chr == gattdb_param_channel) {
            s_cccd_param_enabled = notif;
        }

        if (chr == gattdb_cmd_channel || chr == gattdb_bind_channel
            || chr == gattdb_param_channel) {
            platform_log("[GATT] CCCD chr=0x%04X notif=%d", chr, notif);
        }
        break;
    }

    default:
        break;
    }
}

/* --- 延迟优化连接参数 (在主循环中轮询调用) --- */
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev)
{
    if (s_connection == 0xFF || s_conn_param_updated) {
        return;
    }

    if (!dev->isBound) {
        return;
    }

    // 预留 2s 给服务发现流程，完成后再切入低功耗参数
    if (platform_millis() - s_conn_start_ms < 2000) {
        return;
    }

    // OTA DFU 授权状态下不降速
    if (app_is_ota_authorized()) {
        return;
    }

    /* 符合 Apple 配件设计指南规范:
     * 1. 间隔 1000ms (800 * 1.25ms)
     * 2. 从机延迟 1 -> 等效有效间隔 2000ms (<= 2.0s)
     * 3. 监控超时 6000ms (600 * 10ms <= 6.0s 且 > 2.0s * 2)
     */
    sl_bt_connection_set_parameters(s_connection,
        800,
        800,
        1,
        600,
        0, 0);

    s_conn_param_updated = true;
    platform_log("[CONN] Param update: 1000ms, latency=1, timeout=6s");
}
