// ============================================================
// YuiTag 平台密码学后端实现 (PSA Crypto + mbedTLS / EFR32BG22)
// 实现 yuitag_platform_api.h 声明的加密契约接口
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"

#include "psa/crypto.h"
#include "mbedtls/platform_util.h"
#include "mbedtls/ecp.h"

#include <string.h>

static mbedtls_svc_key_id_t s_ecdhKeyId = 0;
static bool                 s_ecdhReady = false;
static const uint8_t        s_zeroNonce[16] = { 0 };

// ============================================================
// 初始化
// ============================================================
void platform_crypto_init(void)
{
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
// 随机数生成
// ============================================================
void platform_crypto_random(uint8_t *buf, size_t len)
{
    psa_generate_random(buf, len);
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
        mbedtls_platform_zeroize(sharedSecret, sizeof(sharedSecret));
        return false;
    }

    uint8_t kdfHash[SHA256_HASH_LEN];
    size_t hashLen = 0;
    psa_hash_compute(PSA_ALG_SHA_256, sharedSecret, secretLen,
                     kdfHash, sizeof(kdfHash), &hashLen);

    memcpy(aesKeyOut, kdfHash, 16);

    mbedtls_platform_zeroize(sharedSecret, sizeof(sharedSecret));
    mbedtls_platform_zeroize(kdfHash, sizeof(kdfHash));

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
// Apple Find My 密钥派生与公钥计算
// ============================================================
void platform_findmy_derive_seed(const uint8_t aesKey[16], uint8_t seedOut[32])
{
    uint8_t hash[SHA256_HASH_LEN];
    size_t hashLen = 0;

    psa_hash_compute(PSA_ALG_SHA_256, aesKey, 16, hash, sizeof(hash), &hashLen);
    memcpy(seedOut, hash, FINDMY_SEED_LEN);
    mbedtls_platform_zeroize(hash, sizeof(hash));
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

    mbedtls_platform_zeroize(input, sizeof(input));
    mbedtls_platform_zeroize(hash, sizeof(hash));
}

bool platform_findmy_compute_public_x(const uint8_t priv[28], uint8_t pubXOut[28])
{
    mbedtls_ecp_group grp;
    mbedtls_mpi d;
    int ret;

    mbedtls_ecp_group_init(&grp);
    mbedtls_mpi_init(&d);

    ret = mbedtls_ecp_group_load(&grp, MBEDTLS_ECP_DP_SECP224R1);
    if (ret != 0) {
        mbedtls_ecp_group_free(&grp);
        mbedtls_mpi_free(&d);
        return false;
    }

    ret = mbedtls_mpi_read_binary(&d, priv, FINDMY_KEY_LEN);
    if (ret != 0) {
        mbedtls_ecp_group_free(&grp);
        mbedtls_mpi_free(&d);
        return false;
    }

    ret = mbedtls_mpi_mod_mpi(&d, &d, &grp.N);
    mbedtls_ecp_group_free(&grp);
    if (ret != 0) {
        mbedtls_mpi_free(&d);
        return false;
    }

    uint8_t reduced_key[FINDMY_KEY_LEN];
    ret = mbedtls_mpi_write_binary(&d, reduced_key, FINDMY_KEY_LEN);
    mbedtls_mpi_free(&d);
    if (ret != 0) {
        return false;
    }

    psa_key_attributes_t attrs = PSA_KEY_ATTRIBUTES_INIT;
    psa_set_key_type(&attrs, PSA_KEY_TYPE_ECC_KEY_PAIR(PSA_ECC_FAMILY_SECP_R1));
    psa_set_key_bits(&attrs, 224);
    psa_set_key_usage_flags(&attrs, PSA_KEY_USAGE_EXPORT);
    psa_set_key_algorithm(&attrs, PSA_ALG_ECDH);

    psa_key_id_t key_id = 0;
    psa_status_t status = psa_import_key(&attrs, reduced_key, FINDMY_KEY_LEN, &key_id);
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
