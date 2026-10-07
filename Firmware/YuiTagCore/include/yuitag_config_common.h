#ifndef YUITAG_CONFIG_COMMON_H
#define YUITAG_CONFIG_COMMON_H

// This file contains ONLY platform-independent protocol constants.
// Platform-specific values (FW_VERSION, HW_VERSION, MODEL_NUMBER, DEVICE_NAME_PREFIX)
// must be defined in each platform's yuitag_config.h BEFORE including this file.
//
// Compile switch: IS_SPARKS
//   Defined    -> Sparks (no FindMy, no ECDH, no OTA)
//   Undefined  -> Starlight (FindMy + ECDH + OTA)

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// ---- Compile-time log switch ----
#ifndef YUITAG_LOG_ENABLE
#define YUITAG_LOG_ENABLE 0
#endif

// ---- BLE Company ID ----
#define YUITAG_COMPANY_ID           0x7722

// ---- Advertising intervals (ms) ----
#define ADV_INTERVAL_NORMAL_MS      1500
#define ADV_INTERVAL_FIND_MS        500
#define ADV_INTERVAL_UNBOUND_MS     1000
#define ADV_INTERVAL_MIN_MS         100
#define ADV_INTERVAL_MAX_MS         10000

// ---- Timeouts and polling ----
#define HEARTBEAT_TIMEOUT_MS        120000
#define BATTERY_CHECK_INTERVAL_MS   3600000UL   // 1 hour
#define PERIODIC_CHECK_INTERVAL_MS  30000

// ---- Connection parameters (Apple Accessory Design Guidelines) ----
#define CONN_PARAM_DELAY_MS                 15000
#define CONN_PARAM_INTERVAL_MIN_UNITS       48   // 60 ms (48 * 1.25ms)
#define CONN_PARAM_INTERVAL_MAX_UNITS       72   // 90 ms (72 * 1.25ms)
#define CONN_PARAM_LATENCY                  0
#define CONN_PARAM_TIMEOUT_UNITS            400  // 4000 ms (400 * 10ms)
#define CONN_PARAM_FAST_INTERVAL_MIN_UNITS  12   // 15 ms (12 * 1.25ms)
#define CONN_PARAM_FAST_INTERVAL_MAX_UNITS  24   // 30 ms (24 * 1.25ms)

// ---- Custom GATT service/characteristic UUID short codes ----
// Full 128-bit base: 722Axxxx-8a74-432f-9055-7ed8a5584a11
#define YUITAG_SVC_CORE             0xAA00
#define YUITAG_CHAR_BIND            0xAA42
#define YUITAG_CHAR_CMD             0xAA43
#define YUITAG_CHAR_PARAM           0xAA46

// ---- Standard BLE service UUIDs ----
#define UUID16_GAS                  0x1800
#define UUID16_DIS                  0x180A
#define UUID16_BATT                 0x180F

// ---- Standard characteristic UUIDs ----
#define UUID16_CHAR_DEVICE_NAME     0x2A00
#define UUID16_CHAR_MODEL           0x2A24
#define UUID16_CHAR_SERIAL          0x2A25
#define UUID16_CHAR_FW_REV          0x2A26
#define UUID16_CHAR_HW_REV          0x2A27
#define UUID16_CHAR_SW_REV          0x2A28
#define UUID16_CHAR_MFR             0x2A29

// ---- YuiTagCore Module Version ----
#define YUITAG_CORE_VERSION         "1.0.0"

// ---- Command IDs ----
#define CMD_HEARTBEAT               0x06
#define CMD_RING                    0x07
#define CMD_FIND_MODE               0x4F
#define CMD_BATTERY                 0x50
#define CMD_BIND_VERIFY             0x56
#define CMD_UNBIND                  0x7F

// ---- Parameter command IDs ----
#define CMD_NAME_WRITE              0x4E
#define CMD_INTERVAL_CONFIG         0x54
#define CMD_FIND_INTERVAL_CONFIG    0x46
#define CMD_TX_POWER_CONFIG         0x58

#define CMD_FINDMY_ENABLE           0x60
#define CMD_FINDMY_TIME_SYNC        0x62
#define CMD_FINDMY_STATUS           0x63
#define CMD_FINDMY_PERIOD_CONFIG    0x64
#define CMD_FINDMY_TX_POWER_CONFIG  0x65
#define CMD_FINDMY_KEYS_SYNC        0x66
#define CMD_OTA_AUTHORIZE           0x6F

// ---- FindMy Keys Sync sub-opcodes & bounds (0x66) ----
#define SUB_OP_KEYS_START           0x01
#define SUB_OP_KEYS_DATA            0x02
#define SUB_OP_KEYS_FINISH          0x03
#define FINDMY_MIN_KEYS             5
#define FINDMY_MAX_KEYS             50
#define FINDMY_DEFAULT_KEYS         20

