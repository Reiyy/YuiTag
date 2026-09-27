// ============================================================
// YuiTag 加密与数据完整性工具层实现 (跨平台核心)
// 所有硬件相关加密操作通过 platform_crypto_* 接口调用。
// ============================================================

#include "yuitag_config.h"
#include "yuitag_crypto.h"
#include "yuitag_platform_api.h"

#include <string.h>
#include <stdio.h>

// ============================================================
// AES 密钥管理
// ============================================================
static uint8_t  s_aesKeyRaw[AES_KEY_LEN];
static bool     s_aesKeyReady = false;

void yuitag_crypto_init(void)
{
    s_aesKeyReady = false;
    memset(s_aesKeyRaw, 0, sizeof(s_aesKeyRaw));
    platform_crypto_init();
}

void yuitag_crypto_deinit(void)
{
    yuitag_crypto_clear_aes_key();
}

// ============================================================
// UID 派生
// ============================================================
void yuitag_crypto_compute_uid(const uint8_t mac[6], uint8_t uidOut[UID_LEN])
{
    uint8_t input[6 + UID_SALT_LEN];
    uint8_t hash[SHA256_HASH_LEN];

    memcpy(input, mac, 6);
    memcpy(input + 6, UID_SALT, UID_SALT_LEN);

    platform_crypto_sha256(input, sizeof(input), hash);

    memcpy(uidOut, hash, UID_LEN);

    memset(input, 0, sizeof(input));
    memset(hash, 0, sizeof(hash));
}

void yuitag_crypto_get_short_uid(const uint8_t uid[UID_LEN], uint8_t shortUid[SHORT_UID_LEN])
{
    shortUid[0] = uid[0];
    shortUid[1] = uid[1];
}

void yuitag_crypto_make_device_name(const uint8_t shortUid[SHORT_UID_LEN],
                                    char *buf, size_t bufSize)
{
    snprintf(buf, bufSize, "%s%02X%02X", DEVICE_NAME_PREFIX, shortUid[0], shortUid[1]);
}

void yuitag_crypto_uid_to_hex_str(const uint8_t uid[UID_LEN], char *buf, size_t bufSize)
{
    size_t pos = 0;
    for (int i = 0; i < UID_LEN && pos + 2 < bufSize; i++) {
        snprintf(buf + pos, 3, "%02X", uid[i]);
        pos += 2;
    }
    if (pos < bufSize) {
        buf[pos] = '\0';
    }
}

void yuitag_crypto_make_system_id(const uint8_t mac[6], uint8_t out[8])
{
    out[0] = mac[5];
    out[1] = mac[4];
    out[2] = mac[3];
    out[3] = 0xFF;
    out[4] = 0xFE;
    out[5] = mac[2];
    out[6] = mac[1];
    out[7] = mac[0];
}

// ============================================================
// CRC-8 (多项式 0x07, 初始值 0x00)
// ============================================================
uint8_t yuitag_crypto_crc8(const uint8_t *data, size_t len)
{
    if (!data || len == 0) {
        return 0x00;
    }
    uint8_t crc = 0x00;
    while (len--) {
        crc ^= *data++;
        for (uint8_t i = 0; i < 8; i++) {
            crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) : (crc << 1);
        }
    }
    return crc;
}

// ============================================================
// AES-128 会话密钥管理
// ============================================================
void yuitag_crypto_set_aes_key(const uint8_t key[AES_KEY_LEN])
{
    memcpy(s_aesKeyRaw, key, AES_KEY_LEN);
    s_aesKeyReady = true;
}

bool yuitag_crypto_has_aes_key(void)
{
    return s_aesKeyReady;
}

void yuitag_crypto_clear_aes_key(void)
{
    s_aesKeyReady = false;
    memset(s_aesKeyRaw, 0, sizeof(s_aesKeyRaw));
}

bool yuitag_crypto_get_aes_key(uint8_t keyOut[AES_KEY_LEN])
{
    if (!s_aesKeyReady) {
        return false;
    }
    memcpy(keyOut, s_aesKeyRaw, AES_KEY_LEN);
    return true;
}

bool yuitag_crypto_aes_ctr(const uint8_t *input, size_t len, uint8_t *output)
{
    if (!s_aesKeyReady || len == 0) {
        return false;
    }
    return platform_crypto_aes128_ctr(s_aesKeyRaw, input, len, output);
}

// ============================================================
// Sparks: 明文随机密钥生成
// ============================================================
#ifdef IS_SPARKS
void yuitag_crypto_generate_aes_key(uint8_t keyOut[AES_KEY_LEN])
{
    platform_crypto_random(keyOut, AES_KEY_LEN);
    yuitag_crypto_set_aes_key(keyOut);
}
#endif

// ============================================================
// Starlight: ECDH 包装 (委托平台实现)
// ============================================================
#ifndef IS_SPARKS
bool yuitag_crypto_ecdh_generate_keypair(void)
{
    return platform_ecdh_generate_keypair();
}

bool yuitag_crypto_ecdh_compute_shared(const uint8_t *peerPubKey, size_t peerKeyLen,
                                        uint8_t aesKeyOut[AES_KEY_LEN])
{
    return platform_ecdh_compute_shared(peerPubKey, peerKeyLen, aesKeyOut);
}

bool yuitag_crypto_ecdh_export_pubkey(uint8_t *outBuf, size_t *outLen)
{
    return platform_ecdh_export_pubkey(outBuf, outLen);
}

void yuitag_crypto_ecdh_cleanup(void)
{
    platform_ecdh_cleanup();
}

bool yuitag_crypto_ecdh_ready(void)
{
    return platform_ecdh_ready();
}
#endif

// ============================================================
// Starlight: FindMy 密钥派生包装
// ============================================================
#ifndef IS_SPARKS
void yuitag_crypto_derive_findmy_seed(const uint8_t aesKey[AES_KEY_LEN],
                                       uint8_t seedOut[FINDMY_SEED_LEN])
{
    platform_findmy_derive_seed(aesKey, seedOut);
}

void yuitag_crypto_derive_findmy_private_key(const uint8_t seed[FINDMY_SEED_LEN],
                                              uint32_t period_index,
                                              uint8_t privateKeyOut[FINDMY_KEY_LEN])
{
    platform_findmy_derive_private_key(seed, period_index, privateKeyOut);
}

bool yuitag_crypto_findmy_compute_public_x(const uint8_t privateKey[FINDMY_KEY_LEN],
                                            uint8_t publicXOut[FINDMY_KEY_LEN])
{
    return platform_findmy_compute_public_x(privateKey, publicXOut);
}
#endif
