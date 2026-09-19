// ============================================================
// YuiTag 系统提示音实现 (跨平台核心)
// 乐谱在此唯一定义, 各平台通过 buzzer_play_melody 驱动播放。
// ============================================================

#include "yuitag_config.h"
#include "yuitag_buzzer_types.h"

// 开机双短滴提示音
static const buzzer_note_t s_startup_melody[] = {
    BUZZER_NOTE_BEEP(100),
    BUZZER_NOTE_REST(100),
    BUZZER_NOTE_BEEP(100),
};
#define STARTUP_MELODY_LEN (sizeof(s_startup_melody) / sizeof(s_startup_melody[0]))

// 恢复出厂设置连击提示音
static const buzzer_note_t s_factory_reset_melody[] = {
    BUZZER_NOTE_BEEP(100),
    BUZZER_NOTE_REST(80),
    BUZZER_NOTE_TONE(2000, 100),
    BUZZER_NOTE_REST(80),
    BUZZER_NOTE_BEEP(100),
};
#define FACTORY_RESET_MELODY_LEN (sizeof(s_factory_reset_melody) / sizeof(s_factory_reset_melody[0]))

void yuitag_beep_startup(void)
{
    buzzer_play_melody(s_startup_melody, STARTUP_MELODY_LEN, false, NULL);
}

void yuitag_beep_factory_reset(void)
{
    buzzer_play_melody(s_factory_reset_melody, FACTORY_RESET_MELODY_LEN, false, NULL);
}
