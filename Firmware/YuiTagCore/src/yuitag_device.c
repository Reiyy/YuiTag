// ============================================================
// YuiTag Device State Machine and Protocol Logic (Unified)
// ============================================================

#include "yuitag_config.h"
#include "yuitag_device.h"
#include "yuitag_crypto.h"
#include "yuitag_buzzer_types.h"
#include "yuitag_platform_api.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

#include <string.h>
#include <stdio.h>

static void pwr_cycle_clear_callback(void *arg)
{
    YuiTagDevice *dev = (YuiTagDevice *)arg;
    if (dev) {
        dev->pendingPwrCycleClear = true;
    }
    platform_schedule_process();
}

void yuitag_device_init(YuiTagDevice *dev, const uint8_t mac[6])
{
    memset(dev, 0, sizeof(YuiTagDevice));

    yuitag_crypto_compute_uid(mac, dev->uid);
    yuitag_crypto_get_short_uid(dev->uid, dev->shortUid);
    yuitag_crypto_uid_to_hex_str(dev->uid, dev->serial, sizeof(dev->serial));

    yuitag_crypto_make_device_name(dev->shortUid, dev->deviceName, sizeof(dev->deviceName));

    platform_log("[INIT] Serial: %s", dev->serial);
    platform_log("[INIT] ShortUID: %02X %02X", dev->shortUid[0], dev->shortUid[1]);

    char savedName[DEVICE_NAME_MAX_LEN + 1];
    bool hasConfig = platform_storage_load_string(NVM3_KEY_DEV_NAME, savedName, sizeof(savedName));
    if (hasConfig) {
        strncpy(dev->deviceName, savedName, DEVICE_NAME_MAX_LEN);
        dev->deviceName[DEVICE_NAME_MAX_LEN] = '\0';
    }

    dev->isBound = platform_storage_load_bool(NVM3_KEY_IS_BOUND, false);
    {
        uint16_t defaultAdv = dev->isBound ? ADV_INTERVAL_NORMAL_MS : ADV_INTERVAL_UNBOUND_MS;
        dev->advInterval = platform_storage_load_u16(NVM3_KEY_ADV_INTERVAL, defaultAdv);
    }
    dev->findModeInterval = platform_storage_load_u16(NVM3_KEY_FIND_INTERVAL, ADV_INTERVAL_FIND_MS);

    // Self-healing check
    if (dev->isBound && dev->advInterval == dev->findModeInterval && dev->advInterval < ADV_INTERVAL_NORMAL_MS) {
        platform_log("[INIT] Adv interval (%dms) corrupted by find mode, healing to %dms",
                     dev->advInterval, ADV_INTERVAL_NORMAL_MS);
        dev->advInterval = ADV_INTERVAL_NORMAL_MS;
        platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    }
    dev->savedAdvInterval = dev->advInterval;

#ifndef IS_SPARKS
    dev->findmyEnabled = platform_storage_load_bool(NVM3_KEY_FINDMY_ENABLED, true);
#else
    dev->findmyEnabled = platform_storage_load_bool(NVM3_KEY_FINDMY_ENABLED, false);
#endif

    if (dev->isBound) {
        uint8_t keyBuf[AES_KEY_LEN];
        size_t keyLen = platform_storage_load_bytes(NVM3_KEY_AES_KEY, keyBuf, sizeof(keyBuf));
        if (keyLen == AES_KEY_LEN) {
            yuitag_crypto_set_aes_key(keyBuf);
        } else {
            dev->isBound = false;
            platform_log("[INIT] AES key corrupt, reverting to UNBOUND");
        }
    }

    if (!hasConfig) {
        platform_log("[INIT] First boot: saving defaults to storage");
        platform_storage_save_string(NVM3_KEY_DEV_NAME, dev->deviceName);
        platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
        platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
        platform_storage_save_bool(NVM3_KEY_IS_BOUND, dev->isBound);
        platform_storage_save_bool(NVM3_KEY_FINDMY_ENABLED, dev->findmyEnabled);
    } else {
        platform_log("[INIT] Config loaded from storage (isBound=%d)", dev->isBound);
    }

    dev->batteryLevel       = platform_read_battery();
    dev->lastBatteryCheckMs = platform_millis();

    platform_log("[INIT] Name:         %s", dev->deviceName);
    platform_log("[INIT] Interval:     %dms", dev->advInterval);
    platform_log("[INIT] FindInterval: %dms", dev->findModeInterval);
    platform_log("[INIT] State:        %s", dev->isBound ? "BOUND" : "UNBOUND");
#ifndef IS_SPARKS
    platform_log("[INIT] FindMy:       %s", dev->findmyEnabled ? "enabled" : "disabled");
#endif
    platform_log("[INIT] Battery:      %d%%", dev->batteryLevel);
}

