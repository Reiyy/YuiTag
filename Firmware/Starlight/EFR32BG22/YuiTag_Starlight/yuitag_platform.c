/**
 * @file yuitag_platform.c
 * @brief YuiTag 硬件抽象驱动层实现 (EFR32BG22)
 */

#include "yuitag_platform.h"
#include "yuitag_device.h"
#include "yuitag_buzzer.h"

#include "sl_sleeptimer.h"
#include "sl_clock_manager.h"
#include "sl_core.h"

// IADC 电池电压采样
#include "em_iadc.h"

// NVM3 持久化存储
#include "nvm3_default.h"
#include "nvm3_generic.h"

// BGAPI 蓝牙栈接口 (获取出厂 MAC)
#include "sl_bt_api.h"

#include <string.h>

/* --- IADC 采样参数 --- */
#define CLK_SRC_ADC_FREQ    2000000U   // 2 MHz IADC 采样源时钟
#define CLK_ADC_FREQ        1000000U   // 1 MHz ADC 转换时钟
#define ADC_RESULT_MAX      0xFFFU     // 12-bit 最大采样值

static uint32_t s_iadc_vref_mv = 1210U;  // 内部参考电压标称值 (mV)
static bool     s_iadc_initialized = false;
static bool     s_iadc_init_failed = false;  // 硬件故障防死循环重试标记

/* --- 系统时间 --- */
uint32_t platform_millis(void)
{
    uint64_t tick = sl_sleeptimer_get_tick_count64();
    uint32_t freq = sl_sleeptimer_get_timer_frequency();
    if (freq == 0) {
        return 0;
    }
    return (uint32_t)((tick * 1000ULL) / freq);
}

uint64_t platform_ticks64(void)
{
    uint64_t tick = sl_sleeptimer_get_tick_count64();
    uint32_t freq = sl_sleeptimer_get_timer_frequency();
    if (freq == 0) {
        return 0;
    }
    return (tick * 1000ULL) / freq;
}

/* --- 电池采样 (IADC 单通道 AVDD/4 测量) --- */

static void iadc_init_for_battery(void)
{
    sl_status_t sc;

    IADC_Init_t init = IADC_INIT_DEFAULT;
    IADC_AllConfigs_t init_all_configs = IADC_ALLCONFIGS_DEFAULT;
    IADC_InitSingle_t init_single = IADC_INITSINGLE_DEFAULT;
    IADC_SingleInput_t single_input = IADC_SINGLEINPUT_DEFAULT;

    sc = sl_clock_manager_enable_bus_clock(SL_BUS_CLOCK_IADC0);
    if (sc != SL_STATUS_OK) {
        return;
    }

    init.srcClkPrescale = IADC_calcSrcClkPrescale(IADC0, CLK_SRC_ADC_FREQ, 0);
    if (init.srcClkPrescale == 0U) {
        platform_log("[IADC] SrcClkPrescale calc failed for %u Hz", CLK_SRC_ADC_FREQ);
        s_iadc_init_failed = true;
        (void)sl_clock_manager_disable_bus_clock(SL_BUS_CLOCK_IADC0);
        return;
    }

    init.warmup = iadcWarmupNormal;
    init_all_configs.configs[0].osrHighSpeed = iadcCfgOsrHighSpeed2x; // 2x 过采样达到 12 位分辨率

    init_all_configs.configs[0].adcClkPrescale =
        IADC_calcAdcClkPrescale(IADC0, CLK_ADC_FREQ, 0,
                                iadcCfgModeNormal, init.srcClkPrescale);
    if (init_all_configs.configs[0].adcClkPrescale == 0U) {
        platform_log("[IADC] AdcClkPrescale calc failed for %u Hz", CLK_ADC_FREQ);
        s_iadc_init_failed = true;
        (void)sl_clock_manager_disable_bus_clock(SL_BUS_CLOCK_IADC0);
        return;
    }

    single_input.posInput = iadcPosInputAvdd; // 选通内部 AVDD/4 测量通道
    s_iadc_vref_mv = init_all_configs.configs[0].vRef;

    IADC_init(IADC0, &init, &init_all_configs);
    IADC_initSingle(IADC0, &init_single, &single_input);
    IADC_clearInt(IADC0, _IADC_IF_MASK);

    s_iadc_initialized = true;

    // 初始关闭 IADC 及总线时钟以节省功耗
    IADC0->EN_CLR = IADC_EN_EN;
    (void)sl_clock_manager_disable_bus_clock(SL_BUS_CLOCK_IADC0);
}

