// ============================================================
// YuiTag 平台驱动层实现
// 硬件平台: Nordic nRF52810 / Zephyr RTOS
// ============================================================

#include "yuitag_platform.h"
#include "yuitag_buzzer.h"

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/kvss/nvs.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/dt-bindings/adc/nrf-saadc.h>
#include <zephyr/sys/printk.h>

#include <string.h>

// ============================================================
// NVS 存储分区配置
// ============================================================
#define NVS_PARTITION        storage_partition
#define NVS_PARTITION_DEVICE PARTITION_DEVICE(NVS_PARTITION)
#define NVS_PARTITION_OFFSET PARTITION_OFFSET(NVS_PARTITION)

static struct nvs_fs g_nvs_fs;
static bool g_nvs_ready = false;

// ============================================================
// 系统时钟
// ============================================================
uint32_t platform_millis(void)
{
    return k_uptime_get_32();
}

uint64_t platform_ticks64(void)
{
    return (uint64_t)k_uptime_get();
}

// ============================================================
// 电池电量采集 (SAADC)
// ============================================================
// nRF52810 内部采样 VDD (电源轨电压): 增益 1/6，内部参考基准 0.6V (满量程 3.6V)。
// 14位采样分辨率，首次读取自动执行校准，并将采集到的毫伏值线性映射至 0~100%。
#define BATTERY_ADC_NODE DT_PATH(zephyr_user)

static int16_t s_batt_raw;

uint8_t platform_read_battery(void)
{
    const struct device *adc = DEVICE_DT_GET(DT_IO_CHANNELS_CTLR(BATTERY_ADC_NODE));
    static bool s_calibrated = false;

    if (!device_is_ready(adc)) {
        return 0;
    }

    struct adc_channel_cfg ch_cfg = {
        .gain             = ADC_GAIN_1_6,
        .reference        = ADC_REF_INTERNAL,
        .acquisition_time = ADC_ACQ_TIME(ADC_ACQ_TIME_MICROSECONDS, 40),
        .input_positive   = NRF_SAADC_VDD,
    };
    if (adc_channel_setup(adc, &ch_cfg) != 0) {
        return 0;
    }

    struct adc_sequence seq = {
        .channels      = BIT(0),
        .buffer        = &s_batt_raw,
        .buffer_size   = sizeof(s_batt_raw),
        .resolution    = 14,
        .oversampling  = 4,
        .calibrate     = !s_calibrated,
    };
    if (adc_read(adc, &seq) != 0) {
        return 0;
    }
    s_calibrated = true;

    int32_t val = s_batt_raw;
    if (adc_raw_to_millivolts(adc_ref_internal(adc), ch_cfg.gain,
                              seq.resolution, &val) != 0) {
        return 0;
    }

    uint32_t battery_mv = (uint32_t)val;

    // 根据预设电压阈值线性折算为百分比
    if (battery_mv >= BATTERY_FULL_MV) {
        return 100;
    }
    if (battery_mv <= BATTERY_EMPTY_MV) {
        return 0;
    }
    return (uint8_t)(((battery_mv - BATTERY_EMPTY_MV) * 100U)
                     / (BATTERY_FULL_MV - BATTERY_EMPTY_MV));
}

// ============================================================
// 芯片唯一标识 (获取 BLE Identity MAC)
// ============================================================
void platform_get_mac(uint8_t macOut[6])
{
    bt_addr_le_t addrs[1];
    size_t count = 1;

    // bt_id_get 必须在 bt_enable() 完成就绪后调用
    if (bt_is_ready()) {
        bt_id_get(addrs, &count);
        if (count >= 1) {
            memcpy(macOut, addrs[0].a.val, 6);
            return;
        }
    }
    memset(macOut, 0, 6);
}

// ============================================================
// NVS 持久化存储接口
// ============================================================

bool platform_storage_load_bool(uint32_t key, bool defaultVal)
{
    uint8_t val = defaultVal ? 1 : 0;
    if (g_nvs_ready && nvs_read(&g_nvs_fs, (uint16_t)key, &val, sizeof(val)) == sizeof(val)) {
        return val != 0;
    }
    return defaultVal;
}

uint16_t platform_storage_load_u16(uint32_t key, uint16_t defaultVal)
{
    uint16_t val = defaultVal;
    if (g_nvs_ready && nvs_read(&g_nvs_fs, (uint16_t)key, &val, sizeof(val)) == sizeof(val)) {
        return val;
    }
    return defaultVal;
}

size_t platform_storage_load_bytes(uint32_t key, uint8_t *buf, size_t maxLen)
{
    if (!g_nvs_ready) {
        return 0;
    }
    ssize_t rc = nvs_read(&g_nvs_fs, (uint16_t)key, buf, maxLen);
    if (rc <= 0) {
        return 0;
    }
    if ((size_t)rc > maxLen) {
        return 0;
    }
    return (size_t)rc;
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
    buf[loaded] = '\0';
    return true;
}