bool yuitag_device_power_cycle_check(YuiTagDevice *dev)
{
    uint32_t pwrCnt = 0;
    platform_storage_load_bytes(NVM3_KEY_PWR_CYCLE_CNT, (uint8_t *)&pwrCnt, sizeof(pwrCnt));
    pwrCnt++;
    platform_storage_save_bytes(NVM3_KEY_PWR_CYCLE_CNT, (uint8_t *)&pwrCnt, sizeof(pwrCnt));

    if (pwrCnt >= POWER_CYCLE_RESET_COUNT) {
        platform_log("[RESET] count=%d -> FACTORY RESET", pwrCnt);
        yuitag_device_clear_binding(dev);
        platform_factory_reset_beep();
        pwrCnt = 0;
        platform_storage_save_bytes(NVM3_KEY_PWR_CYCLE_CNT, (uint8_t *)&pwrCnt, sizeof(pwrCnt));
        dev->powerCycleStartMs = 0;
        return true;
    } else {
        platform_log("[RESET] count=%d/%d, %ds window",
                     pwrCnt, POWER_CYCLE_RESET_COUNT,
                     POWER_CYCLE_WINDOW_MS / 1000);
        dev->powerCycleStartMs = 1;
        platform_timer_oneshot_start(POWER_CYCLE_WINDOW_MS, pwr_cycle_clear_callback, dev);
        return false;
    }
}

bool yuitag_device_tick(YuiTagDevice *dev)
{
    uint32_t now = platform_millis();

    if (dev->pendingPwrCycleClear) {
        dev->pendingPwrCycleClear = false;
        uint32_t zero = 0;
        platform_storage_save_bytes(NVM3_KEY_PWR_CYCLE_CNT, (uint8_t *)&zero, sizeof(zero));
        dev->powerCycleStartMs = 0;
        platform_log("[RESET] Window expired, counter cleared");
    }

    if ((now - dev->lastBatteryCheckMs) >= BATTERY_CHECK_INTERVAL_MS) {
        dev->batteryLevel = platform_read_battery();
        dev->lastBatteryCheckMs = now;
        platform_log("[BATT] Periodic: %d%%", dev->batteryLevel);
        if (dev->isBound) {
            return true;
        }
    }

    return false;
}

bool yuitag_device_check_heartbeat_timeout(YuiTagDevice *dev)
{
    if (!dev->connected) {
        return false;
    }
    uint32_t now = platform_millis();
    if ((now - dev->lastHeartbeatMs) >= HEARTBEAT_TIMEOUT_MS) {
        platform_log("[HEARTBEAT] Timeout (%ds)", HEARTBEAT_TIMEOUT_MS / 1000);
        return true;
    }
    return false;
}

void yuitag_device_on_connected(YuiTagDevice *dev)
{
    dev->connected = true;
    dev->lastHeartbeatMs = platform_millis();
    platform_log("[GATT] Connected");

#ifndef IS_SPARKS
    if (!dev->isBound) {
        if (!yuitag_crypto_ecdh_generate_keypair()) {
            platform_log("[GATT] ECDH keygen FAILED");
        }
    }
#endif
}

