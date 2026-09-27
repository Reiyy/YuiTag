// ============================================================
// YuiTag Apple Find My 离线寻物模块核心逻辑 (跨平台版)
// ============================================================

#include "yuitag_config.h"

#ifndef IS_SPARKS

#include "yuitag_findmy.h"
#include "yuitag_crypto.h"
#include "yuitag_platform_api.h"
#include "yuitag_adv.h"

#include <string.h>

static bool s_advertising = false;

static uint8_t  s_seed[FINDMY_SEED_LEN];
static bool     s_seed_loaded = false;

static bool     s_time_synced = false;
static uint32_t s_base_unix_time = 0;
static uint64_t s_base_ms = 0;
static uint64_t s_last_persist_ms = 0;

static uint32_t s_cached_period = 0;
static uint8_t  s_cached_key[FINDMY_KEY_LEN];
static bool     s_key_valid = false;

static uint8_t  s_status_byte = 0;
static uint8_t  s_counter = 0;
static uint8_t  s_last_error = 0;

static uint32_t s_period_seconds = FINDMY_DEFAULT_PERIOD_SEC;

static void load_time_state(void)
{
    bool synced = platform_storage_load_bool(NVM3_KEY_FINDMY_TIME_SYNCED, false);

    if (synced) {
        uint32_t storedTime = 0;
        platform_storage_load_bytes(NVM3_KEY_FINDMY_BASE_TIME,
                                    (uint8_t *)&storedTime, sizeof(storedTime));
        if (storedTime >= FINDMY_MIN_VALID_UNIX_TIME) {
            s_time_synced = true;
            s_base_unix_time = storedTime;
            s_base_ms = platform_ticks64();
            s_last_persist_ms = s_base_ms;
            platform_log("[FINDMY] Time restored: unix=%lu (optimistic)", s_base_unix_time);
            return;
        }
        if (storedTime > 0) {
            platform_log("[FINDMY] Stored time %u too old, discarding", storedTime);
        }
    }

    s_time_synced = false;
    s_base_unix_time = 0;
    s_base_ms = 0;
    platform_log("[FINDMY] Time NOT synced");
}

static void save_time_state(void)
{
    platform_storage_save_bool(NVM3_KEY_FINDMY_TIME_SYNCED, s_time_synced);
    if (s_time_synced) {
        platform_storage_save_bytes(NVM3_KEY_FINDMY_BASE_TIME,
                                    (uint8_t *)&s_base_unix_time, sizeof(s_base_unix_time));
        platform_storage_save_bytes(NVM3_KEY_FINDMY_BASE_TICKS,
                                    (uint8_t *)&s_base_ms, sizeof(s_base_ms));
    }
}

uint32_t yuitag_findmy_get_current_time(void)
{
    if (!s_time_synced) {
        return 0;
    }
    uint64_t now = platform_ticks64();
    uint64_t elapsed = (now >= s_base_ms) ? ((now - s_base_ms) / 1000ULL) : 0;
    return s_base_unix_time + (uint32_t)elapsed;
}

uint32_t yuitag_findmy_get_current_period(void)
{
    uint32_t t = yuitag_findmy_get_current_time();
    if (t == 0 || s_period_seconds == 0) {
        return 0;
    }
    return t / s_period_seconds;
}

bool yuitag_findmy_is_time_synced(void)
{
    return s_time_synced;
}

bool yuitag_findmy_time_sync(uint32_t unix_time)
{
    if (unix_time < 1000000000UL) {
        platform_log("[FINDMY] Time sync rejected: invalid timestamp %lu", unix_time);
        return false;
    }

    s_base_unix_time = unix_time;
    s_base_ms = platform_ticks64();
    s_time_synced = true;
    s_last_persist_ms = s_base_ms;
    s_key_valid = false;

    save_time_state();

    platform_log("[FINDMY] Time synced: unix=%lu period=%lu",
                 unix_time, yuitag_findmy_get_current_period());

    return true;
}

void yuitag_findmy_persist_time(void)
{
    if (!s_time_synced) {
        return;
    }

    uint64_t now = platform_ticks64();
    uint64_t interval_ms = (uint64_t)FINDMY_TIME_PERSIST_INTERVAL_SEC * 1000ULL;
    if ((now - s_last_persist_ms) < interval_ms) {
        return;
    }

    uint32_t cur = yuitag_findmy_get_current_time();
    if (cur == 0) {
        return;
    }

    s_base_unix_time = cur;
    s_base_ms = now;
    s_last_persist_ms = now;
    save_time_state();
    platform_log("[FINDMY] Time persisted: unix=%lu", cur);
}

