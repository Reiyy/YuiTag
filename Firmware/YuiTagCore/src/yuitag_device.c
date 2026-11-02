// ============================================================
// YuiTag Device State Machine and Protocol Logic (Unified)
// ============================================================

#include "yuitag_config.h"
#include "yuitag_device.h"
#include "yuitag_crypto.h"
#include "yuitag_buzzer_types.h"
#include "yuitag_platform_api.h"

#include "yuitag_findmy.h"

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

    // Self-healing check: 范围异常或寻物残留纠偏
    if (dev->advInterval < ADV_INTERVAL_MIN_MS || dev->advInterval > ADV_INTERVAL_MAX_MS) {
        platform_log("[INIT] Adv interval (%dms) out of bounds, healing to default", dev->advInterval);
        dev->advInterval = dev->isBound ? ADV_INTERVAL_NORMAL_MS : ADV_INTERVAL_UNBOUND_MS;
        platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    } else if (dev->isBound && dev->advInterval == dev->findModeInterval && dev->advInterval < ADV_INTERVAL_NORMAL_MS) {
        platform_log("[INIT] Adv interval (%dms) corrupted by find mode, healing to %dms",
                     dev->advInterval, ADV_INTERVAL_NORMAL_MS);
        dev->advInterval = ADV_INTERVAL_NORMAL_MS;
        platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    }
    // Self-healing check: findModeInterval 范围异常纠偏
    if (dev->findModeInterval < ADV_INTERVAL_MIN_MS || dev->findModeInterval > ADV_INTERVAL_MAX_MS) {
        platform_log("[INIT] Find interval (%dms) out of bounds, healing to default", dev->findModeInterval);
        dev->findModeInterval = ADV_INTERVAL_FIND_MS;
        platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
    }
    dev->savedAdvInterval = dev->advInterval;

    uint16_t rawPower = platform_storage_load_u16(NVM3_KEY_TX_POWER, 0x7FFF);
    if (rawPower == 0x7FFF) {
        dev->txPower = (int8_t)DEFAULT_BLE_TX_POWER;
    } else {
        dev->txPower = (int8_t)(uint8_t)rawPower;
    }
    if (dev->txPower < HW_TX_POWER_MIN || dev->txPower > HW_TX_POWER_MAX) {
        platform_log("[INIT] TxPower (%d dBm) out of bounds, healing to default", dev->txPower);
        dev->txPower = (int8_t)DEFAULT_BLE_TX_POWER;
        platform_storage_save_u16(NVM3_KEY_TX_POWER, (uint16_t)(uint8_t)dev->txPower);
    }

    uint16_t rawFmPower = platform_storage_load_u16(NVM3_KEY_FINDMY_TX_POWER, 0x7FFF);
    if (rawFmPower == 0x7FFF) {
        dev->findmyTxPower = (int8_t)DEFAULT_FINDMY_TX_POWER;
    } else {
        dev->findmyTxPower = (int8_t)(uint8_t)rawFmPower;
    }
    if (dev->findmyTxPower < HW_TX_POWER_MIN || dev->findmyTxPower > HW_TX_POWER_MAX) {
        platform_log("[INIT] FindMy TxPower (%d dBm) out of bounds, healing to default", dev->findmyTxPower);
        dev->findmyTxPower = (int8_t)DEFAULT_FINDMY_TX_POWER;
        platform_storage_save_u16(NVM3_KEY_FINDMY_TX_POWER, (uint16_t)(uint8_t)dev->findmyTxPower);
    }
    platform_findmy_adv_set_tx_power(dev->findmyTxPower);

    uint16_t rawFmInterval = platform_storage_load_u16(NVM3_KEY_FINDMY_ADV_INTERVAL, 0xFFFF);
    if (rawFmInterval == 0xFFFF || rawFmInterval < FINDMY_ADV_INTERVAL_MIN_MS || rawFmInterval > FINDMY_ADV_INTERVAL_MAX_MS) {
        if (rawFmInterval != 0xFFFF) {
            platform_log("[INIT] FindMy AdvInterval (%dms) out of bounds, healing to default", rawFmInterval);
        }
        dev->findmyAdvInterval = FINDMY_ADV_INTERVAL_DEFAULT_MS;
        platform_storage_save_u16(NVM3_KEY_FINDMY_ADV_INTERVAL, dev->findmyAdvInterval);
    } else {
        dev->findmyAdvInterval = rawFmInterval;
    }
    platform_findmy_adv_set_interval(dev->findmyAdvInterval);

