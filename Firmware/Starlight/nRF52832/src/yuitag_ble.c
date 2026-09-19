// ============================================================
// YuiTag BLE 协议栈实现 - Zephyr Bluetooth Host
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

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

// ============================================================
// 自定义 128 位 UUID (基准: 722Axxxx-8a74-432f-9055-7ed8a5584a11)
// ============================================================
#define YUITAG_UUID_SVC   BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa00, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_BIND  BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa42, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_CMD   BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa43, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))
#define YUITAG_UUID_PARAM BT_UUID_DECLARE_128(BT_UUID_128_ENCODE(0x722aaa46, 0x8a74, 0x432f, 0x9055, 0x7ed8a5584a11))

// ============================================================
// 模块内部状态
// ============================================================
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

// 专用绑定加密工作队列 (4096 字节栈空间)
// ECDH 软件计算栈开销较大，移至独立线程处理，避免在 BLE RX 线程触发栈溢出
#define BIND_CRYPTO_STACK_SIZE 4096
static K_THREAD_STACK_DEFINE(s_bind_crypto_stack, BIND_CRYPTO_STACK_SIZE);
static struct k_work_q s_bind_crypto_wq;
static struct k_work s_bind_crypto_work;
static uint8_t s_peer_pubkey[ECDH_PUBKEY_LEN];

// ============================================================
// CCCD 特征通知使能状态变更回调
// ============================================================
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

// ============================================================
// 特征通知发送辅助函数
// ============================================================
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

static void notify_status(const struct bt_uuid *uuid, uint8_t status)
{
    uint8_t resp[2] = { 0x00, status };
    notify_by_uuid(uuid, resp, 2);
}

// ============================================================
// 绑定协商加密任务处理 (独立大栈工作队列线程)
// ============================================================
static void bind_crypto_work_handler(struct k_work *work)
{
    (void)work;

    if (!g_conn) {
        g_dev->pendingBindAbort = true;
        app_proceed();
        return;
    }

    uint8_t tagPubKey[ECDH_PUBKEY_LEN];
    size_t pubKeyLen = 0;

    if (!yuitag_device_bind_process_peer_key(g_dev, s_peer_pubkey, ECDH_PUBKEY_LEN,
                                              tagPubKey, &pubKeyLen)) {
        uint8_t fail = STATUS_FAILED;
        notify_by_uuid(YUITAG_UUID_BIND, &fail, 1);
        g_dev->pendingBindAbort = true;
        app_proceed();
        return;
    }

    if (pubKeyLen > (size_t)(bt_gatt_get_mtu(g_conn) - 3)) {
        platform_log("[BIND] MTU too small for pubkey (%d bytes, MTU=%u)",
                     pubKeyLen, bt_gatt_get_mtu(g_conn));
        g_dev->pendingBindAbort = true;
        app_proceed();
        return;
    }

    notify_by_uuid(YUITAG_UUID_BIND, tagPubKey, pubKeyLen);
    platform_log("[BIND] Sent Tag pubkey (%d bytes)", pubKeyLen);
}

// ============================================================
// GATT 读特征回调 (DIS 设备信息与参数通道)
// ============================================================
static ssize_t read_dis_string(struct bt_conn *conn,
                               const struct bt_gatt_attr *attr,
                               void *buf, uint16_t len, uint16_t offset)
{
    const char *str = (const char *)attr->user_data;
    return bt_gatt_attr_read(conn, attr, buf, len, offset, str, strlen(str));
}

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