void yuitag_device_on_disconnected(YuiTagDevice *dev)
{
    dev->connected = false;

    if (dev->ringActive) {
        dev->ringActive = false;
        buzzer_stop_melody();
        platform_log("[GATT] Disconnected: ring stopped");
    }

    if (dev->findModeActive) {
        dev->findModeActive = false;
        if (dev->savedAdvInterval >= ADV_INTERVAL_MIN_MS &&
            dev->savedAdvInterval <= ADV_INTERVAL_MAX_MS &&
            dev->savedAdvInterval != dev->findModeInterval) {
            dev->advInterval = dev->savedAdvInterval;
        } else {
            dev->advInterval = dev->isBound ? ADV_INTERVAL_NORMAL_MS : ADV_INTERVAL_UNBOUND_MS;
        }
        platform_log("[FIND] Disconnected: restored interval=%dms", dev->advInterval);
    }

#ifndef IS_SPARKS
    if (!dev->isBound && yuitag_crypto_ecdh_ready()) {
        yuitag_crypto_ecdh_cleanup();
        dev->waitingVerify = false;
    }
#else
    dev->waitingVerify = false;
#endif

    platform_log("[GATT] Disconnected");
}

uint8_t yuitag_device_decrypt_validate(YuiTagDevice *dev,
                                       const uint8_t *cipherData, size_t len,
                                       uint8_t *outCmdId, uint8_t *outAction,
                                       uint8_t *outPlain)
{
    (void)dev;
    if (len < FRAME_MIN_LEN) {
        platform_log("[VALIDATE] Too short: %d bytes", len);
        return STATUS_FAILED;
    }

    uint8_t plain[CMD_DECRYPT_BUF_MAX_LEN];
    if (len > sizeof(plain)) {
        platform_log("[VALIDATE] Too long: %d bytes", len);
        return STATUS_FAILED;
    }

    if (!yuitag_crypto_aes_ctr(cipherData, len, plain)) {
        return STATUS_AES_DECRYPT_ERROR;
    }

    *outCmdId  = plain[0];
    *outAction = plain[1];

    uint8_t expectedCrc = yuitag_crypto_crc8(plain, len - 1);
    if (expectedCrc != plain[len - 1]) {
        platform_log("[VALIDATE] CRC mismatch: expected=0x%02X got=0x%02X",
                     expectedCrc, plain[len - 1]);
        return STATUS_CRC_ERROR;
    }

    if (outPlain != NULL) {
        memcpy(outPlain, plain, len);
    }

    return STATUS_SUCCESS;
}

static const buzzer_note_t s_ring_melody[] = {
    BUZZER_NOTE_BEEP(250),
    BUZZER_NOTE_REST(80),
    BUZZER_NOTE_BEEP(100),
    BUZZER_NOTE_REST(80),
    BUZZER_NOTE_BEEP(100),
    BUZZER_NOTE_REST(80),
    BUZZER_NOTE_BEEP(400),
    BUZZER_NOTE_REST(200),
};
#define RING_MELODY_LEN (sizeof(s_ring_melody) / sizeof(s_ring_melody[0]))