uint8_t platform_read_battery(void)
{
    if (s_iadc_init_failed) {
        return 0;
    }

    if (!s_iadc_initialized) {
        iadc_init_for_battery();
        if (!s_iadc_initialized) {
            s_iadc_init_failed = true;
            return 0;
        }
    }

    sl_status_t sc = sl_clock_manager_enable_bus_clock(SL_BUS_CLOCK_IADC0);
    if (sc != SL_STATUS_OK) {
        return 0;
    }

    IADC0->EN_SET = IADC_EN_EN;
    IADC_clearInt(IADC0, IADC_IF_SINGLEDONE);
    IADC_command(IADC0, iadcCmdStartSingle);

    uint32_t timeout = 1000000U;
    while ((IADC0->IF & IADC_IF_SINGLEDONE) == 0U) {
        if (--timeout == 0U) {
            IADC0->EN_CLR = IADC_EN_EN;
            (void)sl_clock_manager_disable_bus_clock(SL_BUS_CLOCK_IADC0);
            return 0;
        }
    }

    IADC_Result_t result = IADC_pullSingleFifoResult(IADC0);
    IADC_clearInt(IADC0, IADC_IF_SINGLEDONE);

#if defined(IADC_STATUS_SYNCBUSY)
    while ((IADC0->STATUS & IADC_STATUS_SYNCBUSY) != 0U) {
    }
#endif

    // 转换完成立即关闭 IADC
    IADC0->EN_CLR = IADC_EN_EN;
    (void)sl_clock_manager_disable_bus_clock(SL_BUS_CLOCK_IADC0);

    // 计算电池电压 (mV)：AVDD/4 分压输入，内部 ~1.21V 基准，12-bit 分辨率
    // voltage = (result * 4 * vref) / 4095
    uint32_t battery_mv = ((4U * result.data * s_iadc_vref_mv) + (ADC_RESULT_MAX / 2U))
                          / ADC_RESULT_MAX;

    return yuitag_battery_mv_to_percent(battery_mv);
}

/* --- 芯片出厂 MAC 地址 --- */
void platform_get_mac(uint8_t macOut[6])
{
    bd_addr address;
    uint8_t address_type;

    sl_status_t sc = sl_bt_system_get_identity_address(&address, &address_type);
    if (sc == SL_STATUS_OK) {
        // bd_addr 为小端序 (LSB first)，直接拷贝保持一致
        memcpy(macOut, address.addr, 6);
    } else {
        memset(macOut, 0, 6);
    }
}

/* --- NVM3 存储读写接口 --- */

bool platform_storage_load_bool(uint32_t key, bool defaultVal)
{
    uint8_t val;
    sl_status_t sc = nvm3_readData(nvm3_defaultHandle, key, &val, sizeof(val));
    if (sc == SL_STATUS_OK) {
        return val != 0;
    }
    return defaultVal;
}

uint16_t platform_storage_load_u16(uint32_t key, uint16_t defaultVal)
{
    uint16_t val;
    sl_status_t sc = nvm3_readData(nvm3_defaultHandle, key, &val, sizeof(val));
    if (sc == SL_STATUS_OK) {
        return val;
    }
    return defaultVal;
}

size_t platform_storage_load_bytes(uint32_t key, uint8_t *buf, size_t maxLen)
{
    uint32_t type;
    size_t objLen;

    sl_status_t sc = nvm3_getObjectInfo(nvm3_defaultHandle, key, &type, &objLen);
    if (sc != SL_STATUS_OK || type != NVM3_OBJECTTYPE_DATA) {
        return 0;
    }
    if (objLen > maxLen) {
        objLen = maxLen;
    }
    sc = nvm3_readPartialData(nvm3_defaultHandle, key, buf, 0, objLen);
    if (sc != SL_STATUS_OK) {
        return 0;
    }
    return objLen;
}

bool platform_storage_load_string(uint32_t key, char *buf, size_t maxLen)
{
    if (maxLen == 0) {
        return false;
    }
    size_t loaded = platform_storage_load_bytes(key, (uint8_t *)buf, maxLen - 1);
    if (loaded == 0) {
        buf[0] = '\0';
        return false;
    }
    buf[loaded] = '\0'; // 补齐字符串结束符
    return true;
}

void platform_storage_save_bool(uint32_t key, bool val)
{
    uint8_t v = val ? 1 : 0;
    sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, key, &v, sizeof(v));
    if (sc != SL_STATUS_OK) {
        platform_log("[NVM3] Save bool 0x%04X failed: 0x%04X", key, sc);
    }
}

