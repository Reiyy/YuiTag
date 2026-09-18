// ============================================================
// YuiTag BLE 协议栈与 GATT 通信层实现
// ============================================================

#include "yuitag_ble.h"
#include "yuitag_crypto.h"
#include "yuitag_platform.h"
#include "app.h"

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/util.h>
#include <zephyr/kernel.h>

#include <string.h>

// 128 位完整 UUID 声明
#define YUITAG_UUID_SVC   BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa00, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_BIND  BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa42, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_CMD   BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa43, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_PARAM BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa46, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))

static YuiTagDevice *g_dev;
static struct bt_conn *g_conn;
static bool g_connected = false;
static bool g_cccd_bind = false;
static bool g_cccd_cmd  = false;
static bool g_cccd_param = false;
static bool g_adv_failed = false;

static uint32_t g_conn_start_ms = 0;
static bool g_conn_param_updated = false;
static struct k_work_delayable s_conn_param_work;
static void conn_param_work_handler(struct k_work *work);

// CCCD 客户端通知特性描述符状态监听
static void ccc_bind_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    (void)attr;
    g_cccd_bind = (value == BT_GATT_CCC_NOTIFY);
    platform_log("[GATT] CCC bind notif=%d", g_cccd_bind);
}

static void ccc_cmd_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    (void)attr;
    g_cccd_cmd = (value == BT_GATT_CCC_NOTIFY);
    platform_log("[GATT] CCC cmd notif=%d", g_cccd_cmd);
}

static void ccc_param_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    (void)attr;
    g_cccd_param = (value == BT_GATT_CCC_NOTIFY);
    platform_log("[GATT] CCC param notif=%d", g_cccd_param);
}

// 根据特征 UUID 向客户端发送 GATT Notify 通知
static void notify_by_uuid(const struct bt_uuid *uuid,
                           const uint8_t *data, size_t len)
{
    if (!g_conn) {
        return;
    }

    bool ccc_enabled;
    if (!bt_uuid_cmp(uuid, YUITAG_UUID_BIND)) {
        ccc_enabled = g_cccd_bind;
    } else if (!bt_uuid_cmp(uuid, YUITAG_UUID_CMD)) {
        ccc_enabled = g_cccd_cmd;
    } else if (!bt_uuid_cmp(uuid, YUITAG_UUID_PARAM)) {
        ccc_enabled = g_cccd_param;
    } else {
        return;
    }

    if (!ccc_enabled) {
        return;
    }

    uint16_t mtu = bt_gatt_get_mtu(g_conn);
    if (len > (size_t)(mtu - 3)) {
        return;
    }

    struct bt_gatt_notify_params params = {
        .uuid = uuid,
        .attr = NULL,
        .data = data,
        .len = (uint16_t)len,
        .func = NULL,
        .user_data = NULL,
    };
    bt_gatt_notify_cb(g_conn, &params);
}

// 推送两字节执行状态通知: [0x00, status]
static void notify_status(const struct bt_uuid *uuid, uint8_t status)
{
    uint8_t resp[2] = { 0x00, status };
    notify_by_uuid(uuid, resp, 2);
}

// 设备信息服务 (DIS) 静态字符串读取
static ssize_t read_dis_string(struct bt_conn *conn,
                               const struct bt_gatt_attr *attr,
                               void *buf, uint16_t len, uint16_t offset)
{
    const char *str = (const char *)attr->user_data;
    return bt_gatt_attr_read(conn, attr, buf, len, offset, str, strlen(str));
}

// 参数通道读取回调 (读取当前名称、广播间隔与状态)
static ssize_t read_param(struct bt_conn *conn,
                          const struct bt_gatt_attr *attr,
                          void *buf, uint16_t len, uint16_t offset)
{
    uint8_t paramBuf[PARAM_DECRYPT_BUF_MAX_LEN];
    size_t paramLen = yuitag_device_param_read(g_dev, paramBuf, sizeof(paramBuf));
    if (paramLen == 0) {
        return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
    }
    return bt_gatt_attr_read(conn, attr, buf, len, offset, paramBuf, paramLen);
}

// 绑定特征写入回调 (触发设备端生成随机 AES 密钥并通过 Notify 发送给客户端)
static ssize_t write_bind(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                          const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags; (void)buf; (void)len;

    if (g_dev->isBound) {
        platform_log("[BIND] Ignored: already bound");
        return len;
    }

    if (g_dev->waitingVerify) {
        platform_log("[BIND] Ignored: bind already in progress");
        return len;
    }

    uint8_t aesKey[AES_KEY_LEN];
    if (!yuitag_device_bind_generate_key(g_dev, aesKey)) {
        uint8_t fail = STATUS_FAILED;
        notify_by_uuid(YUITAG_UUID_BIND, &fail, 1);
        g_dev->pendingBindAbort = true;
        app_proceed();
        return len;
    }

    notify_by_uuid(YUITAG_UUID_BIND, aesKey, AES_KEY_LEN);
    platform_log("[BIND] Sent AES key (%d bytes)", AES_KEY_LEN);
    return len;
}

