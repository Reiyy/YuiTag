// ============================================================
// YuiTag 平台密码学后端实现 - PSA Crypto (Oberon / nRF52832)
// 实现 yuitag_platform_api.h 声明的加密契约接口
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"

#include <psa/crypto.h>
#include <zephyr/random/random.h>

#include <string.h>

static psa_key_id_t s_ecdhKeyId = 0;
static bool         s_ecdhReady = false;
static const uint8_t s_zeroNonce[16] = { 0 };

// ============================================================
// 加密模块生命周期
// ============================================================
void platform_crypto_init(void)
{
    psa_status_t status = psa_crypto_init();
    if (status != PSA_SUCCESS) {
        platform_log("[CRYPTO] psa_crypto_init failed: %d", status);
    }
    s_ecdhReady = false;
    s_ecdhKeyId = 0;
}

// ============================================================
// SHA-256
// ============================================================
bool platform_crypto_sha256(const uint8_t *in, size_t len, uint8_t out[32])
{
    size_t hashLen = 0;
    psa_status_t status = psa_hash_compute(PSA_ALG_SHA_256, in, len, out, 32, &hashLen);
    return (status == PSA_SUCCESS && hashLen == 32);
}

// ============================================================
// AES-128-CTR (零 IV)
// ============================================================
bool platform_crypto_aes128_ctr(const uint8_t key[16], const uint8_t *in, size_t len, uint8_t *out)
{
    if (len == 0) {
        return false;
    }

    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_AES);
    psa_set_key_bits(&attrs, 128);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_ENCRYPT | PSA_KEY_USAGE_DECRYPT);
    psa_set_key_algorithm(&attrs, PSA_ALG_CTR);

    psa_key_id_t key_id = 0;
    psa_status_t status = psa_import_key(&attrs, key, 16, &key_id);
    psa_reset_key_attributes(&attrs);
    if (status != PSA_SUCCESS) {
        return false;
    }

    psa_cipher_operation_t op = PSA_CIPHER_OPERATION_INIT;
    status = psa_cipher_encrypt_setup(&op, key_id, PSA_ALG_CTR);
    if (status != PSA_SUCCESS) {
        psa_destroy_key(key_id);
        return false;
    }

    status = psa_cipher_set_iv(&op, s_zeroNonce, sizeof(s_zeroNonce));
    if (status != PSA_SUCCESS) {
        psa_cipher_abort(&op);
        psa_destroy_key(key_id);
        return false;
    }

    size_t out_len = 0;
    status = psa_cipher_update(&op, in, len, out, len, &out_len);
    if (status != PSA_SUCCESS) {
        psa_cipher_abort(&op);
        psa_destroy_key(key_id);
        return false;
    }

    size_t finish_len = 0;
    status = psa_cipher_finish(&op, out + out_len, len - out_len, &finish_len);
    psa_destroy_key(key_id);
    return (status == PSA_SUCCESS);
}

// ============================================================
// 安全随机数
// ============================================================
void platform_crypto_random(uint8_t *buf, size_t len)
{
    psa_status_t status = psa_generate_random(buf, len);
    if (status != PSA_SUCCESS) {
        sys_csrand_get(buf, len);
    }
}

// ============================================================
// ECDH 密钥协商 (secp256r1)
// ============================================================
bool platform_ecdh_generate_keypair(void)
{
    if (s_ecdhReady && s_ecdhKeyId != 0) {
        psa_destroy_key(s_ecdhKeyId);
        s_ecdhKeyId = 0;
        s_ecdhReady = false;
    }

    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attrs, 256);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_DERIVE | PSA_KEY_USAGE_EXPORT);
    psa_set_key_algorithm(&attrs, PSA_ALG_ECDH);

    psa_status_t status = psa_generate_key(&attrs, &s_ecdhKeyId);
    psa_reset_key_attributes(&attrs);

    s_ecdhReady = (status == PSA_SUCCESS);
    return s_ecdhReady;
}

bool platform_ecdh_compute_shared(const uint8_t *peerPub, size_t len, uint8_t aesKeyOut[16])
{
    if (!s_ecdhReady) {
        return false;
    }

    uint8_t sharedSecret[ECDH_SHARED_SECRET_LEN];
    size_t secretLen = 0;

    psa_status_t status = psa_raw_key_agreement(PSA_ALG_ECDH,
                                                s_ecdhKeyId,
                                                peerPub, len,
                                                sharedSecret, sizeof(sharedSecret),
                                                &secretLen);
    if (status != PSA_SUCCESS) {
        memset(sharedSecret, 0, sizeof(sharedSecret));
        return false;
    }

    uint8_t kdfHash[SHA256_HASH_LEN];
    size_t hashLen = 0;
    psa_hash_compute(PSA_ALG_SHA_256, sharedSecret, secretLen,
                     kdfHash, sizeof(kdfHash), &hashLen);

    memcpy(aesKeyOut, kdfHash, 16);

    memset(sharedSecret, 0, sizeof(sharedSecret));
    memset(kdfHash, 0, sizeof(kdfHash));

    return true;
}

