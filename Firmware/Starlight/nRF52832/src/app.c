/* ============================================================
 * YuiTag 应用调度层实现 (延时工作队列与周期定时器)
 * ============================================================ */

#include "app.h"
#include "yuitag_platform.h"
#include "yuitag_ble.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

#include <zephyr/kernel.h>

#if defined(CONFIG_MCUMGR_SMP_COMMAND_STATUS_HOOKS)
#include <zephyr/mgmt/mcumgr/mgmt/callbacks.h>
#endif

static YuiTagDevice *g_dev;
static bool g_initialized = false;
static bool g_ota_authorized = false;

static struct k_work s_process_work;
static struct k_timer s_periodic_timer;

#if defined(CONFIG_MCUMGR_SMP_COMMAND_STATUS_HOOKS)
/* ------------------------------------------------------------
 * MCUmgr SMP 指令接收安全拦截回调 (仅在 0x6F 授权后放行)
 * ------------------------------------------------------------ */
static enum mgmt_cb_return smp_cmd_recv_callback(uint32_t event, enum mgmt_cb_return prev_status,
                                                  int32_t *rc, uint16_t *group, bool *abort_more,
                                                  void *data, size_t data_size)
{
    (void)event; (void)prev_status; (void)group; (void)abort_more; (void)data; (void)data_size;
    if (!app_is_ota_authorized()) {
        platform_log("[OTA-SEC] SMP command rejected: unauthorized");
        *rc = 2; /* MGMT_ERR_EACCESS (Access denied) */
        return MGMT_CB_ERROR_RC;
    }
    return MGMT_CB_OK;
}

static struct mgmt_callback s_smp_cb = {
    .callback = smp_cmd_recv_callback,
    .event_id = MGMT_EVT_OP_CMD_RECV,
};
#endif

static void process_action_handler(struct k_work *work);

/* ------------------------------------------------------------
 * 主任务事件处理 (工作队列回调)
 * ------------------------------------------------------------ */
static void process_action_handler(struct k_work *work)
{
    (void)work;
    if (!g_initialized || !g_dev) {
        return;
    }

    yuitag_device_process_events(g_dev);
}

static void periodic_timer_handler(struct k_timer *timer)
{
    (void)timer;
    app_proceed();
}

/* ------------------------------------------------------------
 * 模块初始化
 * ------------------------------------------------------------ */
void app_init(YuiTagDevice *dev)
{
    g_dev = dev;

    k_work_init(&s_process_work, process_action_handler);
    k_timer_init(&s_periodic_timer, periodic_timer_handler, NULL);
    k_timer_start(&s_periodic_timer,
                  K_SECONDS(PERIODIC_CHECK_INTERVAL_MS / 1000),
                  K_SECONDS(PERIODIC_CHECK_INTERVAL_MS / 1000));

#if defined(CONFIG_MCUMGR_SMP_COMMAND_STATUS_HOOKS)
    mgmt_callback_register(&s_smp_cb);
#endif
}

void app_proceed(void)
{
    k_work_submit(&s_process_work);
}

void app_mark_initialized(void)
{
    g_initialized = true;
    app_proceed();
}

/* ------------------------------------------------------------
 * OTA 授权状态管理
 * ------------------------------------------------------------ */
void app_ota_authorize(void)
{
    g_ota_authorized = true;
    platform_log("[OTA] Authorized");
    yuitag_ble_set_fast_conn_params();
}

void app_ota_deauthorize(void)
{
    g_ota_authorized = false;
    platform_log("[OTA] Deauthorized");
}

bool app_is_ota_authorized(void)
{
    return g_ota_authorized;
}
