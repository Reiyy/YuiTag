#ifndef YUITAG_PLATFORM_H
#define YUITAG_PLATFORM_H

/**
 * @file yuitag_platform.h
 * @brief YuiTag 硬件抽象驱动层接口 (Silicon Labs EFR32BG22)
 * @note 遵循 YuiTagCore 的 yuitag_platform_api.h 平台契约
 */

#include "yuitag_config.h"
#include "yuitag_platform_api.h"

void platform_init(void);

#endif // YUITAG_PLATFORM_H