#ifndef IS_SPARKS
    dev->findmyEnabled = platform_storage_load_bool(NVM3_KEY_FINDMY_ENABLED, true);
#else
    uint16_t savedKeyCount = platform_storage_load_u16(NVM3_KEY_FINDMY_KEY_COUNT, 0);
    dev->findmyEnabled = platform_storage_load_bool(NVM3_KEY_FINDMY_ENABLED, (savedKeyCount >= FINDMY_MIN_KEYS));
#endif

    if (dev->isBound) {
        uint8_t keyBuf[AES_KEY_LEN];
        size_t keyLen = platform_storage_load_bytes(NVM3_KEY_AES_KEY, keyBuf, sizeof(keyBuf));
        if (keyLen == AES_KEY_LEN) {
            yuitag_crypto_set_aes_key(keyBuf);
        } else {
            dev->isBound = false;
            dev->advInterval = ADV_INTERVAL_UNBOUND_MS;
            dev->savedAdvInterval = dev->advInterval;
            yuitag_crypto_clear_aes_key();
            platform_storage_save_bool(NVM3_KEY_IS_BOUND, false);
            platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
            platform_log("[INIT] AES key corrupt, reverting to UNBOUND");
        }
    }

    if (!hasConfig) {
        platform_log("[INIT] First boot: saving defaults to storage");
        platform_storage_save_string(NVM3_KEY_DEV_NAME, dev->deviceName);
        platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
        platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
        platform_storage_save_u16(NVM3_KEY_TX_POWER, (uint16_t)(uint8_t)dev->txPower);
        platform_storage_save_u16(NVM3_KEY_FINDMY_TX_POWER, (uint16_t)(uint8_t)dev->findmyTxPower);
        platform_storage_save_u16(NVM3_KEY_FINDMY_ADV_INTERVAL, dev->findmyAdvInterval);
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
    platform_log("[INIT] TxPower:      %d dBm", dev->txPower);
#ifndef IS_SPARKS
    platform_log("[INIT] FindMyTxPower:%d dBm", dev->findmyTxPower);
    platform_log("[INIT] FindMyInterval:%dms", dev->findmyAdvInterval);
#endif
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
        yuitag_beep_factory_reset();
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
        if (dev->ringActive) {
            return false;
        }
        uint8_t oldBatt = dev->batteryLevel;
        dev->batteryLevel = platform_read_battery();
        dev->lastBatteryCheckMs = now;
        platform_log("[BATT] Periodic: %d%%", dev->batteryLevel);
        if (dev->isBound) {
            return (oldBatt != dev->batteryLevel);
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
        dev->pendingParamSave = true;
    }

    if (!dev->isBound) {
#ifndef IS_SPARKS
        dev->otaAuthorized = false;
        if (yuitag_crypto_ecdh_ready()) {
            yuitag_crypto_ecdh_cleanup();
        }
#endif
        if (dev->waitingVerify || yuitag_crypto_has_aes_key()) {
            yuitag_crypto_clear_aes_key();
            dev->waitingVerify = false;
            platform_log("[BIND] Disconnected unverified -> cleared AES key");
        }
    }

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
            dev->otaAuthorized = true;
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

    yuitag_crypto_clear_aes_key();
#ifndef IS_SPARKS
    yuitag_crypto_ecdh_cleanup();
#endif

    platform_storage_clear_all();

    yuitag_findmy_clear_seed();

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
    dev->pendingFindmyChange = true;

    platform_storage_save_string(NVM3_KEY_DEV_NAME, dev->deviceName);
    platform_storage_save_u16(NVM3_KEY_ADV_INTERVAL, dev->advInterval);
    platform_storage_save_u16(NVM3_KEY_FIND_INTERVAL, dev->findModeInterval);
    dev->txPower = (int8_t)DEFAULT_BLE_TX_POWER;
    platform_storage_save_u16(NVM3_KEY_TX_POWER, (uint16_t)(uint8_t)dev->txPower);
    dev->findmyTxPower = (int8_t)DEFAULT_FINDMY_TX_POWER;
    platform_storage_save_u16(NVM3_KEY_FINDMY_TX_POWER, (uint16_t)(uint8_t)dev->findmyTxPower);
    platform_findmy_adv_set_tx_power(dev->findmyTxPower);
    dev->findmyAdvInterval = FINDMY_ADV_INTERVAL_DEFAULT_MS;
    platform_storage_save_u16(NVM3_KEY_FINDMY_ADV_INTERVAL, dev->findmyAdvInterval);
    platform_findmy_adv_set_interval(dev->findmyAdvInterval);
    platform_storage_save_bool(NVM3_KEY_IS_BOUND, false);
    platform_storage_save_bool(NVM3_KEY_FINDMY_ENABLED, false);

#ifndef IS_SPARKS
    dev->otaAuthorized = false;
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
    dev->otaAuthorized = false;
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
    platform_storage_save_u16(NVM3_KEY_TX_POWER, (uint16_t)(uint8_t)dev->txPower);
    platform_storage_save_u16(NVM3_KEY_FINDMY_TX_POWER, (uint16_t)(uint8_t)dev->findmyTxPower);
    platform_storage_save_u16(NVM3_KEY_FINDMY_ADV_INTERVAL, dev->findmyAdvInterval);
    platform_storage_save_bool(NVM3_KEY_FINDMY_ENABLED, dev->findmyEnabled);
    platform_log("[PERSIST] Device params saved to storage (normalAdv=%dms txPower=%d)", normalInterval, dev->txPower);
}