void platform_storage_save_bool(uint32_t key, bool val)
{
    uint8_t v = val ? 1 : 0;
    if (g_nvs_ready) {
        int rc = nvs_write(&g_nvs_fs, (uint16_t)key, &v, sizeof(v));
        if (rc < 0) {
            platform_log("[NVS] write bool key=0x%04X failed: %d", key, rc);
        }
    }
}

void platform_storage_save_u16(uint32_t key, uint16_t val)
{
    if (g_nvs_ready) {
        int rc = nvs_write(&g_nvs_fs, (uint16_t)key, &val, sizeof(val));
        if (rc < 0) {
            platform_log("[NVS] write u16 key=0x%04X failed: %d", key, rc);
        }
    }
}

void platform_storage_save_bytes(uint32_t key, const uint8_t *buf, size_t len)
{
    if (g_nvs_ready) {
        int rc = nvs_write(&g_nvs_fs, (uint16_t)key, buf, len);
        if (rc < 0) {
            platform_log("[NVS] write bytes key=0x%04X failed: %d", key, rc);
        }
    }
}

void platform_storage_save_string(uint32_t key, const char *str)
{
    if (g_nvs_ready) {
        int rc = nvs_write(&g_nvs_fs, (uint16_t)key, str, strlen(str));
        if (rc < 0) {
            platform_log("[NVS] write string key=0x%04X failed: %d", key, rc);
        }
    }
}

void platform_storage_remove(uint32_t key)
{
    if (g_nvs_ready) {
        nvs_delete(&g_nvs_fs, (uint16_t)key);
    }
}

void platform_storage_clear_all(void)
{
    if (g_nvs_ready) {
        nvs_clear(&g_nvs_fs);
    }
}

// ============================================================
// 平台与外设初始化
// ============================================================
void platform_init(void)
{
    struct flash_pages_info info;
    int rc;

    // 在 storage_partition 分区挂载 NVS 文件系统
    g_nvs_fs.flash_device = NVS_PARTITION_DEVICE;
    if (!device_is_ready(g_nvs_fs.flash_device)) {
        platform_log("[NVS] Flash device not ready");
    } else {
        g_nvs_fs.offset = NVS_PARTITION_OFFSET;
        rc = flash_get_page_info_by_offs(g_nvs_fs.flash_device,
                                         g_nvs_fs.offset, &info);
        if (rc) {
            platform_log("[NVS] flash_get_page_info_by_offs failed: %d", rc);
        } else {
            g_nvs_fs.sector_size = info.size;
            g_nvs_fs.sector_count = 6U;  // storage 分区大小 24 KB / 单扇区 4 KB = 6 扇区
            rc = nvs_mount(&g_nvs_fs);
            if (rc) {
                // 初次挂载或数据损坏时执行全分区清理并重新挂载
                platform_log("[NVS] Mount failed (%d), clearing", rc);
                nvs_clear(&g_nvs_fs);
                rc = nvs_mount(&g_nvs_fs);
            }
            if (rc) {
                platform_log("[NVS] Remount failed: %d", rc);
            } else {
                g_nvs_ready = true;
                platform_log("[NVS] Ready (sector=%u x%u)",
                             g_nvs_fs.sector_size, g_nvs_fs.sector_count);
            }
        }
    }

    buzzer_init();
}

// ============================================================
// 平台定时器与事件调度
// ============================================================
#include "app.h"

static struct k_timer s_oneshot_timer;
static platform_timer_callback_t s_oneshot_cb = NULL;
static void *s_oneshot_user_data = NULL;

static void oneshot_timer_expiry(struct k_timer *timer)
{
    (void)timer;
    if (s_oneshot_cb) {
        s_oneshot_cb(s_oneshot_user_data);
    }
}

void platform_timer_oneshot_start(uint32_t delay_ms, platform_timer_callback_t cb, void *user_data)
{
    s_oneshot_cb = cb;
    s_oneshot_user_data = user_data;
    k_timer_init(&s_oneshot_timer, oneshot_timer_expiry, NULL);
    k_timer_start(&s_oneshot_timer, K_MSEC(delay_ms), K_NO_WAIT);
}

void platform_timer_oneshot_stop(void)
{
    k_timer_stop(&s_oneshot_timer);
    s_oneshot_cb = NULL;
}

void platform_schedule_process(void)
{
    app_proceed();
}

// ============================================================
// 平台加密后端 (基于 YuiTagCore/lib)
// ============================================================
#include "sha256.h"
#include "tiny_aes.h"
#include <zephyr/random/random.h>

void platform_crypto_init(void)
{
}

bool platform_crypto_sha256(const uint8_t *in, size_t len, uint8_t out[32])
{
    sha256(in, len, out);
    return true;
}

bool platform_crypto_aes128_ctr(const uint8_t key[16], const uint8_t *in, size_t len, uint8_t *out)
{
    aes128_set_key(key);
    aes128_ctr_xcrypt(in, out, len);
    return true;
}

void platform_crypto_random(uint8_t *buf, size_t len)
{
    sys_csrand_get(buf, len);
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