uint8_t yuitag_device_dispatch_cmd(YuiTagDevice *dev, uint8_t cmdId, uint8_t action)
{
    if (!dev->isBound && cmdId != CMD_BIND_VERIFY) {
        platform_log("[CMD] Rejected: device unbound");
        return STATUS_FAILED;
    }

    switch (cmdId) {
    case CMD_BIND_VERIFY:
        return STATUS_FAILED;

    case CMD_RING:
        if (action == ACTION_ON) {
            dev->ringActive = true;
            buzzer_play_melody(s_ring_melody, RING_MELODY_LEN, true, NULL);
            platform_log("[RING] ON");
            return STATUS_SUCCESS;
        } else if (action == ACTION_OFF) {
            dev->ringActive = false;
            buzzer_stop_melody();
            platform_log("[RING] OFF");
            return STATUS_SUCCESS;
        }
        return STATUS_FAILED;

    case CMD_UNBIND:
        if (action == ACTION_CONFIRM) {
            platform_log("[UNBIND] Confirmed");
            dev->pendingUnbindClear = true;
            return STATUS_SUCCESS;
        }
        return STATUS_FAILED;

    case CMD_HEARTBEAT:
        if (action == ACTION_CONFIRM) {
            dev->lastHeartbeatMs = platform_millis();
            return STATUS_NO_REPLY;
        }
        return STATUS_FAILED;

    case CMD_BATTERY:
        if (action == ACTION_ON) {
            dev->batteryLevel = platform_read_battery();
            dev->lastBatteryCheckMs = platform_millis();
            platform_log("[BATT] Triggered: %d%%", dev->batteryLevel);
            dev->pendingAdvRefresh = true;
            return STATUS_SUCCESS;
        }
        return STATUS_FAILED;

    case CMD_FIND_MODE:
        if (action == ACTION_ON) {
            if (!dev->findModeActive) {
                dev->savedAdvInterval = dev->advInterval;
                dev->findModeActive = true;
            }
            dev->advInterval = dev->findModeInterval;
            platform_log("[FIND] ON: interval=%dms (saved=%dms)", dev->advInterval, dev->savedAdvInterval);
            dev->pendingAdvRefresh = true;
            return STATUS_SUCCESS;
        } else if (action == ACTION_OFF) {
            if (dev->findModeActive) {
                dev->findModeActive = false;
                if (dev->savedAdvInterval >= ADV_INTERVAL_MIN_MS &&
                    dev->savedAdvInterval <= ADV_INTERVAL_MAX_MS &&
                    dev->savedAdvInterval != dev->findModeInterval) {
                    dev->advInterval = dev->savedAdvInterval;
                } else {
                    dev->advInterval = dev->isBound ? ADV_INTERVAL_NORMAL_MS : ADV_INTERVAL_UNBOUND_MS;
                }
                platform_log("[FIND] OFF: restored interval=%dms", dev->advInterval);
                dev->pendingAdvRefresh = true;
            }
            return STATUS_SUCCESS;
        }
        return STATUS_FAILED;

#ifndef IS_SPARKS
    case CMD_OTA_AUTHORIZE:
        if (action == ACTION_CONFIRM) {
            platform_ota_authorize();
            return STATUS_SUCCESS;
        }
        return STATUS_FAILED;

    case CMD_FINDMY_STATUS:
        return STATUS_SUCCESS;
#endif

    default:
        platform_log("[CMD] Unknown: 0x%02X", cmdId);
        return STATUS_UNKNOWN_CMD;
    }
}

#ifdef IS_SPARKS
bool yuitag_device_bind_generate_key(YuiTagDevice *dev, uint8_t *aesKeyOut)
{
    if (dev->isBound) {
        platform_log("[BIND] Already bound, ignore");
        return false;
    }

    yuitag_crypto_generate_aes_key(aesKeyOut);
    dev->waitingVerify = true;
    platform_log("[BIND] Key generated, waiting for APP verify (cmd 0x56)");
    return true;
}
#else
bool yuitag_device_bind_process_peer_key(YuiTagDevice *dev,
                                         const uint8_t *peerPubKey, size_t peerKeyLen,
                                         uint8_t *tagPubKeyOut, size_t *pubKeyLenOut)
{
    if (dev->isBound) {
        platform_log("[BIND] Already bound, ignore");
        return false;
    }

    if (peerKeyLen != ECDH_PUBKEY_LEN || peerPubKey[0] != 0x04) {
        platform_log("[BIND] Invalid pubkey format (len=%d, fmt=0x%02X)",
                     peerKeyLen, peerPubKey[0]);
        return false;
    }

    platform_log("[BIND] Received APP pubkey (%d bytes)", peerKeyLen);

    uint8_t aesKey[AES_KEY_LEN];
    if (!yuitag_crypto_ecdh_compute_shared(peerPubKey, peerKeyLen, aesKey)) {
        platform_log("[BIND] ECDH compute failed");
        memset(aesKey, 0, sizeof(aesKey));
        return false;
    }
    yuitag_crypto_set_aes_key(aesKey);
    memset(aesKey, 0, sizeof(aesKey));

    if (!yuitag_crypto_ecdh_export_pubkey(tagPubKeyOut, pubKeyLenOut)) {
        platform_log("[BIND] Export Tag pubkey failed");
        return false;
    }

    dev->waitingVerify = true;
    platform_log("[BIND] Waiting for APP verify (cmd 0x56)");
    return true;
}
#endif

