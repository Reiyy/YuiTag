#ifndef YUITAG_FINDMY_H
#define YUITAG_FINDMY_H

// ============================================================
// YuiTag Apple Find My 离线寻物模块接口 (跨平台核心逻辑)
// 广播控制由各平台通过 platform_findmy_adv_* 回调实现。
// 整个模块仅在 Starlight 版本编译。
// ============================================================

#ifndef IS_SPARKS

#include "yuitag_config_common.h"
#include <stdint.h>
#include <stdbool.h>

#define FINDMY_ADV_INTERVAL_MS  1000

// ---- 模块生命周期 ----
void yuitag_findmy_init(void);
void yuitag_findmy_tick(void);
void yuitag_findmy_update_battery(uint8_t battery_percent);

// ---- 使能控制 ----
bool yuitag_findmy_enable(void);
void yuitag_findmy_disable(void);
bool yuitag_findmy_is_enabled(void);

// ---- 时间管理 ----
bool     yuitag_findmy_time_sync(uint32_t unix_time);
uint32_t yuitag_findmy_get_current_time(void);
uint32_t yuitag_findmy_get_current_period(void);
bool     yuitag_findmy_is_time_synced(void);
void     yuitag_findmy_persist_time(void);

// ---- 种子密钥管理 ----
bool yuitag_findmy_get_seed(uint8_t seedOut[FINDMY_SEED_LEN]);
bool yuitag_findmy_has_seed(void);
void yuitag_findmy_clear_seed(void);

// ---- 密钥与状态查询 ----
bool    yuitag_findmy_get_current_key(uint8_t keyOut[FINDMY_KEY_LEN]);
uint8_t yuitag_findmy_get_status(void);
uint8_t yuitag_findmy_get_last_error(void);
#ifdef YUITAG_FINDMY_DEBUG
void    yuitag_findmy_get_current_addr(uint8_t addrOut[6]);
#endif

// ---- 轮转周期配置 ----
void     yuitag_findmy_set_period(uint32_t seconds);
uint32_t yuitag_findmy_get_period(void);
uint32_t yuitag_findmy_period_index_to_seconds(uint8_t index);
uint8_t  yuitag_findmy_period_seconds_to_index(uint32_t seconds);

#endif // !IS_SPARKS
#endif // YUITAG_FINDMY_H
