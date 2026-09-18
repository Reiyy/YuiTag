#ifndef SHA256_H
#define SHA256_H

#include <stdint.h>
#include <stddef.h>

#define SHA256_HASH_LEN 32

void sha256(const uint8_t *data, size_t len, uint8_t hash[SHA256_HASH_LEN]);

#endif