bool yuitag_device_bind_verify(YuiTagDevice *dev, const uint8_t *cipherData, size_t len)
{
    if (!dev->waitingVerify || dev->isBound) {
        return false;
    }

    platform_log("[VERIFY] Received verify data (%d bytes)", len);

    if (len != 2) {
        platform_log("[VERIFY] Invalid length: %d", len);
        dev->waitingVerify = false;
#ifndef IS_SPARKS
        yuitag_crypto_ecdh_cleanup();
#endif
        return false;
    }

    uint8_t plain[4];
    if (!yuitag_crypto_aes_ctr(cipherData, len, plain)) {
        platform_log("[VERIFY] AES decrypt failed");
        dev->waitingVerify = false;
#ifndef IS_SPARKS
        yuitag_crypto_ecdh_cleanup();
#endif
        return false;
    }

    uint8_t vCmd = plain[0];
    uint8_t vCrc = plain[1];
    uint8_t expectedCrc = yuitag_crypto_crc8(plain, 1);

    if (vCmd == CMD_BIND_VERIFY && vCrc == expectedCrc) {
        dev->isBound = true;
        dev->waitingVerify = false;

        dev->advInterval = ADV_INTERVAL_NORMAL_MS;
        dev->savedAdvInterval = dev->advInterval;
        dev->pendingBindSave = true;

#ifndef IS_SPARKS
        yuitag_crypto_ecdh_cleanup();
#endif

        platform_log("[BIND] Verify OK -> BOUND");
        return true;
    }

    platform_log("[BIND] Verify failed: cmd=0x%02X crc=0x%02X(expected=0x%02X)",
                 vCmd, vCrc, expectedCrc);
    dev->waitingVerify = false;
#ifndef IS_SPARKS
    yuitag_crypto_ecdh_cleanup();
#endif
    yuitag_crypto_clear_aes_key();
    return false;
}

void yuitag_device_clear_binding(YuiTagDevice *dev)
{
    dev->isBound = false;
    dev->waitingVerify = false;

    platform_timer_oneshot_stop();

    yuitag_crypto_clear_aes_key();
#ifndef IS_SPARKS
    yuitag_crypto_ecdh_cleanup();
#endif

    platform_storage_clear_all();

#ifndef IS_SPARKS
    yuitag_findmy_clear_seed();
#endif

    if (dev->shortUid[0] == 0 && dev->shortUid[1] == 0) {
        uint8_t mac[6];
        platform_get_mac(mac);
        yuitag_crypto_compute_uid(mac, dev->uid);
        yuitag_crypto_get_short_uid(dev->uid, dev->shortUid);
    }
    yuitag_crypto_make_device_name(dev->shortUid, dev->deviceName, sizeof(dev->deviceName));
    dev->advInterval = ADV_INTERVAL_UNBOUND_MS;
    dev->findModeInterval = ADV_INTERVAL_FIND_MS;
    dev->savedAdvInterval = dev->advInterval;
    dev->findmyEnabled = false;

#ifndef IS_SPARKS
    dev->pendingFindmyChange = true;
#endif

    platform_storage_save_string(NVM3_KEY_DEV_NAME, dev->deviceName);
    platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
    platform_storage_save_bool(NVM3_KEY_IS_BOUND, false);
    platform_storage_save_bool(NVM3_KEY_FINDMY_ENABLED, false);

#ifndef IS_SPARKS
    platform_ota_deauthorize();
#endif

    platform_log("[BIND] Cleared -> UNBOUND (factory reset)");
}

