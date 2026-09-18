#ifndef APP_H
#define APP_H

// ============================================================
// YuiTag 业务事件调度中枢 (工作队列异步处理)
// ============================================================

#include "yuitag_device.h"

// 初始化应用调度器 (绑定工作项与定时器)
void app_init(YuiTagDevice *dev);

// 提交工作队列项，触发异步事件消费处理
void app_proceed(void);

// 标记系统启动初始化完毕，使能事件消费
void app_mark_initialized(void);

#endif // APP_H