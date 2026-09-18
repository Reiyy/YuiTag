#ifndef YUITAG_CORE_H
#define YUITAG_CORE_H

// ============================================================
// YuiTagCore — 跨平台核心模块总入口
// 包含此头文件即可访问所有核心接口。
// ============================================================

#include "yuitag_config_common.h"
#include "yuitag_platform_api.h"
#include "yuitag_buzzer_types.h"
#include "yuitag_crypto.h"
#include "yuitag_device.h"

#ifndef IS_SPARKS
#include "yuitag_findmy.h"
#endif

#endif // YUITAG_CORE_H