void yuitag_device_abort_bind(YuiTagDevice *dev)
{
    dev->isBound = false;
    dev->waitingVerify = false;

    yuitag_crypto_clear_aes_key();
#ifndef IS_SPARKS
    yuitag_crypto_ecdh_cleanup();
    platform_ota_deauthorize();
#endif

    platform_log("[BIND] Aborted (no storage erase)");
}

void yuitag_device_persist_bind(YuiTagDevice *dev)
{
    uint8_t aesKey[AES_KEY_LEN];
    if (yuitag_crypto_get_aes_key(aesKey)) {
        platform_storage_save_bytes(NVM3_KEY_AES_KEY, aesKey, AES_KEY_LEN);
    }
    platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    platform_storage_save_bool(NVM3_KEY_IS_BOUND, true);
    platform_log("[PERSIST] Bind data saved to storage");
}

void yuitag_device_persist_params(YuiTagDevice *dev)
{
    platform_storage_save_string(NVM3_KEY_DEV_NAME, dev->deviceName);
    uint16_t normalInterval = dev->findModeActive ? dev->savedAdvInterval : dev->advInterval;
    if (normalInterval < ADV_INTERVAL_MIN_MS || normalInterval > ADV_INTERVAL_MAX_MS ||
        (dev->findModeActive && normalInterval == dev->findModeInterval)) {
        normalInterval = dev->isBound ? ADV_INTERVAL_NORMAL_MS : ADV_INTERVAL_UNBOUND_MS;
    }
    platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, normalInterval);
    platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
    platform_storage_save_bool(NVM3_KEY_FINDMY_ENABLED, dev->findmyEnabled);
    platform_log("[PERSIST] Device params saved to storage (normalAdv=%dms)", normalInterval);
}

size_t yuitag_device_param_read(YuiTagDevice *dev, uint8_t *buf, size_t maxLen)
{
    size_t nameLen = strlen(dev->deviceName);
    if (nameLen > DEVICE_NAME_MAX_LEN) {
        nameLen = DEVICE_NAME_MAX_LEN;
    }

    size_t total = 1 + nameLen + 6;
    if (total > maxLen) {
        return 0;
    }

    buf[0] = (uint8_t)nameLen;
    memcpy(buf + 1, dev->deviceName, nameLen);
    buf[1 + nameLen] = dev->advInterval & 0xFF;
    buf[2 + nameLen] = (dev->advInterval >> 8) & 0xFF;
    buf[3 + nameLen] = dev->findModeInterval & 0xFF;
    buf[4 + nameLen] = (dev->findModeInterval >> 8) & 0xFF;

#ifdef IS_SPARKS
    buf[5 + nameLen] = 0xFF;
    buf[6 + nameLen] = 2;
#else
    buf[5 + nameLen] = dev->findmyEnabled ? 1 : 0;
    buf[6 + nameLen] = yuitag_findmy_period_seconds_to_index(yuitag_findmy_get_period());
#endif

    platform_log("[PARAM] Read: name=%s adv=%dms find=%dms findmy=%d period=%d",
                 dev->deviceName, dev->advInterval, dev->findModeInterval,
                 dev->findmyEnabled, buf[6 + nameLen]);
    return total;
}