size_t yuitag_device_param_read(YuiTagDevice *dev, uint8_t *buf, size_t maxLen)
{
    size_t nameLen = strlen(dev->deviceName);
    if (nameLen > DEVICE_NAME_MAX_LEN) {
        nameLen = DEVICE_NAME_MAX_LEN;
    }

    size_t total = 1 + nameLen + 10 + 2;
    if (total > maxLen) {
        return 0;
    }

    buf[0] = (uint8_t)nameLen;
    memcpy(buf + 1, dev->deviceName, nameLen);
    buf[1 + nameLen] = dev->advInterval & 0xFF;
    buf[2 + nameLen] = (dev->advInterval >> 8) & 0xFF;
    buf[3 + nameLen] = dev->findModeInterval & 0xFF;
    buf[4 + nameLen] = (dev->findModeInterval >> 8) & 0xFF;

    buf[5 + nameLen] = dev->findmyEnabled ? 1 : 0;
    buf[6 + nameLen] = yuitag_findmy_period_seconds_to_index(yuitag_findmy_get_period());

    buf[7 + nameLen] = (uint8_t)dev->txPower;
    buf[8 + nameLen] = (uint8_t)(int8_t)HW_TX_POWER_MIN;
    buf[9 + nameLen] = (uint8_t)(int8_t)HW_TX_POWER_MAX;
    buf[10 + nameLen] = (uint8_t)dev->findmyTxPower;
    buf[11 + nameLen] = (uint8_t)(dev->findmyAdvInterval & 0xFF);
    buf[12 + nameLen] = (uint8_t)((dev->findmyAdvInterval >> 8) & 0xFF);

    platform_log("[PARAM] Read: name=%s adv=%dms find=%dms findmy=%d period=%d txPower=%d findmyTxPower=%d fmAdvInt=%dms",
                 dev->deviceName, dev->advInterval, dev->findModeInterval,
                 dev->findmyEnabled, buf[6 + nameLen], dev->txPower, dev->findmyTxPower, dev->findmyAdvInterval);
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
    case CMD_TX_POWER_CONFIG: {
        if (len >= 3) {
            int8_t newPower = (int8_t)decBuf[1];
            if (newPower >= HW_TX_POWER_MIN && newPower <= HW_TX_POWER_MAX) {
                dev->txPower = newPower;
                dev->pendingParamSave = true;
                dev->pendingAdvRefresh = true;
                status = STATUS_SUCCESS;
                platform_log("[PARAM] TX power: %d dBm", dev->txPower);
            } else {
                platform_log("[PARAM] TX power rejected (%d dBm out of [%d, %d])",
                             newPower, HW_TX_POWER_MIN, HW_TX_POWER_MAX);
            }
        }
        break;
    }
    case CMD_FINDMY_KEYS_SYNC: {
        status = yuitag_device_handle_keys_sync(dev, decBuf, len);
        break;
    }
    case CMD_FINDMY_ENABLE: {
        if (len >= 3) {
            uint8_t action = decBuf[1];
            if (action == ACTION_ON) {
#ifndef IS_SPARKS
                if (!yuitag_findmy_is_time_synced()) {
                    platform_log("[PARAM] FindMy enable rejected: time not synced");
                    status = STATUS_FAILED;
                    break;
                }
#else
                uint16_t count = platform_storage_load_u16(NVM3_KEY_FINDMY_KEY_COUNT, 0);
                if (count < FINDMY_MIN_KEYS) {
                    platform_log("[PARAM] FindMy enable rejected: no keys configured (%d)", count);
                    status = STATUS_FAILED;
                    break;
                }
#endif
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
            dev->pendingFindmyChange = true;
            status = STATUS_SUCCESS;
        }
        break;
    }
    case CMD_FINDMY_TX_POWER_CONFIG: {
        if (len >= 3) {
            int8_t newPower = (int8_t)decBuf[1];
            if (newPower >= HW_TX_POWER_MIN && newPower <= HW_TX_POWER_MAX) {
                dev->findmyTxPower = newPower;
                dev->pendingParamSave = true;
                platform_findmy_adv_set_tx_power(dev->findmyTxPower);
                status = STATUS_SUCCESS;
                platform_log("[PARAM] FindMy TX power: %d dBm", dev->findmyTxPower);
            } else {
                platform_log("[PARAM] FindMy TX power rejected (%d dBm out of [%d, %d])",
                             newPower, HW_TX_POWER_MIN, HW_TX_POWER_MAX);
            }
        }
        break;
    }
    case CMD_FINDMY_INTERVAL_CONFIG: {
        if (len >= 4) {
            uint16_t newInterval = (uint16_t)decBuf[1] | ((uint16_t)decBuf[2] << 8);
            if (newInterval >= FINDMY_ADV_INTERVAL_MIN_MS && newInterval <= FINDMY_ADV_INTERVAL_MAX_MS) {
                dev->findmyAdvInterval = newInterval;
                dev->pendingParamSave = true;
                platform_findmy_adv_set_interval(dev->findmyAdvInterval);
                status = STATUS_SUCCESS;
                platform_log("[PARAM] FindMy adv interval: %dms", dev->findmyAdvInterval);
            } else {
                platform_log("[PARAM] FindMy adv interval rejected (%dms out of [%d, %d])",
                             newInterval, FINDMY_ADV_INTERVAL_MIN_MS, FINDMY_ADV_INTERVAL_MAX_MS);
            }
        }
        break;
    }
    default:
        status = STATUS_UNKNOWN_CMD;
        break;
    }

    platform_log("[PARAM] cmdId=0x%02X -> status=0x%02X", cmdId, status);
    return status;
}