static void load_or_generate_seed(void)
{
    size_t loaded = platform_storage_load_bytes(NVM3_KEY_FINDMY_SEED,
                                                s_seed, sizeof(s_seed));
    if (loaded == FINDMY_SEED_LEN) {
        s_seed_loaded = true;
        platform_log("[FINDMY] Seed loaded from NVS");
        return;
    }

    uint8_t aesKey[AES_KEY_LEN];
    if (yuitag_crypto_get_aes_key(aesKey)) {
        yuitag_crypto_derive_findmy_seed(aesKey, s_seed);
        memset(aesKey, 0, sizeof(aesKey));
        platform_storage_save_bytes(NVM3_KEY_FINDMY_SEED, s_seed, FINDMY_SEED_LEN);
        s_seed_loaded = true;
        platform_log("[FINDMY] Seed derived from AES key");
        return;
    }

    s_seed_loaded = false;
    platform_log("[FINDMY] Seed not available: AES key missing");
}

bool yuitag_findmy_get_seed(uint8_t seedOut[FINDMY_SEED_LEN])
{
    if (!s_seed_loaded) {
        return false;
    }
    memcpy(seedOut, s_seed, FINDMY_SEED_LEN);
    return true;
}

bool yuitag_findmy_has_seed(void)
{
    return s_seed_loaded;
}

void yuitag_findmy_clear_seed(void)
{
    s_seed_loaded = false;
    s_key_valid = false;
    memset(s_seed, 0, sizeof(s_seed));
}

bool yuitag_findmy_get_current_key(uint8_t keyOut[FINDMY_KEY_LEN])
{
    if (!s_key_valid) {
        return false;
    }
    memcpy(keyOut, s_cached_key, FINDMY_KEY_LEN);
    return true;
}

#ifdef YUITAG_FINDMY_DEBUG
void yuitag_findmy_get_current_addr(uint8_t addrOut[6])
{
    if (s_key_valid) {
        yuitag_findmy_key_to_addr(s_cached_key, addrOut);
    } else {
        memset(addrOut, 0, 6);
    }
}
#endif // YUITAG_FINDMY_DEBUG

uint8_t yuitag_findmy_get_status(void)
{
    uint8_t status = 0;
    if (s_advertising) status |= 0x01;
    if (s_time_synced) status |= 0x02;
    if (s_seed_loaded) status |= 0x04;
    if (s_key_valid)   status |= 0x08;
    return status;
}

uint8_t yuitag_findmy_get_last_error(void)
{
    return s_last_error;
}

static void load_period_config(void)
{
    uint32_t stored = 0;
    size_t loaded = platform_storage_load_bytes(NVM3_KEY_FINDMY_PERIOD_SEC,
                                                (uint8_t *)&stored, sizeof(stored));
    if (loaded == sizeof(stored) && stored >= FINDMY_PERIOD_MIN_SEC
        && stored <= FINDMY_PERIOD_MAX_SEC) {
        s_period_seconds = stored;
    } else {
        s_period_seconds = FINDMY_DEFAULT_PERIOD_SEC;
    }
}

void yuitag_findmy_set_period(uint32_t seconds)
{
    if (seconds < FINDMY_PERIOD_MIN_SEC) seconds = FINDMY_PERIOD_MIN_SEC;
    if (seconds > FINDMY_PERIOD_MAX_SEC) seconds = FINDMY_PERIOD_MAX_SEC;
    if (seconds == s_period_seconds) return;

    s_period_seconds = seconds;
    platform_storage_save_bytes(NVM3_KEY_FINDMY_PERIOD_SEC,
                                (uint8_t *)&s_period_seconds, sizeof(s_period_seconds));
    s_key_valid = false;
    platform_log("[FINDMY] Period set to %lu sec", seconds);
}

uint32_t yuitag_findmy_get_period(void)
{
    return s_period_seconds;
}

uint32_t yuitag_findmy_period_index_to_seconds(uint8_t index)
{
    switch (index) {
        case 0:  return 900;
        case 1:  return 1800;
        case 2:  return 3600;
        case 3:  return 7200;
        default: return 3600;
    }
}

uint8_t yuitag_findmy_period_seconds_to_index(uint32_t seconds)
{
    if (seconds <= 900)  return 0;
    if (seconds <= 1800) return 1;
    if (seconds <= 3600) return 2;
    return 3;
}