bool platform_ecdh_export_pubkey(uint8_t *out, size_t *outLen)
{
    if (!s_ecdhReady) {
        return false;
    }

    size_t exportedLen = 0;
    psa_status_t status = psa_export_public_key(s_ecdhKeyId,
                                                out, ECDH_PUBKEY_LEN,
                                                &exportedLen);
    if (status == PSA_SUCCESS) {
        *outLen = exportedLen;
        return true;
    }
    return false;
}

void platform_ecdh_cleanup(void)
{
    if (s_ecdhReady && s_ecdhKeyId != 0) {
        psa_destroy_key(s_ecdhKeyId);
        s_ecdhKeyId = 0;
    }
    s_ecdhReady = false;
}

bool platform_ecdh_ready(void)
{
    return s_ecdhReady;
}

// ============================================================
// Apple Find My ECC 密钥派生与公钥计算
// ============================================================
void platform_findmy_derive_seed(const uint8_t aesKey[16], uint8_t seedOut[32])
{
    uint8_t hash[SHA256_HASH_LEN];
    size_t hashLen = 0;
    psa_hash_compute(PSA_ALG_SHA_256, aesKey, 16, hash, sizeof(hash), &hashLen);
    memcpy(seedOut, hash, FINDMY_SEED_LEN);
    memset(hash, 0, sizeof(hash));
}

void platform_findmy_derive_private_key(const uint8_t seed[32],
                                         uint32_t period,
                                         uint8_t out[28])
{
    uint8_t input[FINDMY_SEED_LEN + 4 + FINDMY_DERIVE_INFO_LEN];
    uint8_t hash[SHA256_HASH_LEN];
    size_t hashLen = 0;

    memcpy(input, seed, FINDMY_SEED_LEN);
    input[FINDMY_SEED_LEN]     = (uint8_t)(period & 0xFF);
    input[FINDMY_SEED_LEN + 1] = (uint8_t)((period >> 8) & 0xFF);
    input[FINDMY_SEED_LEN + 2] = (uint8_t)((period >> 16) & 0xFF);
    input[FINDMY_SEED_LEN + 3] = (uint8_t)((period >> 24) & 0xFF);
    memcpy(input + FINDMY_SEED_LEN + 4, FINDMY_DERIVE_INFO, FINDMY_DERIVE_INFO_LEN);

    psa_hash_compute(PSA_ALG_SHA_256, input, sizeof(input), hash, sizeof(hash), &hashLen);
    memcpy(out, hash, FINDMY_KEY_LEN);

    memset(input, 0, sizeof(input));
    memset(hash, 0, sizeof(hash));
}

// secp224r1 曲线群阶 N (大端序, 28 字节)
static const uint8_t s_secp224r1_n[FINDMY_KEY_LEN] = {
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x16, 0xA2,
    0xE0, 0xB8, 0xF0, 0x3E, 0x13, 0xDD, 0x29, 0x45,
    0x5C, 0x5C, 0x2A, 0x3D
};

static void secp224r1_reduce_mod_n(const uint8_t in[FINDMY_KEY_LEN],
                                   uint8_t out[FINDMY_KEY_LEN])
{
    if (memcmp(in, s_secp224r1_n, FINDMY_KEY_LEN) >= 0) {
        int borrow = 0;
        for (int i = FINDMY_KEY_LEN - 1; i >= 0; i--) {
            int sub = (int)in[i] - (int)s_secp224r1_n[i] - borrow;
            if (sub < 0) {
                sub += 256;
                borrow = 1;
            } else {
                borrow = 0;
            }
            out[i] = (uint8_t)sub;
        }
    } else {
        memcpy(out, in, FINDMY_KEY_LEN);
    }
}

bool platform_findmy_compute_public_x(const uint8_t priv[28], uint8_t pubXOut[28])
{
    uint8_t reduced[FINDMY_KEY_LEN];
    secp224r1_reduce_mod_n(priv, reduced);

    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attrs, 224);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_EXPORT);
    psa_set_key_algorithm(&attrs, PSA_ALG_ECDH);

    psa_key_id_t key_id = 0;
    psa_status_t status = psa_import_key(&attrs, reduced, FINDMY_KEY_LEN, &key_id);
    psa_reset_key_attributes(&attrs);

    if (status != PSA_SUCCESS) {
        return false;
    }

    uint8_t pubkey[1 + 2 * FINDMY_KEY_LEN];
    size_t pubkey_len = 0;
    status = psa_export_public_key(key_id, pubkey, sizeof(pubkey), &pubkey_len);
    psa_destroy_key(key_id);

    if (status != PSA_SUCCESS || pubkey_len != sizeof(pubkey)) {
        return false;
    }

    memcpy(pubXOut, pubkey + 1, FINDMY_KEY_LEN);
    return true;
}
