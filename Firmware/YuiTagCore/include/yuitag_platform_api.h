#ifndef YUITAG_PLATFORM_API_H
#define YUITAG_PLATFORM_API_H

// ============================================================
// YuiTag 平台适配层契约接口
// YuiTagCore 中的所有代码只能调用此文件中声明的函数，
// 不允许直接包含任何平台 SDK 头文件。
// 各平台需实现此文件中声明的所有函数。
// ============================================================

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#include "yuitag_config.h"

// ---- 日志 ----
// 由各平台在 yuitag_config.h 中通过宏覆盖实现
// Core 代码中调用 platform_log()，若平台未定义则默认为空操作
#ifndef platform_log
#define platform_log(fmt, ...) ((void)0)
#endif

// ---- 系统时间 ----
uint32_t platform_millis(void);      // 开机以来的毫秒数 (32-bit, 会溢出)
uint64_t platform_ticks64(void);     // 开机以来的毫秒数 (64-bit, 不溢出)

// ---- 电池电量 ----
uint8_t platform_read_battery(void); // 返回 0~100 百分比

// ---- 芯片身份 ----
void platform_get_mac(uint8_t macOut[6]);

// ---- 持久化存储 (key 统一为 uint32_t) ----
bool     platform_storage_load_bool(uint32_t key, bool defaultVal);
uint16_t platform_storage_load_u16(uint32_t key, uint16_t defaultVal);
size_t   platform_storage_load_bytes(uint32_t key, uint8_t *buf, size_t maxLen);
bool     platform_storage_load_string(uint32_t key, char *buf, size_t maxLen);

void platform_storage_save_bool(uint32_t key, bool val);
void platform_storage_save_u16(uint32_t key, uint16_t val);
void platform_storage_save_bytes(uint32_t key, const uint8_t *buf, size_t len);
void platform_storage_save_string(uint32_t key, const char *str);

void platform_storage_remove(uint32_t key);
void platform_storage_clear_all(void);

// ---- 加密后端 (由各平台的 crypto_platform.c 实现) ----
void platform_crypto_init(void);
bool platform_crypto_sha256(const uint8_t *in, size_t len, uint8_t out[32]);
bool platform_crypto_aes128_ctr(const uint8_t key[16],
                                const uint8_t *in, size_t len, uint8_t *out);
void platform_crypto_random(uint8_t *buf, size_t len);

// ---- ECDH 密钥协商 (仅 Starlight) ----
#ifndef IS_SPARKS
bool platform_ecdh_generate_keypair(void);
bool platform_ecdh_compute_shared(const uint8_t *peerPub, size_t len,
                                   uint8_t aesKeyOut[16]);
bool platform_ecdh_export_pubkey(uint8_t *out, size_t *outLen);
void platform_ecdh_cleanup(void);
bool platform_ecdh_ready(void);
#endif

// ---- FindMy ECC 后端 (仅 Starlight) ----
#ifndef IS_SPARKS
bool platform_findmy_compute_public_x(const uint8_t priv[28],
                                       uint8_t pubXOut[28]);
void platform_findmy_derive_seed(const uint8_t aesKey[16],
                                  uint8_t seedOut[32]);
void platform_findmy_derive_private_key(const uint8_t seed[32],
                                         uint32_t period, uint8_t out[28]);

// ---- FindMy 广播控制回调 (仅 Starlight) ----
bool platform_findmy_adv_start(const uint8_t key[28], uint8_t status_byte);
bool platform_findmy_adv_update_data(const uint8_t key[28], uint8_t status_byte);
void platform_findmy_adv_stop(void);
void platform_findmy_adv_set_tx_power(int8_t power_dbm);
#endif

// ---- 定时器抽象 (替代 Zephyr k_timer / SiLabs sleeptimer) ----
typedef void (*platform_timer_callback_t)(void *user_data);
void platform_timer_oneshot_start(uint32_t delay_ms,
                                   platform_timer_callback_t cb,
                                   void *user_data);
void platform_timer_oneshot_stop(void);

// ---- 异步调度触发 (替代 app_proceed / k_work_submit) ----
void platform_schedule_process(void);

// ---- BLE 控制回调 (由各平台 BLE 层实现) ----
void platform_ble_disconnect(void);
void platform_ble_update_advertising(void *dev, bool rebuild_data);
void platform_ble_update_dis_values(void *dev);
void platform_ble_update_param_value(void *dev);
void platform_ble_set_device_name(const char *name);
void platform_ble_try_conn_param_update(void *dev);
bool platform_ble_adv_failed(void);

// ---- OTA 控制 (仅 Starlight) ----
#ifndef IS_SPARKS
void platform_ota_authorize(void);
void platform_ota_deauthorize(void);
#endif

#endif // YUITAG_PLATFORM_API_H