void platform_storage_save_u16(uint32_t key, uint16_t val)
{
    sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, key, &val, sizeof(val));
    if (sc != SL_STATUS_OK) {
        platform_log("[NVM3] Save u16 0x%04X failed: 0x%04X", key, sc);
    }
}

void platform_storage_save_bytes(uint32_t key, const uint8_t *buf, size_t len)
{
    sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, key, (void *)buf, len);
    if (sc != SL_STATUS_OK) {
        platform_log("[NVM3] Save bytes 0x%04X len=%d failed: 0x%04X", key, len, sc);
    }
}

void platform_storage_save_string(uint32_t key, const char *str)
{
    size_t len = strlen(str);
    sl_status_t sc = nvm3_writeData(nvm3_defaultHandle, key, (void *)str, len);
    if (sc != SL_STATUS_OK) {
        platform_log("[NVM3] Save string 0x%04X len=%d failed: 0x%04X", key, len, sc);
    }
}

void platform_storage_remove(uint32_t key)
{
    (void)nvm3_deleteObject(nvm3_defaultHandle, key);
}

void platform_storage_clear_all(void)
{
    (void)nvm3_eraseAll(nvm3_defaultHandle);
}

/* --- 平台驱动初始化 --- */
void platform_init(void)
{
    // 校验 NVM3 默认实例初始化状态
    sl_status_t sc = nvm3_initDefault();
    if (sc == SL_STATUS_OK) {
        platform_log("[NVM3] Init OK");
    } else if (sc == SL_STATUS_NVM3_OPENED_WITH_OTHER_PARAMETERS) {
        platform_log("[NVM3] Init already opened (expected): 0x%04X", sc);
    } else {
        platform_log("[NVM3] Init FAILED: 0x%04X", sc);

        // 尝试关闭后重试恢复
        nvm3_deinitDefault();
        sc = nvm3_initDefault();
        if (sc == SL_STATUS_OK) {
            platform_log("[NVM3] Recovery OK");
        } else {
            platform_log("[NVM3] Recovery FAILED: 0x%04X", sc);
        }
    }

    buzzer_init();
}

// ============================================================
// 平台定时器与事件调度 (基于 Sleeptimer)
// ============================================================
#include "app.h"

static sl_sleeptimer_timer_handle_t s_oneshot_timer;
static platform_timer_callback_t s_oneshot_cb = NULL;
static void *s_oneshot_user_data = NULL;

static void oneshot_sleeptimer_cb(sl_sleeptimer_timer_handle_t *handle, void *data)
{
    (void)handle;
    (void)data;
    if (s_oneshot_cb) {
        s_oneshot_cb(s_oneshot_user_data);
    }
}

void platform_timer_oneshot_start(uint32_t delay_ms, platform_timer_callback_t cb, void *user_data)
{
    s_oneshot_cb = cb;
    s_oneshot_user_data = user_data;
    sl_sleeptimer_restart_timer_ms(&s_oneshot_timer, delay_ms, oneshot_sleeptimer_cb, NULL, 0, 0);
}

void platform_timer_oneshot_stop(void)
{
    sl_sleeptimer_stop_timer(&s_oneshot_timer);
    s_oneshot_cb = NULL;
}

void platform_schedule_process(void)
{
    app_proceed();
}

// ============================================================
// 平台 BLE 控制桥接
// ============================================================
#include "yuitag_ble.h"

void platform_ble_disconnect(void)
{
    yuitag_ble_disconnect();
}

void platform_ble_update_advertising(void *dev, bool rebuild_data)
{
    yuitag_ble_update_advertising((YuiTagDevice *)dev, rebuild_data);
}

void platform_ble_update_dis_values(void *dev)
{
    yuitag_ble_update_dis_values((YuiTagDevice *)dev);
}

void platform_ble_update_param_value(void *dev)
{
    yuitag_ble_update_param_value((YuiTagDevice *)dev);
}

void platform_ble_set_device_name(const char *name)
{
    yuitag_ble_set_device_name(name);
}

void platform_ble_try_conn_param_update(void *dev)
{
    yuitag_ble_try_conn_param_update((YuiTagDevice *)dev);
}

bool platform_ble_adv_failed(void)
{
    return yuitag_ble_adv_failed();
}

// ============================================================
// 平台 OTA 控制桥接
// ============================================================
void platform_ota_authorize(void)
{
    app_ota_authorize();
}

void platform_ota_deauthorize(void)
{
    app_ota_deauthorize();
}
