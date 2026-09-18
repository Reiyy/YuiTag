// ============================================================
// YuiTag 业务事件调度中枢 (工作队列异步处理)
// 设计目的: 将耗时的 Flash 擦写 (NVS)、广播重启等操作移出蓝牙回调
//          与中断上下文，统一在 Zephyr 系统工作队列 (System Workqueue) 中安全消费。
// ============================================================

#include "app.h"
#include "yuitag_platform.h"
#include "yuitag_ble.h"

#include <zephyr/kernel.h>

static YuiTagDevice *g_dev;
static bool g_initialized = false;

static struct k_work s_process_work;
static struct k_timer s_periodic_timer;

static void process_action_handler(struct k_work *work);

// 核心业务异步消费处理函数 (调用 YuiTagCore 统一事件处理中枢)
static void process_action_handler(struct k_work *work)
{
    (void)work;
    if (!g_initialized || !g_dev) {
        return;
    }

    yuitag_device_process_events(g_dev);
}

// 系统周期定时器中断回调 (定期向工作队列提交消费任务)
static void periodic_timer_handler(struct k_timer *timer)
{
    (void)timer;
    app_proceed();
}

void app_init(YuiTagDevice *dev)
{
    g_dev = dev;

    k_work_init(&s_process_work, process_action_handler);
    k_timer_init(&s_periodic_timer, periodic_timer_handler, NULL);
    k_timer_start(&s_periodic_timer,
                  K_SECONDS(PERIODIC_CHECK_INTERVAL_MS / 1000),
                  K_SECONDS(PERIODIC_CHECK_INTERVAL_MS / 1000));
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