// ---- Action values ----
#define ACTION_OFF                  0x00
#define ACTION_ON                   0x01
#define ACTION_CONFIRM              0xFF

// ---- Status codes ----
#define STATUS_NO_REPLY             0x00
#define STATUS_SUCCESS              0x0A
#define STATUS_FAILED               0x0B
#define STATUS_AES_DECRYPT_ERROR    0x0C
#define STATUS_CRC_ERROR            0x0D
#define STATUS_UNKNOWN_CMD          0xFF

// ---- Bind verify status ----
#define BIND_VERIFY_OK              0x01
#define BIND_VERIFY_FAIL            0x00

// ---- Frame format ----
#define FRAME_OVERHEAD              1
#define FRAME_MIN_LEN               3
#define CMD_DECRYPT_BUF_MAX_LEN     32
#define PARAM_DECRYPT_BUF_MAX_LEN   40

// ---- Default regular BLE TX power (dBm) and hardware boundaries ----
#ifndef DEFAULT_BLE_TX_POWER
#define DEFAULT_BLE_TX_POWER        0
#endif
#ifndef DEFAULT_FINDMY_TX_POWER
#define DEFAULT_FINDMY_TX_POWER     HW_TX_POWER_MAX
#endif
#ifndef HW_TX_POWER_MIN
#define HW_TX_POWER_MIN             (-40)
#endif
#ifndef HW_TX_POWER_MAX
#define HW_TX_POWER_MAX             6
#endif

// ---- NVM3 storage keys (uint32_t) ----
#define NVM3_KEY_IS_BOUND           0x5001U
#define NVM3_KEY_AES_KEY            0x5002U
#define NVM3_KEY_DEV_NAME           0x5003U
#define NVM3_KEY_ADV_INTERVAL       0x5004U
#define NVM3_KEY_FIND_INTERVAL      0x5005U
#define NVM3_KEY_PWR_CYCLE_CNT      0x5006U
#define NVM3_KEY_FINDMY_ENABLED     0x5007U
#define NVM3_KEY_FINDMY_SEED        0x5008U
#define NVM3_KEY_FINDMY_BASE_TIME   0x5009U
#define NVM3_KEY_FINDMY_BASE_TICKS  0x500AU
#define NVM3_KEY_FINDMY_TIME_SYNCED 0x500BU
#define NVM3_KEY_FINDMY_PERIOD_SEC  0x500CU
#define NVM3_KEY_TX_POWER           0x500DU
#define NVM3_KEY_FINDMY_TX_POWER    0x500EU
#define NVM3_KEY_FINDMY_KEY_COUNT   0x5010U
#define NVM3_KEY_FINDMY_CURRENT_IDX 0x5011U
#define NVM3_KEY_FINDMY_KEYS_BLOB   0x5012U
#define NVM3_KEY_FINDMY_KEY_BASE    0x5020U

// ---- Power cycle reset ----
#define POWER_CYCLE_RESET_COUNT     5
#define POWER_CYCLE_WINDOW_MS       7000

// ---- Identity and crypto ----
#define UID_SALT                    "0d000721"
#define UID_SALT_LEN                8
#define UID_LEN                     6
#define SHORT_UID_LEN               2
#define SERIAL_LEN                  12
#define SHA256_HASH_LEN             32
#define AES_KEY_LEN                 16
#define DEVICE_NAME_MAX_LEN         24

// ---- FindMy common constants ----
#define FINDMY_KEY_LEN              28
#define FINDMY_DEFAULT_PERIOD_SEC   3600
#define FINDMY_PERIOD_MIN_SEC       900
#define FINDMY_PERIOD_MAX_SEC       7200

// ---- Battery voltage thresholds (mV) ----
#define BATTERY_FULL_MV             3000
#define BATTERY_EMPTY_MV            2000

// ---- Starlight-only: ECDH parameters ----
#ifndef IS_SPARKS
#define ECDH_PUBKEY_LEN             65
#define ECDH_SHARED_SECRET_LEN      32
#endif

// ---- Starlight-only: FindMy dynamic derive parameters ----
#ifndef IS_SPARKS
#define FINDMY_SEED_LEN             32
#define FINDMY_DERIVE_INFO          "ciallo"
#define FINDMY_DERIVE_INFO_LEN      6
#ifndef FINDMY_TIME_PERSIST_INTERVAL_SEC
#define FINDMY_TIME_PERSIST_INTERVAL_SEC 300
#endif
#ifndef FINDMY_MIN_VALID_UNIX_TIME
#define FINDMY_MIN_VALID_UNIX_TIME  1767225600UL
#endif
#define UUID16_DEBUG_FINDMY_KEY     0xFFF0
#endif // !IS_SPARKS

#endif // YUITAG_CONFIG_COMMON_H
