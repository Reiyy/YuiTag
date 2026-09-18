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

#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
#include <zephyr/mgmt/mcumgr/transport/smp_bt.h>
#endif

static YuiTagDevice *g_dev;
static bool g_initialized = false;
static bool g_ota_authorized = false;
static bool g_smp_registered = false;

static struct k_work s_process_work;
static struct k_timer s_periodic_timer;

#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
static struct k_work s_ota_work;
static void ota_work_handler(struct k_work *work);
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

#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
    k_work_init(&s_ota_work, ota_work_handler);
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
 * OTA 授权与 SMP 动态服务注册 (延时执行)
 * ------------------------------------------------------------ */
#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
static void ota_work_handler(struct k_work *work)
{
    (void)work;

    if (g_ota_authorized && !g_smp_registered) {
        if (smp_bt_register() == 0) {
            g_smp_registered = true;
            platform_log("[OTA] SMP service registered");
        } else {
            platform_log("[OTA] SMP service register failed");
        }
    } else if (!g_ota_authorized && g_smp_registered) {
        smp_bt_unregister();
        g_smp_registered = false;
        platform_log("[OTA] SMP service unregistered");
    }
}
#endif

void app_ota_authorize(void)
{
    g_ota_authorized = true;
    platform_log("[OTA] Authorized");
#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
    k_work_submit(&s_ota_work);
#endif
}

void app_ota_deauthorize(void)
{
    g_ota_authorized = false;
#if defined(CONFIG_MCUMGR_TRANSPORT_BT_DYNAMIC_SVC_REGISTRATION)
    k_work_submit(&s_ota_work);
#endif
}

bool app_is_ota_authorized(void)
{
    return g_ota_authorized;
}