// 指令通道写入回调 (处理绑定验证包及各类加密业务指令)
static ssize_t write_cmd(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                         const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags;
    const uint8_t *data = buf;

    // 处于绑定握手流程中的回包验证
    if (g_dev->waitingVerify && yuitag_crypto_has_aes_key() && !g_dev->isBound) {
        platform_log("[VERIFY] Received encrypted verify data (%d bytes)", len);
        if (yuitag_device_bind_verify(g_dev, data, len)) {
            uint8_t resp = BIND_VERIFY_OK;
            notify_by_uuid(YUITAG_UUID_CMD, &resp, 1);
            g_dev->pendingAdvRefresh = true;
            yuitag_ble_update_dis_values(g_dev);
            g_conn_param_updated = false;
            k_work_reschedule(&s_conn_param_work, K_MSEC(2500));
            app_proceed();
        } else {
            uint8_t resp = BIND_VERIFY_FAIL;
            notify_by_uuid(YUITAG_UUID_CMD, &resp, 1);
            g_dev->pendingBindAbort = true;
            app_proceed();
        }
        return len;
    }

    if (!g_dev->isBound) {
        platform_log("[CMD] Rejected: unbound");
        notify_status(YUITAG_UUID_CMD, STATUS_FAILED);
        return len;
    }

    // 解密并校验指令帧
    uint8_t cmdId = 0, action = 0;
    uint8_t plain[CMD_DECRYPT_BUF_MAX_LEN];
    uint8_t status = yuitag_device_decrypt_validate(g_dev, data, len, &cmdId, &action, plain);
    if (status != STATUS_SUCCESS) {
        notify_status(YUITAG_UUID_CMD, status);
        return len;
    }

    if (cmdId == CMD_BIND_VERIFY) {
        return len;
    }

    // 分发执行指令
    status = yuitag_device_dispatch_cmd(g_dev, cmdId, action);

    // 心跳包成功无需向手机端发送回包通知
    if (cmdId == CMD_HEARTBEAT && status == STATUS_NO_REPLY) {
        app_proceed();
        return len;
    }

    notify_status(YUITAG_UUID_CMD, status);

    if (g_dev->pendingUnbindClear) {
        app_proceed();
        return len;
    }

    if (g_dev->pendingAdvRefresh) {
        app_proceed();
    }

    platform_log("[CMD] cmdId=0x%02X action=0x%02X -> status=0x%02X",
                 cmdId, action, status);
    return len;
}

// 参数通道写入回调 (处理加密的设备名称与广播间隔修改)
static ssize_t write_param(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                           const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags;

    if (!g_dev->isBound) {
        platform_log("[PARAM] Rejected: unbound");
        notify_status(YUITAG_UUID_PARAM, STATUS_FAILED);
        return len;
    }

    uint8_t status = yuitag_device_param_write(g_dev, buf, len);
    notify_status(YUITAG_UUID_PARAM, status);

    if (status == STATUS_SUCCESS) {
        if (g_dev->pendingParamSave) {
            app_proceed();
        }
        yuitag_ble_update_param_value(g_dev);
    }
    return len;
}

static ssize_t read_serial(struct bt_conn *conn,
                           const struct bt_gatt_attr *attr,
                           void *buf, uint16_t len, uint16_t offset);

static ssize_t read_system_id(struct bt_conn *conn,
                              const struct bt_gatt_attr *attr,
                              void *buf, uint16_t len, uint16_t offset);

// ============================================================
// GATT 服务结构定义
// ============================================================

// 标准设备信息服务 (DIS: 0x180A)
BT_GATT_SERVICE_DEFINE(dis_svc,
    BT_GATT_PRIMARY_SERVICE(BT_UUID_DIS),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A29),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_dis_string, NULL, (void *)MANUFACTURER),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A24),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_dis_string, NULL, (void *)MODEL_NUMBER),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A25),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_serial, NULL, NULL),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A23),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_system_id, NULL, NULL),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A27),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_dis_string, NULL, (void *)HW_VERSION),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A26),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_dis_string, NULL, (void *)FW_VERSION),
    BT_GATT_CHARACTERISTIC(BT_UUID_DECLARE_16(0x2A28),
        BT_GATT_CHRC_READ, BT_GATT_PERM_READ, read_dis_string, NULL, (void *)YUITAG_CORE_VERSION),
);

