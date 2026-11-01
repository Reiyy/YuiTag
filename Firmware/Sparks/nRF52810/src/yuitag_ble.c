// ============================================================
// YuiTag BLE 协议栈与 GATT 通信层实现
// ============================================================

#include "yuitag_ble.h"
#include "yuitag_crypto.h"
#include "yuitag_platform.h"
#include "yuitag_adv.h"
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

static struct k_work_delayable s_adv_refresh_work;
static void adv_refresh_work_handler(struct k_work *work)
{
    (void)work;
    if (g_dev) {
        yuitag_ble_update_advertising(g_dev, true);
        app_proceed();
    }
}

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

// 根据通道向客户端发送 GATT Notify 通知
static void send_notify_by_ch(yuitag_notify_ch_t ch, const uint8_t *data, size_t len)
{
    switch (ch) {
    case YUITAG_NOTIFY_BIND:
        notify_by_uuid(YUITAG_UUID_BIND, data, len);
        break;
    case YUITAG_NOTIFY_CMD:
        notify_by_uuid(YUITAG_UUID_CMD, data, len);
        break;
    case YUITAG_NOTIFY_PARAM:
        notify_by_uuid(YUITAG_UUID_PARAM, data, len);
        break;
    default:
        break;
    }
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

    yuitag_response_t resp = {0};
    yuitag_device_handle_cmd_write(g_dev, buf, len, &resp);

    if (resp.channel != YUITAG_NOTIFY_NONE) {
        send_notify_by_ch(resp.channel, resp.data, resp.len);
    }

    if (g_dev->pendingConnParamReset) {
        g_dev->pendingConnParamReset = false;
        yuitag_ble_update_dis_values(g_dev);
        g_conn_param_updated = false;
        k_work_reschedule(&s_conn_param_work, K_MSEC(CONN_PARAM_DELAY_MS));
    }

    if (g_dev->pendingUnbindClear || g_dev->pendingAdvRefresh || g_dev->pendingBindAbort) {
        app_proceed();
    }

    return len;
}

