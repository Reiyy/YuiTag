#ifndef TINY_AES_H
#define TINY_AES_H

#include <stdint.h>
#include <stddef.h>

#define AES128_KEY_LEN 16
#define AES_BLOCK_LEN  16

void aes128_set_key(const uint8_t key[AES128_KEY_LEN]);
void aes128_encrypt_block(const uint8_t in[AES_BLOCK_LEN], uint8_t out[AES_BLOCK_LEN]);
void aes128_ctr_xcrypt(const uint8_t *input, uint8_t *output, size_t len);

#endif