// ============================================================
// GATT 写特征回调
// ============================================================
static ssize_t write_bind(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                          const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags;

    if (g_dev->isBound) {
        platform_log("[BIND] Ignored: already bound");
        return len;
    }

    if (g_dev->waitingVerify) {
        platform_log("[BIND] Ignored: bind already in progress");
        return len;
    }

    if (len != ECDH_PUBKEY_LEN || ((const uint8_t *)buf)[0] != 0x04) {
        platform_log("[BIND] Invalid pubkey format (len=%d, fmt=0x%02X)",
                     len, len > 0 ? ((const uint8_t *)buf)[0] : 0x00);
        uint8_t fail = STATUS_FAILED;
        notify_by_uuid(YUITAG_UUID_BIND, &fail, 1);
        g_dev->pendingBindAbort = true;
        app_proceed();
        return len;
    }

    // 标记绑定中，将 ECDH 计算提交至大栈独立工作队列执行
    g_dev->waitingVerify = true;
    memcpy(s_peer_pubkey, buf, ECDH_PUBKEY_LEN);
    k_work_submit_to_queue(&s_bind_crypto_wq, &s_bind_crypto_work);
    return len;
}

static ssize_t write_cmd(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                         const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
    (void)conn; (void)attr; (void)offset; (void)flags;
    const uint8_t *data = buf;

    if (g_dev->waitingVerify && yuitag_crypto_has_aes_key() && !g_dev->isBound) {
        platform_log("[VERIFY] Received encrypted verify data (%d bytes)", len);
        if (yuitag_device_bind_verify(g_dev, data, len)) {
            uint8_t resp = BIND_VERIFY_OK;
            notify_by_uuid(YUITAG_UUID_CMD, &resp, 1);
            // 延后广播刷新至主循环工作队列执行 (避免在 BLE RX 线程执行阻塞式 HCI 操作)
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

#ifndef IS_SPARKS
    if (cmdId == CMD_FINDMY_TIME_SYNC) {
        uint8_t syncStatus = yuitag_device_findmy_handle_time_sync(plain, len);
        notify_status(YUITAG_UUID_CMD, syncStatus);
        if (syncStatus == STATUS_SUCCESS) {
            app_proceed();
        }
        return len;
    }
#endif

    status = yuitag_device_dispatch_cmd(g_dev, cmdId, action);

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
        notify_by_uuid(YUITAG_UUID_PARAM, notifyBuf, sizeof(notifyBuf));
        return len;
    }
#endif

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

// ============================================================
// GATT Service Definition
// ============================================================

static ssize_t read_serial(struct bt_conn *conn,
                           const struct bt_gatt_attr *attr,
                           void *buf, uint16_t len, uint16_t offset);

static ssize_t read_system_id(struct bt_conn *conn,
                              const struct bt_gatt_attr *attr,
                              void *buf, uint16_t len, uint16_t offset);

/* 设备信息服务 (DIS, 0x180A) */
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

/* YuiTag 核心服务 (722aaa00-...) */
BT_GATT_SERVICE_DEFINE(yuitag_core_svc,
    BT_GATT_PRIMARY_SERVICE(YUITAG_UUID_SVC),

    BT_GATT_CHARACTERISTIC(YUITAG_UUID_BIND,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_WRITE, NULL, write_bind, NULL),
    BT_GATT_CCC(ccc_bind_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    BT_GATT_CHARACTERISTIC(YUITAG_UUID_CMD,
        BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_WRITE, NULL, write_cmd, NULL),
    BT_GATT_CCC(ccc_cmd_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    BT_GATT_CHARACTERISTIC(YUITAG_UUID_PARAM,
        BT_GATT_CHRC_READ | BT_GATT_CHRC_WRITE | BT_GATT_CHRC_NOTIFY,
        BT_GATT_PERM_READ | BT_GATT_PERM_WRITE, read_param, write_param, NULL),
    BT_GATT_CCC(ccc_param_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ------------------------------------------------------------
 * DIS 特征值动态读取
 * ------------------------------------------------------------ */
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

static ssize_t read_system_id(struct bt_conn *conn,
                              const struct bt_gatt_attr *attr,
                              void *buf, uint16_t len, uint16_t offset)
{
    /* 生成 8 字节 Bluetooth System ID (EUI-64, 构造逻辑在 YuiTagCore) */
    uint8_t mac[6];
    uint8_t sid[8];

    platform_get_mac(mac);
    yuitag_crypto_make_system_id(mac, sid);

    return bt_gatt_attr_read(conn, attr, buf, len, offset, sid, sizeof(sid));
}

/* ------------------------------------------------------------
 * 广播控制
 * ------------------------------------------------------------ */
/* 字段布局由 YuiTagCore yuitag_adv 统一构造, 此处仅映射至 Zephyr bt_data */
static void start_advertising(YuiTagDevice *dev, bool connectable)
{
    /* 主广播包字段段 (flags + 厂商数据 + [绑定后]电量 + [debug]诊断) */
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

    /* 扫描响应包: 完整设备名称 */
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

/* ------------------------------------------------------------
 * GATT 属性更新
 * ------------------------------------------------------------ */
void yuitag_ble_update_param_value(YuiTagDevice *dev)
{
    /* 参数通道在 read_param 回调中动态生成，无需主动更新缓存 */
    (void)dev;
}

void yuitag_ble_update_dis_values(YuiTagDevice *dev)
{
    /* 解绑或重置设备名称后，同步更新 Zephyr GAP 设备名 */
    bt_set_name(dev->deviceName);
}

void yuitag_ble_set_device_name(const char *name)
{
    bt_set_name(name);
}

/* ------------------------------------------------------------
 * 连接状态回调
 * ------------------------------------------------------------ */
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

    /* 已绑定状态下，延时 2.5 秒申请更新连接参数 */
    if (g_dev && g_dev->isBound) {
        k_work_reschedule(&s_conn_param_work, K_MSEC(2500));
    }

    /* 连接建立后重新启动不可连接广播 (供手机端跟踪 RSSI)；转交主循环处理以保证安全 */
    g_dev->pendingAdvRefresh = true;
    app_proceed();

    platform_log("[CONN] Connected");
}

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

    app_ota_deauthorize();
    yuitag_device_on_disconnected(g_dev);

    /* 断开后通知主循环恢复正常可连接广播 */
    g_dev->pendingAdvRefresh = true;
    app_proceed();
    platform_log("[CONN] Disconnected (reason 0x%02x)", reason);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

/* ------------------------------------------------------------
 * 延时连接参数更新
 * ------------------------------------------------------------ */
static void conn_param_work_handler(struct k_work *work)
{
    (void)work;
    if (g_dev) {
        yuitag_ble_try_conn_param_update(g_dev);
    }
}

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
    if (app_is_ota_authorized()) {
        return;
    }

    /* 符合 Apple 配件设计指南规范:
     * 1. 间隔 1000ms (800 * 1.25ms)
     * 2. 从机延迟 1 -> 等效有效间隔 2000ms (<= 2.0s)
     * 3. 监控超时 6000ms (600 * 10ms <= 6.0s 且 > 2.0s * 2)
     */
    struct bt_le_conn_param param = {
        .interval_min = 800,
        .interval_max = 800,
        .latency = 1,
        .timeout = 600,
    };
    int err = bt_conn_le_param_update(g_conn, &param);
    if (err == 0) {
        g_conn_param_updated = true;
        platform_log("[CONN] Apple-compliant param update requested: 1000ms, lat=1, to=6s");
    } else {
        platform_log("[CONN] Param update failed: %d", err);
    }
}

/* ------------------------------------------------------------
 * 模块初始化
 * ------------------------------------------------------------ */
void yuitag_ble_init(YuiTagDevice *dev)
{
    g_dev = dev;

    k_work_init_delayable(&s_conn_param_work, conn_param_work_handler);

    k_work_queue_init(&s_bind_crypto_wq);
    k_work_queue_start(&s_bind_crypto_wq, s_bind_crypto_stack,
                       K_THREAD_STACK_SIZEOF(s_bind_crypto_stack),
                       K_PRIO_PREEMPT(10), NULL);
    k_work_init(&s_bind_crypto_work, bind_crypto_work_handler);
}