uint8_t yuitag_device_param_write(YuiTagDevice *dev, const uint8_t *cipherData, size_t len)
{
    if (!dev->isBound) {
        platform_log("[PARAM] Rejected: unbound");
        return STATUS_FAILED;
    }

    if (len < FRAME_MIN_LEN) {
        return STATUS_FAILED;
    }

    if (len > PARAM_DECRYPT_BUF_MAX_LEN) {
        platform_log("[PARAM] Rejected: too long %d (max %d)",
                     len, PARAM_DECRYPT_BUF_MAX_LEN);
        return STATUS_FAILED;
    }

    uint8_t decBuf[PARAM_DECRYPT_BUF_MAX_LEN];
    if (!yuitag_crypto_aes_ctr(cipherData, len, decBuf)) {
        return STATUS_AES_DECRYPT_ERROR;
    }

    uint8_t cmdId = decBuf[0];

    if (yuitag_crypto_crc8(decBuf, len - 1) != decBuf[len - 1]) {
        return STATUS_CRC_ERROR;
    }

    uint8_t status = STATUS_FAILED;

    switch (cmdId) {
    case CMD_NAME_WRITE: {
        size_t nameLen = len - 2;
        if (nameLen > 0 && nameLen <= DEVICE_NAME_MAX_LEN) {
            memcpy(dev->deviceName, decBuf + 1, nameLen);
            dev->deviceName[nameLen] = '\0';
            dev->pendingParamSave = true;
            platform_ble_set_device_name(dev->deviceName);
            status = STATUS_SUCCESS;
            dev->pendingAdvRefresh = true;
            platform_log("[PARAM] Name: %s", dev->deviceName);
        }
        break;
    }
    case CMD_INTERVAL_CONFIG: {
        if (len >= 4) {
            uint16_t newInterval = decBuf[1] | ((uint16_t)decBuf[2] << 8);
            if (newInterval >= ADV_INTERVAL_MIN_MS && newInterval <= ADV_INTERVAL_MAX_MS) {
                dev->advInterval = newInterval;
                dev->savedAdvInterval = newInterval;
                dev->pendingParamSave = true;
                status = STATUS_SUCCESS;
                dev->pendingAdvRefresh = true;
                platform_log("[PARAM] Adv interval: %dms", dev->advInterval);
            }
        }
        break;
    }
    case CMD_FIND_INTERVAL_CONFIG: {
        if (len >= 4) {
            uint16_t newInterval = decBuf[1] | ((uint16_t)decBuf[2] << 8);
            if (newInterval >= ADV_INTERVAL_MIN_MS && newInterval <= ADV_INTERVAL_MAX_MS) {
                dev->findModeInterval = newInterval;
                dev->pendingParamSave = true;
                if (dev->findModeActive) {
                    dev->advInterval = dev->findModeInterval;
                    dev->pendingAdvRefresh = true;
                }
                status = STATUS_SUCCESS;
                platform_log("[PARAM] Find interval: %dms", dev->findModeInterval);
            }
        }
        break;
    }
#ifndef IS_SPARKS
    case CMD_FINDMY_ENABLE: {
        if (len >= 3) {
            uint8_t action = decBuf[1];
            if (action == ACTION_ON) {
                if (!yuitag_findmy_is_time_synced()) {
                    platform_log("[PARAM] FindMy enable rejected: time not synced");
                    status = STATUS_FAILED;
                    break;
                }
                dev->findmyEnabled = true;
                dev->pendingFindmyChange = true;
                dev->pendingParamSave = true;
                status = STATUS_SUCCESS;
                platform_log("[PARAM] FindMy: enable");
            } else if (action == ACTION_OFF) {
                dev->findmyEnabled = false;
                dev->pendingFindmyChange = true;
                dev->pendingParamSave = true;
                status = STATUS_SUCCESS;
                platform_log("[PARAM] FindMy: disable");
            }
        }
        break;
    }
    case CMD_FINDMY_PERIOD_CONFIG: {
        if (yuitag_device_findmy_period_config(decBuf, len)) {
            dev->pendingParamSave = true;
            status = STATUS_SUCCESS;
        }
        break;
    }
#endif
    default:
        status = STATUS_UNKNOWN_CMD;
        break;
    }

    platform_log("[PARAM] cmdId=0x%02X -> status=0x%02X", cmdId, status);
    return status;
}