// YuiTag 核心服务 (Core Service: 0xAA00)
BT_GATT_SERVICE_DEFINE(yuitag_core_svc,
    BT_GATT_PRIMARY_SERVICE(YUITAG_UUID_SVC),

    // 绑定握手特征 (0xAA42)
    BT_GATT_CHARACTERISTIC(YUITAG_UUID_BIND,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_WRITE, NULL, write_bind, NULL),
    BT_GATT_CCC(ccc_bind_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    // 控制指令通道特征 (0xAA43)
    BT_GATT_CHARACTERISTIC(YUITAG_UUID_CMD,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_WRITE, NULL, write_cmd, NULL),
    BT_GATT_CCC(ccc_cmd_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    // 参数配置通道特征 (0xAA46)
    BT_GATT_CHARACTERISTIC(YUITAG_UUID_PARAM,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ | BT_GATT_PERM_WRITE, read_param, write_param, NULL),
    BT_GATT_CCC(ccc_param_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

// 读取序列号特征
static ssize_t read_serial(struct bt_conn *conn,
                           const struct bt_gatt_attr *attr,
                           void *buf, uint16_t len, uint16_t offset)
{
    if (!g_dev) {
        return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
    }
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             g_dev->serial, strlen(g_dev->serial));
}

// 读取系统 ID 特征 (由 MAC 转换为 EUI-64 格式)
static ssize_t read_system_id(struct bt_conn *conn,
                              const struct bt_gatt_attr *attr,
                              void *buf, uint16_t len, uint16_t offset)
{
    uint8_t mac[6];
    uint8_t sid[8];

    platform_get_mac(mac);
    sid[0] = mac[5];
    sid[1] = mac[4];
    sid[2] = mac[3];
    sid[3] = 0xFF;
    sid[4] = 0xFE;
    sid[5] = mac[2];
    sid[6] = mac[1];
    sid[7] = mac[0];

    return bt_gatt_attr_read(conn, attr, buf, len, offset, sid, sizeof(sid));
}

// 组装广播包与扫描响应包并启动广播
static void start_advertising(YuiTagDevice *dev, bool connectable)
{
    // 厂商自定义数据 (Company ID + 协议标识 + 短 UID + 未绑定标志)
    uint8_t mfrBuf[8];
    size_t mfrLen = 0;
    if (dev->isBound) {
        mfrBuf[0] = YUITAG_COMPANY_ID & 0xFF;
        mfrBuf[1] = (YUITAG_COMPANY_ID >> 8) & 0xFF;
        mfrBuf[2] = 0x01;
        mfrBuf[3] = dev->shortUid[0];
        mfrBuf[4] = dev->shortUid[1];
        mfrLen = 5;
    } else {
        mfrBuf[0] = YUITAG_COMPANY_ID & 0xFF;
        mfrBuf[1] = (YUITAG_COMPANY_ID >> 8) & 0xFF;
        mfrBuf[2] = 0x01;
        mfrBuf[3] = dev->shortUid[0];
        mfrBuf[4] = dev->shortUid[1];
        mfrBuf[5] = 0x00; // 未绑定状态标志
        mfrLen = 6;
    }

    // 16位服务数据 (标准电池电量服务 0x180F)
    uint8_t battData[3];
    battData[0] = UUID16_BATT & 0xFF;
    battData[1] = (UUID16_BATT >> 8) & 0xFF;
    battData[2] = dev->batteryLevel;

    uint8_t flags = 0x06; // 限制通用发现模式 + 不支持经典蓝牙
    struct bt_data ad[3];
    size_t adLen = 0;

    ad[adLen].type = BT_DATA_FLAGS;
    ad[adLen].data_len = sizeof(flags);
    ad[adLen].data = &flags;
    adLen++;

    ad[adLen].type = BT_DATA_MANUFACTURER_DATA;
    ad[adLen].data_len = (uint8_t)mfrLen;
    ad[adLen].data = mfrBuf;
    adLen++;

    // 已绑定状态下在广播中携带电量信息
    if (dev->isBound) {
        ad[adLen].type = BT_DATA_SVC_DATA16;
        ad[adLen].data_len = sizeof(battData);
        ad[adLen].data = battData;
        adLen++;
    }

    // 扫描响应包中包含完整设备名称
    uint8_t nameBuf[DEVICE_NAME_MAX_LEN + 1];
    size_t nameLen = strlen(dev->deviceName);
    if (nameLen > DEVICE_NAME_MAX_LEN) {
        nameLen = DEVICE_NAME_MAX_LEN;
    }
    memcpy(nameBuf, dev->deviceName, nameLen);

    struct bt_data sd[] = {
        BT_DATA(BT_DATA_NAME_COMPLETE, nameBuf, nameLen),
    };

    // 毫秒折算为 0.625ms 广播单位
    uint32_t units = ((uint32_t)dev->advInterval * 8U) / 5U;
    uint32_t opt = BT_LE_ADV_OPT_USE_IDENTITY;
    opt |= connectable ? BT_LE_ADV_OPT_CONN : BT_LE_ADV_OPT_SCANNABLE;
    struct bt_le_adv_param param = BT_LE_ADV_PARAM_INIT(opt, units, units, NULL);

    int err = bt_le_adv_start(&param, ad, adLen, sd, ARRAY_SIZE(sd));
    if (err) {
        platform_log("[ADV] Start failed: %d", err);
        g_adv_failed = true;
        return;
    }

    g_adv_failed = false;
    platform_log("[ADV] Mode=%s connectable=%d interval=%dms",
                 dev->isBound ? "Normal" : "Initial", connectable, dev->advInterval);
}

void yuitag_ble_update_advertising(YuiTagDevice *dev, bool rebuild_data)
{
    (void)rebuild_data;
    bt_le_adv_stop();
    start_advertising(dev, !g_connected);
}

bool yuitag_ble_adv_failed(void)
{
    return g_adv_failed;
}

void yuitag_ble_disconnect(void)
{
    if (g_conn) {
        bt_conn_disconnect(g_conn, BT_HCI_ERR_REMOTE_USER_TERM_CONN);
    }
}

bool yuitag_ble_is_connected(void)
{
    return g_connected;
}

void yuitag_ble_update_param_value(YuiTagDevice *dev)
{
    (void)dev;
}

void yuitag_ble_update_dis_values(YuiTagDevice *dev)
{
    bt_set_name(dev->deviceName);
}

void yuitag_ble_set_device_name(const char *name)
{
    bt_set_name(name);
}

// 蓝牙连接建立回调
static void connected(struct bt_conn *conn, uint8_t err)
{
    if (err) {
        platform_log("[CONN] Connect failed: %u", err);
        return;
    }

    g_conn = bt_conn_ref(conn);
    g_connected = true;
    g_conn_start_ms = platform_millis();
    g_conn_param_updated = false;
    g_cccd_bind = g_cccd_cmd = g_cccd_param = false;

    yuitag_device_on_connected(g_dev);

    // 已绑定设备在连接建立 2.5 秒后尝试更新连接参数 (避开初始阶段 GATT 服务发现的高峰)
    if (g_dev && g_dev->isBound) {
        k_work_reschedule(&s_conn_param_work, K_MSEC(2500));
    }

    g_dev->pendingAdvRefresh = true;
    app_proceed();

    platform_log("[CONN] Connected");
}

// 蓝牙连接断开回调
static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    (void)reason;
    k_work_cancel_delayable(&s_conn_param_work);

    if (g_conn) {
        bt_conn_unref(g_conn);
        g_conn = NULL;
    }
    g_connected = false;
    g_cccd_bind = g_cccd_cmd = g_cccd_param = false;

    yuitag_device_on_disconnected(g_dev);

    g_dev->pendingAdvRefresh = true;
    app_proceed();
    platform_log("[CONN] Disconnected (reason 0x%02x)", reason);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

static void conn_param_work_handler(struct k_work *work)
{
    (void)work;
    if (g_dev) {
        yuitag_ble_try_conn_param_update(g_dev);
    }
}

// 请求优化连接参数 (严格遵循 Apple iOS 配件设计准则: 间隔*延迟<=2s, 超时<=6s)
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev)
{
    if (!g_connected || g_conn_param_updated || !g_conn) {
        return;
    }
    if (!dev || !dev->isBound) {
        return;
    }
    if (platform_millis() - g_conn_start_ms < 2000) {
        return;
    }

    // 采用: 间隔 1000ms, 从机延迟 1 (有效通信周期 2000ms), 监控超时 6000ms
    struct bt_le_conn_param param = {
        .interval_min = 800,   // 1000 ms (800 * 1.25ms)
        .interval_max = 800,   // 1000 ms (800 * 1.25ms)
        .latency = 1,          // 1 slave latency -> 有效间隔 2000 ms (2.0s)
        .timeout = 600,        // 6000 ms (600 * 10ms = 6.0s)
    };
    int err = bt_conn_le_param_update(g_conn, &param);
    if (err == 0) {
        g_conn_param_updated = true;
        platform_log("[CONN] Apple-compliant param update requested: 1000ms, lat=1, to=6s");
    } else {
        platform_log("[CONN] Param update failed: %d", err);
    }
}

void yuitag_ble_init(YuiTagDevice *dev)
{
    g_dev = dev;
    k_work_init_delayable(&s_conn_param_work, conn_param_work_handler);
}