static uint64_t s_keys_rx_mask = 0;
static uint8_t  s_keys_expected_total = 0;
static uint32_t s_keys_session_last_rx_ms = 0;

bool yuitag_device_is_keys_sync_active(void)
{
    if (s_keys_expected_total > 0) {
        if ((platform_millis() - s_keys_session_last_rx_ms) > 10000UL) {
            s_keys_expected_total = 0;
            s_keys_rx_mask = 0;
            return false;
        }
        return true;
    }
    return false;
}

uint8_t yuitag_device_handle_keys_sync(YuiTagDevice *dev, const uint8_t *decBuf, size_t len)
{
    if (!dev || !decBuf || len < 3) {
        return STATUS_FAILED;
    }

    uint8_t subOp = decBuf[1];

    switch (subOp) {
    case SUB_OP_KEYS_START: {
        if (len < 5) {
            return STATUS_FAILED;
        }
        uint8_t total = decBuf[2];
        if (total < FINDMY_MIN_KEYS || total > FINDMY_MAX_KEYS) {
            platform_log("[KEYS_SYNC] START rejected: total=%d out of [%d, %d]",
                         total, FINDMY_MIN_KEYS, FINDMY_MAX_KEYS);
            return STATUS_FAILED;
        }

        s_keys_expected_total = total;
        s_keys_rx_mask = 0;
        s_keys_session_last_rx_ms = platform_millis();

        platform_log("[KEYS_SYNC] START session: expecting %d keys", total);
        return STATUS_SUCCESS;
    }

    case SUB_OP_KEYS_DATA: {
        if (len < (3 + FINDMY_KEY_LEN + 1)) {
            platform_log("[KEYS_SYNC] DATA rejected: len=%d", len);
            return STATUS_FAILED;
        }
        if (s_keys_expected_total < FINDMY_MIN_KEYS) {
            platform_log("[KEYS_SYNC] DATA rejected: no session");
            return STATUS_FAILED;
        }
        if ((platform_millis() - s_keys_session_last_rx_ms) > 10000UL) {
            platform_log("[KEYS_SYNC] DATA rejected: sliding timeout");
            s_keys_expected_total = 0;
            s_keys_rx_mask = 0;
            return STATUS_FAILED;
        }

        uint8_t index = decBuf[2];
        if (index >= s_keys_expected_total) {
            platform_log("[KEYS_SYNC] DATA rejected: index=%d >= %d",
                         index, s_keys_expected_total);
            return STATUS_FAILED;
        }

        s_keys_session_last_rx_ms = platform_millis();
        platform_storage_save_bytes(NVM3_KEY_FINDMY_KEY_BASE + index, &decBuf[3], FINDMY_KEY_LEN);
        s_keys_rx_mask |= ((uint64_t)1 << index);
        platform_log("[KEYS_SYNC] DATA: slot %d/%d saved to storage", index, s_keys_expected_total);
        return STATUS_SUCCESS;
    }

    case SUB_OP_KEYS_FINISH: {
        if (len < 5) {
            return STATUS_FAILED;
        }
        uint8_t total = decBuf[2];
        if (total != s_keys_expected_total || s_keys_expected_total < FINDMY_MIN_KEYS) {
            platform_log("[KEYS_SYNC] FINISH rejected: total %d != expected %d",
                         total, s_keys_expected_total);
            return STATUS_FAILED;
        }

        uint64_t expectedMask = (total == 64) ? 0xFFFFFFFFFFFFFFFFULL : (((uint64_t)1 << total) - 1);
        if (s_keys_rx_mask != expectedMask) {
            platform_log("[KEYS_SYNC] FINISH rejected: missing packets (mask=0x%llX)",
                         (unsigned long long)s_keys_rx_mask);
            return STATUS_FAILED;
        }

        uint16_t oldCount = platform_storage_load_u16(NVM3_KEY_FINDMY_KEY_COUNT, 0);
        for (uint16_t i = total; i < oldCount && i <= FINDMY_MAX_KEYS; i++) {
            platform_storage_remove(NVM3_KEY_FINDMY_KEY_BASE + i);
        }

        platform_storage_save_u16(NVM3_KEY_FINDMY_KEY_COUNT, (uint16_t)total);
        platform_storage_save_u16(NVM3_KEY_FINDMY_CURRENT_IDX, 0);

        dev->findmyEnabled = true;
        dev->pendingFindmyChange = true;
        dev->pendingParamSave = true;

#ifdef IS_SPARKS
        yuitag_findmy_reload_keys();
#endif

        platform_log("[KEYS_SYNC] FINISH OK: %d keys verified", total);
        s_keys_expected_total = 0;
        s_keys_rx_mask = 0;
        return STATUS_SUCCESS;
    }

    default:
        platform_log("[KEYS_SYNC] Unknown subOp: 0x%02X", subOp);
        return STATUS_FAILED;
    }
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
        if (dev->findmyEnabled) {
            yuitag_findmy_update_battery(dev->batteryLevel);
        }
    }

    if (dev->findmyEnabled) {
        yuitag_findmy_tick();
    }