static bool derive_key_for_period(uint32_t period, uint8_t keyOut[FINDMY_KEY_LEN])
{
    if (!s_seed_loaded) {
        return false;
    }

    uint8_t private_key[FINDMY_KEY_LEN];
    yuitag_crypto_derive_findmy_private_key(s_seed, period, private_key);

    bool ok = yuitag_crypto_findmy_compute_public_x(private_key, keyOut);
    memset(private_key, 0, sizeof(private_key));

    return ok;
}

static bool ensure_key_for_period(uint32_t period)
{
    if (s_key_valid && period == s_cached_period) {
        return true;
    }
    if (!derive_key_for_period(period, s_cached_key)) {
        return false;
    }
    s_cached_period = period;
    s_key_valid = true;
    return true;
}

static bool apply_key(const uint8_t key[FINDMY_KEY_LEN])
{
    if (!platform_findmy_adv_start(key, s_status_byte)) {
        s_last_error = 3;
        return false;
    }
    return true;
}

void yuitag_findmy_tick(void)
{
    if (!s_advertising || !s_time_synced || !s_seed_loaded) {
        return;
    }

    uint32_t period = yuitag_findmy_get_current_period();
    if (period == 0) {
        return;
    }

    s_counter = (s_counter + 1) & 0x3F;
    s_status_byte = (s_status_byte & 0xC0) | s_counter;

    if (s_key_valid) {
        platform_findmy_adv_update_data(s_cached_key, s_status_byte);
    }

    if (s_key_valid && period == s_cached_period) {
        return;
    }

    if (!ensure_key_for_period(period)) {
        return;
    }

    apply_key(s_cached_key);
    platform_log("[FINDMY] Period %u, key rotated", period);
}

void yuitag_findmy_update_battery(uint8_t battery_percent)
{
    uint8_t battery_bits;
    if (battery_percent > 80) {
        battery_bits = 0x00;
    } else if (battery_percent > 50) {
        battery_bits = 0x40;
    } else if (battery_percent > 30) {
        battery_bits = 0x80;
    } else {
        battery_bits = 0xC0;
    }

    uint8_t old_battery = s_status_byte & 0xC0;
    if (old_battery == battery_bits) {
        return;
    }

    s_status_byte = battery_bits | (s_status_byte & 0x3F);

    if (s_advertising && s_key_valid) {
        platform_findmy_adv_update_data(s_cached_key, s_status_byte);
        platform_log("[FINDMY] Battery updated: %d%% -> bits=0x%02X",
                     battery_percent, battery_bits);
    }
}

bool yuitag_findmy_is_enabled(void)
{
    return s_advertising;
}

static bool start_advertising(void)
{
    if (!s_seed_loaded) {
        s_last_error = 1;
        platform_log("[FINDMY] Not advertising: no seed");
        return false;
    }

    s_counter = 0;
    s_status_byte = 0;

    if (s_time_synced) {
        uint32_t period = yuitag_findmy_get_current_period();
        if (period == 0) {
            s_last_error = 5;
            platform_log("[FINDMY] Not advertising: period is 0");
            return false;
        }
        if (!ensure_key_for_period(period)) {
            s_last_error = 6;
            platform_log("[FINDMY] Not advertising: key derivation failed");
            return false;
        }
        if (!apply_key(s_cached_key)) {
            return false;
        }
        s_advertising = true;
        s_last_error = 0;
        platform_log("[FINDMY] Advertising started (time-synced)");
        return true;
    }

    s_last_error = 2;
    platform_log("[FINDMY] Not advertising: time not synced");
    return false;
}

bool yuitag_findmy_enable(void)
{
    if (s_advertising) {
        return true;
    }

    if (!s_seed_loaded) {
        load_or_generate_seed();
    }

    if (!s_seed_loaded) {
        s_last_error = 1;
        platform_log("[FINDMY] Cannot enable: no seed (AES key not available)");
        return false;
    }

    if (!s_time_synced) {
        s_last_error = 2;
        platform_log("[FINDMY] Cannot enable: time not synced");
        return false;
    }

    return start_advertising();
}

void yuitag_findmy_disable(void)
{
    if (!s_advertising) {
        return;
    }

    platform_findmy_adv_stop();

    s_advertising = false;
    s_key_valid = false;
    platform_log("[FINDMY] Disabled");
}

void yuitag_findmy_init(void)
{
    s_advertising = false;
    s_key_valid = false;
    s_cached_period = 0;
    load_or_generate_seed();
    load_time_state();
    load_period_config();

    platform_log("[FINDMY] Init: seed=%s time=%s period=%us",
                 s_seed_loaded ? "yes" : "no",
                 s_time_synced ? "synced" : "no",
                 s_period_seconds);
}

#endif // IS_SPARKS