// 参数通道写入回调 (处理加密的设备名称与广播间隔修改)
static ssize_t write_param(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                           const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags;

    yuitag_response_t resp = {0};
    yuitag_device_handle_param_write(g_dev, buf, len, &resp);

    if (resp.channel != YUITAG_NOTIFY_NONE) {
        send_notify_by_ch(resp.channel, resp.data, resp.len);
    }

    if (g_dev->pendingParamSave || g_dev->pendingAdvRefresh) {
        app_proceed();
    }
    yuitag_ble_update_param_value(g_dev);

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

// 读取系统 ID 特征 (由 MAC 转换为 EUI-64 格式, 构造逻辑在 YuiTagCore)
static ssize_t read_system_id(struct bt_conn *conn,
                              const struct bt_gatt_attr *attr,
                              void *buf, uint16_t len, uint16_t offset)
{
    uint8_t mac[6];
    uint8_t sid[8];

    platform_get_mac(mac);
    yuitag_crypto_make_system_id(mac, sid);

    return bt_gatt_attr_read(conn, attr, buf, len, offset, sid, sizeof(sid));
}

#include <zephyr/bluetooth/hci.h>
#if defined(CONFIG_BT_HCI_VS) || defined(CONFIG_BT_CTLR) || defined(CONFIG_BT_LL_SOFTDEVICE)
#include <zephyr/bluetooth/hci_vs.h>
#endif

static void set_adv_tx_power(int8_t power_dbm)
{
    int8_t valid_powers[] = { 4, 3, 0, -4, -8, -12, -16, -20, -40 };
    int8_t selected = -40;
    for (size_t i = 0; i < ARRAY_SIZE(valid_powers); i++) {
        if (power_dbm >= valid_powers[i]) {
            selected = valid_powers[i];
            break;
        }
    }

#if defined(BT_HCI_OP_VS_WRITE_TX_POWER_LEVEL)
    struct bt_hci_cp_vs_write_tx_power_level *cp;
    struct net_buf *buf, *rsp = NULL;

    buf = bt_hci_cmd_alloc(K_FOREVER);
    if (buf) {
        cp = net_buf_add(buf, sizeof(*cp));
        cp->handle_type = BT_HCI_VS_LL_HANDLE_TYPE_ADV;
        cp->handle = sys_cpu_to_le16(0);
        cp->tx_power_level = selected;

        int err = bt_hci_cmd_send_sync(BT_HCI_OP_VS_WRITE_TX_POWER_LEVEL, buf, &rsp);
        if (err) {
            platform_log("[ADV] Set TX power (%d dBm) failed: %d", selected, err);
        } else {
            int8_t actual = selected;
            if (rsp && rsp->len >= sizeof(struct bt_hci_rp_vs_write_tx_power_level)) {
                struct bt_hci_rp_vs_write_tx_power_level *rp = (void *)rsp->data;
                actual = rp->selected_tx_power;
            }
            platform_log("[ADV] Set TX power: %d dBm (actual: %d dBm)", selected, actual);
        }
        if (rsp) {
            net_buf_unref(rsp);
        }
    }
#else
    ARG_UNUSED(selected);
#endif
}

// 组装广播包与扫描响应包并启动广播 (字段布局由 YuiTagCore yuitag_adv 统一构造)
static void start_advertising(YuiTagDevice *dev, bool connectable)
{
    // 主广播包字段段 (flags + 厂商数据 + [绑定后]电量 + [debug]诊断)
    uint8_t adStorage[32];
    yuitag_adv_seg_t adSegs[4];
    size_t adSegCount = yuitag_adv_build(dev, adSegs, ARRAY_SIZE(adSegs),
                                         adStorage, sizeof(adStorage));
    if (adSegCount == 0) {
        platform_log("[ADV] Failed to build adv data");
        g_adv_failed = true;
        return;
    }

    struct bt_data ad[4];
    size_t adLen = 0;
    for (size_t i = 0; i < adSegCount && adLen < ARRAY_SIZE(ad); i++) {
        ad[adLen].type = adSegs[i].type;
        ad[adLen].data_len = adSegs[i].len;
        ad[adLen].data = adSegs[i].data;
        adLen++;
    }

    // 扫描响应包中包含完整设备名称
    uint8_t sdStorage[DEVICE_NAME_MAX_LEN + 1];
    yuitag_adv_seg_t sdSegs[1];
    size_t sdSegCount = yuitag_adv_build_scan_rsp(dev, sdSegs, ARRAY_SIZE(sdSegs),
                                                  sdStorage, sizeof(sdStorage));

    struct bt_data sd[1];
    size_t sdLen = 0;
    if (sdSegCount == 1) {
        sd[0].type = sdSegs[0].type;
        sd[0].data_len = sdSegs[0].len;
        sd[0].data = sdSegs[0].data;
        sdLen = 1;
    }

    // 毫秒折算为 0.625ms 广播单位
    uint32_t units = ((uint32_t)dev->advInterval * 8U) / 5U;
    uint32_t opt = BT_LE_ADV_OPT_USE_IDENTITY;
    opt |= connectable ? BT_LE_ADV_OPT_CONN : BT_LE_ADV_OPT_SCANNABLE;
    struct bt_le_adv_param param = BT_LE_ADV_PARAM_INIT(opt, units, units, NULL);

    int err = bt_le_adv_start(&param, ad, adLen, sd, sdLen);
    if (err) {
        platform_log("[ADV] Start failed: %d", err);
        g_adv_failed = true;
        return;
    }

    set_adv_tx_power(dev->txPower);

    g_adv_failed = false;
    platform_log("[ADV] Mode=%s connectable=%d interval=%dms txPower=%d",
                 dev->isBound ? "Normal" : "Initial", connectable, dev->advInterval, dev->txPower);
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

    // 已绑定设备在连接建立 15.0 秒后尝试温和更新连接参数 (预留充裕窗口给服务发现与交互)
    if (g_dev && g_dev->isBound) {
        k_work_reschedule(&s_conn_param_work, K_MSEC(CONN_PARAM_DELAY_MS));
    }

    // 连接建立后延迟 30ms 重新配置不可连接广播，避开 BT_RX 协议栈清理竞态
    k_work_reschedule(&s_adv_refresh_work, K_MSEC(30));

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
    app_proceed();

    // 连接断开后延迟 30ms 恢复常态可连接广播，避开 BT_RX 协议栈清理竞态
    k_work_reschedule(&s_adv_refresh_work, K_MSEC(30));
    platform_log("[CONN] Disconnected (reason 0x%02x)", reason);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

static void conn_param_work_handler(struct k_work *work)
{
    (void)work;
    if (yuitag_device_is_keys_sync_active()) {
        platform_log("[CONN] Keys sync in progress, postpone conn param update");
        k_work_reschedule(&s_conn_param_work, K_SECONDS(5));
        return;
    }
    if (g_dev) {
        yuitag_ble_try_conn_param_update(g_dev);
    }
}

// 平衡功耗与响应的温和降速参数 (60ms~90ms, latency=0, timeout=4s)
void yuitag_ble_try_conn_param_update(YuiTagDevice *dev)
{
    if (!g_connected || g_conn_param_updated || !g_conn) {
        return;
    }
    if (!dev || !dev->isBound) {
        return;
    }
    if (platform_millis() - g_conn_start_ms < CONN_PARAM_DELAY_MS) {
        return;
    }
    if (yuitag_device_is_keys_sync_active()) {
        platform_log("[CONN] Keys sync in progress, postpone conn param update");
        k_work_reschedule(&s_conn_param_work, K_SECONDS(5));
        return;
    }

    struct bt_le_conn_param param = {
        .interval_min = CONN_PARAM_INTERVAL_MIN_UNITS,
        .interval_max = CONN_PARAM_INTERVAL_MAX_UNITS,
        .latency = CONN_PARAM_LATENCY,
        .timeout = CONN_PARAM_TIMEOUT_UNITS,
    };
    int err = bt_conn_le_param_update(g_conn, &param);
    if (err == 0) {
        g_conn_param_updated = true;
        platform_log("[CONN] Balanced param update requested: 60-90ms, lat=0, to=4s");
    } else {
        platform_log("[CONN] Param update failed: %d", err);
    }
}

void yuitag_ble_init(YuiTagDevice *dev)
{
    g_dev = dev;
    k_work_init_delayable(&s_conn_param_work, conn_param_work_handler);
    k_work_init_delayable(&s_adv_refresh_work, adv_refresh_work_handler);
}