#ifndef IS_SPARKS
    if (yuitag_findmy_is_time_synced()) {
        yuitag_findmy_persist_time();
    }
#endif

    // 4. pending adv refresh
    if (dev->pendingAdvRefresh) {
        dev->pendingAdvRefresh = false;
        platform_ble_update_advertising(dev, true);
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

    // 9. param save
    if (dev->pendingParamSave) {
        dev->pendingParamSave = false;
        yuitag_device_persist_params(dev);
    }
}

void yuitag_device_handle_cmd_write(YuiTagDevice *dev, const uint8_t *inData, size_t inLen, yuitag_response_t *resp)
{
    if (resp) {
        memset(resp, 0, sizeof(yuitag_response_t));
    }
    if (!dev || !inData || inLen == 0 || !resp) {
        return;
    }

    // 阶段 1: 绑定确认密文验证
    if (dev->waitingVerify && yuitag_crypto_has_aes_key() && !dev->isBound) {
        platform_log("[VERIFY] Received encrypted verify data (%d bytes)", inLen);
        if (yuitag_device_bind_verify(dev, inData, inLen)) {
            resp->channel = YUITAG_NOTIFY_CMD;
            resp->data[0] = BIND_VERIFY_OK;
            resp->len = 1;
            dev->pendingAdvRefresh = true;
            dev->pendingConnParamReset = true;
        } else {
            resp->channel = YUITAG_NOTIFY_CMD;
            resp->data[0] = BIND_VERIFY_FAIL;
            resp->len = 1;
            dev->pendingBindAbort = true;
        }
        return;
    }

    // 未绑定状态拒绝常规控制指令
    if (!dev->isBound) {
        platform_log("[CMD] Rejected: unbound");
        resp->channel = YUITAG_NOTIFY_CMD;
        resp->data[0] = 0x00;
        resp->data[1] = STATUS_FAILED;
        resp->len = 2;
        return;
    }

    // 阶段 2: 常规加密业务指令
    uint8_t cmdId = 0, action = 0;
    uint8_t plain[CMD_DECRYPT_BUF_MAX_LEN];
    uint8_t status = yuitag_device_decrypt_validate(dev, inData, inLen, &cmdId, &action, plain);
    if (status != STATUS_SUCCESS) {
        resp->channel = YUITAG_NOTIFY_CMD;
        resp->data[0] = 0x00;
        resp->data[1] = status;
        resp->len = 2;
        return;
    }

    if (cmdId == CMD_BIND_VERIFY) {
        resp->channel = YUITAG_NOTIFY_NONE;
        resp->len = 0;
        return;
    }

#ifndef IS_SPARKS
    if (cmdId == CMD_FINDMY_TIME_SYNC) {
        uint8_t syncStatus = yuitag_device_findmy_handle_time_sync(plain, inLen);
        resp->channel = YUITAG_NOTIFY_CMD;
        resp->data[0] = 0x00;
        resp->data[1] = syncStatus;
        resp->len = 2;
        return;
    }
#endif

    status = yuitag_device_dispatch_cmd(dev, cmdId, action);

#ifndef IS_SPARKS
    if (cmdId == CMD_FINDMY_STATUS && status == STATUS_SUCCESS) {
        resp->channel = YUITAG_NOTIFY_PARAM;
        resp->data[0] = CMD_FINDMY_STATUS;
        resp->data[1] = yuitag_findmy_is_time_synced() ? 1 : 0;
        uint32_t curTime = yuitag_findmy_get_current_time();
        uint32_t curPeriod = yuitag_findmy_get_current_period();
        resp->data[2] = (uint8_t)(curTime & 0xFF);
        resp->data[3] = (uint8_t)((curTime >> 8) & 0xFF);
        resp->data[4] = (uint8_t)((curTime >> 16) & 0xFF);
        resp->data[5] = (uint8_t)((curTime >> 24) & 0xFF);
        resp->data[6] = (uint8_t)(curPeriod & 0xFF);
        resp->data[7] = (uint8_t)((curPeriod >> 8) & 0xFF);
        resp->data[8] = (uint8_t)((curPeriod >> 16) & 0xFF);
        resp->data[9] = (uint8_t)((curPeriod >> 24) & 0xFF);
        resp->len = 10;
        platform_log("[FINDMY] Status sent: synced=%d time=%lu period=%lu",
                     resp->data[1], curTime, curPeriod);
        return;
    }
#endif

    // 心跳指令无需回包
    if (cmdId == CMD_HEARTBEAT && status == STATUS_NO_REPLY) {
        resp->channel = YUITAG_NOTIFY_NONE;
        resp->len = 0;
        return;
    }

    resp->channel = YUITAG_NOTIFY_CMD;
    resp->data[0] = 0x00;
    resp->data[1] = status;
    resp->len = 2;

    platform_log("[CMD] cmdId=0x%02X action=0x%02X -> status=0x%02X",
                 cmdId, action, status);
}