#ifndef IS_SPARKS
uint8_t yuitag_device_findmy_handle_time_sync(const uint8_t *plain, size_t len)
{
    if (len < 6 || len > CMD_DECRYPT_BUF_MAX_LEN) {
        return STATUS_FAILED;
    }

    uint32_t timestamp = (uint32_t)plain[1]
                       | ((uint32_t)plain[2] << 8)
                       | ((uint32_t)plain[3] << 16)
                       | ((uint32_t)plain[4] << 24);

    bool ok = yuitag_findmy_time_sync(timestamp);
    platform_log("[FINDMY] TIME_SYNC: unix=%lu ok=%d", timestamp, ok);
    return ok ? STATUS_SUCCESS : STATUS_FAILED;
}

bool yuitag_device_findmy_period_config(const uint8_t *decryptedPayload, size_t len)
{
    if (len < 3) return false;
    uint8_t index = decryptedPayload[1];
    uint32_t seconds = yuitag_findmy_period_index_to_seconds(index);
    yuitag_findmy_set_period(seconds);
    platform_log("[PARAM] FindMy period: index=%d -> %lus", index, seconds);
    return true;
}
#endif

void yuitag_device_process_events(YuiTagDevice *dev)
{
    if (!dev) {
        return;
    }

    // 1. heartbeat timeout -> disconnect
    if (yuitag_device_check_heartbeat_timeout(dev)) {
        platform_ble_disconnect();
    }

    // 2. try conn param update
    platform_ble_try_conn_param_update(dev);

    // 3. device tick -> update adv if needed
    if (yuitag_device_tick(dev)) {
        platform_ble_update_advertising(dev, true);
#ifndef IS_SPARKS
        if (dev->findmyEnabled) {
            yuitag_findmy_update_battery(dev->batteryLevel);
        }
#endif
    }

#ifndef IS_SPARKS
    if (dev->findmyEnabled) {
        yuitag_findmy_tick();
    }
    if (yuitag_findmy_is_time_synced()) {
        yuitag_findmy_persist_time();
    }
#endif

    // 4. pending adv refresh
    if (dev->pendingAdvRefresh) {
        dev->pendingAdvRefresh = false;
        platform_ble_update_advertising(dev, false);
    }

    // 5. adv failed retry
    if (platform_ble_adv_failed()) {
        platform_ble_update_advertising(dev, true);
    }

    // 6. unbind clear
    if (dev->pendingUnbindClear) {
        dev->pendingUnbindClear = false;
        platform_log("[UNBIND] Async clear binding");
        yuitag_device_clear_binding(dev);
        platform_ble_update_advertising(dev, true);
        platform_ble_update_dis_values(dev);
    }

    // 7. bind save
    if (dev->pendingBindSave) {
        dev->pendingBindSave = false;
        yuitag_device_persist_bind(dev);
    }

    // 8. bind abort
    if (dev->pendingBindAbort) {
        dev->pendingBindAbort = false;
        yuitag_device_abort_bind(dev);
    }

#ifndef IS_SPARKS
    if (dev->pendingFindmyChange) {
        dev->pendingFindmyChange = false;
        dev->pendingParamSave = true;
        if (dev->findmyEnabled) {
            if (!yuitag_findmy_enable()) {
                dev->findmyEnabled = false;
                platform_log("[FINDMY] Enable failed, reverting flag");
            }
            yuitag_findmy_update_battery(dev->batteryLevel);
        } else {
            yuitag_findmy_disable();
        }
        platform_ble_update_advertising(dev, true);
    }
#endif

    // 9. param save
    if (dev->pendingParamSave) {
        dev->pendingParamSave = false;
        yuitag_device_persist_params(dev);
    }
}
