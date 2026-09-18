#ifndef APP_H
#define APP_H
/* ============================================================
 * YuiTag 应用层主逻辑接口
 * ============================================================ */

#include <stdbool.h>

/* --- 主循环事件触发与轮询标志 --- */

// 唤醒主循环执行周期性/延迟任务
void app_proceed(void);

// 查询主循环是否需要继续执行处理
bool app_is_process_required(void);

/* --- 资源保护互斥锁 (禁止在中断中调用) --- */
bool app_mutex_acquire(void);
void app_mutex_release(void);

/* --- 应用层初始化 --- */
void app_init_bt(void);

/* --- OTA DFU 安全授权控制 (通过 0x6F 指令解锁，断开时清空) --- */
void app_ota_authorize(void);
void app_ota_deauthorize(void);
bool app_is_ota_authorized(void);

/* --- 快速上电出厂复位状态查询 --- */
bool app_is_factory_reset_triggered(void);

#endif // APP_H
