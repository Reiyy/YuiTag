#ifndef YUITAG_CRYPTO_H
#define YUITAG_CRYPTO_H

// ============================================================
// YuiTag 加密与数据完整性校验接口 (跨平台核心)
// 实际加密后端由各平台的 crypto_platform.c 通过
// platform_crypto_* 函数提供。
// ============================================================

#include "yuitag_config_common.h"
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// ---- 模块生命周期 ----
void yuitag_crypto_init(void);
void yuitag_crypto_deinit(void);

// ---- UID 生成与设备标识 ----
void yuitag_crypto_compute_uid(const uint8_t mac[6], uint8_t uidOut[UID_LEN]);
void yuitag_crypto_get_short_uid(const uint8_t uid[UID_LEN], uint8_t shortUid[SHORT_UID_LEN]);
void yuitag_crypto_make_device_name(const uint8_t shortUid[SHORT_UID_LEN], char *buf, size_t bufSize);
void yuitag_crypto_uid_to_hex_str(const uint8_t uid[UID_LEN], char *buf, size_t bufSize);
// 由 MAC 构造 8 字节 Bluetooth System ID (EUI-64: 前3字节MAC逆序 + 0xFF,0xFE + 后3字节MAC逆序)
void yuitag_crypto_make_system_id(const uint8_t mac[6], uint8_t out[8]);

// ---- CRC-8 校验 (多项式 0x07) ----
uint8_t yuitag_crypto_crc8(const uint8_t *data, size_t len);

// ---- AES-128-CTR 会话密钥管理与加解密 ----
void yuitag_crypto_set_aes_key(const uint8_t key[AES_KEY_LEN]);
bool yuitag_crypto_has_aes_key(void);
void yuitag_crypto_clear_aes_key(void);
bool yuitag_crypto_get_aes_key(uint8_t keyOut[AES_KEY_LEN]);
bool yuitag_crypto_aes_ctr(const uint8_t *input, size_t len, uint8_t *output);

// ---- 随机密钥生成 (Sparks: 明文下发) ----
#ifdef IS_SPARKS
void yuitag_crypto_generate_aes_key(uint8_t keyOut[AES_KEY_LEN]);
#endif

// ---- ECDH 密钥协商包装 (仅 Starlight) ----
#ifndef IS_SPARKS
bool yuitag_crypto_ecdh_generate_keypair(void);
bool yuitag_crypto_ecdh_compute_shared(const uint8_t *peerPubKey, size_t peerKeyLen,
                                        uint8_t aesKeyOut[AES_KEY_LEN]);
bool yuitag_crypto_ecdh_export_pubkey(uint8_t *outBuf, size_t *outLen);
void yuitag_crypto_ecdh_cleanup(void);
bool yuitag_crypto_ecdh_ready(void);
#endif

// ---- FindMy 密钥派生包装 (仅 Starlight) ----
#ifndef IS_SPARKS
void yuitag_crypto_derive_findmy_seed(const uint8_t aesKey[AES_KEY_LEN],
                                       uint8_t seedOut[FINDMY_SEED_LEN]);
void yuitag_crypto_derive_findmy_private_key(const uint8_t seed[FINDMY_SEED_LEN],
                                              uint32_t period_index,
                                              uint8_t privateKeyOut[FINDMY_KEY_LEN]);
bool yuitag_crypto_findmy_compute_public_x(const uint8_t privateKey[FINDMY_KEY_LEN],
                                            uint8_t publicXOut[FINDMY_KEY_LEN]);
#endif


#endif // YUITAG_CRYPTO_H
