#ifndef YUITAG_PLATFORM_H
#define YUITAG_PLATFORM_H

// ============================================================
// YuiTag 平台驱动层接口 - Nordic nRF52810 / Zephyr
// 遵循 YuiTagCore 的 yuitag_platform_api.h 平台契约
// ============================================================

#include "yuitag_config.h"
#include "yuitag_platform_api.h"

void platform_init(void);

#endif // YUITAG_PLATFORM_H