void yuitag_device_handle_param_write(YuiTagDevice *dev, const uint8_t *inData, size_t inLen, yuitag_response_t *resp)
{
    if (resp) {
        memset(resp, 0, sizeof(yuitag_response_t));
    }
    if (!dev || !inData || inLen == 0 || !resp) {
        return;
    }

    if (!dev->isBound) {
        platform_log("[PARAM] Rejected: unbound");
        resp->channel = YUITAG_NOTIFY_PARAM;
        resp->data[0] = 0x00;
        resp->data[1] = STATUS_FAILED;
        resp->len = 2;
        return;
    }

    uint8_t status = yuitag_device_param_write(dev, inData, inLen);
    resp->channel = YUITAG_NOTIFY_PARAM;
    resp->data[0] = 0x00;
    resp->data[1] = status;
    resp->len = 2;
}

typedef struct {
    uint16_t mv;
    uint8_t  pct;
} batt_curve_point_t;

// 基于松下 CR2032 官方低功耗微安级放电特性的标准放电曲线 (以 1% 精度连续插值)
static const batt_curve_point_t BATT_CURVE[] = {
    { 3000, 100 },
    { 2880,  80 },
    { 2800,  60 },
    { 2720,  40 },
    { 2600,  20 },
    { 2450,  10 },
    { 2000,   0 }
};

#define BATT_CURVE_POINTS (sizeof(BATT_CURVE) / sizeof(BATT_CURVE[0]))

uint8_t yuitag_battery_mv_to_percent(uint32_t battery_mv)
{
    if (battery_mv >= BATT_CURVE[0].mv) {
        return 100;
    }
    if (battery_mv <= BATT_CURVE[BATT_CURVE_POINTS - 1].mv) {
        return 0;
    }

    for (size_t i = 0; i < BATT_CURVE_POINTS - 1; i++) {
        if (battery_mv >= BATT_CURVE[i + 1].mv) {
            uint32_t v_high = BATT_CURVE[i].mv;
            uint32_t v_low  = BATT_CURVE[i + 1].mv;
            uint32_t p_high = BATT_CURVE[i].pct;
            uint32_t p_low  = BATT_CURVE[i + 1].pct;

            uint8_t pct = (uint8_t)(p_low + ((battery_mv - v_low) * (p_high - p_low)) / (v_high - v_low));
            // 只要电压仍高于 2000mV 截止门限，至少保留 1% 电量显示
            if (pct == 0 && battery_mv > BATT_CURVE[BATT_CURVE_POINTS - 1].mv) {
                pct = 1;
            }
            return pct;
        }
    }

    return 0;
}
