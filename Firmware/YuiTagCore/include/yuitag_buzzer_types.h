#ifndef YUITAG_BUZZER_TYPES_H
#define YUITAG_BUZZER_TYPES_H

// ============================================================
// YuiTag 蜂鸣器通用类型定义 (平台无关)
// ============================================================

#include <stdint.h>
#include <stdbool.h>

// 单个音符结构体 (频率与持续时长)
typedef struct {
    uint16_t freq;         // 频率 (Hz), 为 0 表示休止符
    uint16_t duration_ms;  // 持续时长 (ms)
} buzzer_note_t;

// 常用音符构造宏
#define BUZZER_NOTE_TONE(freq_hz, ms) { (freq_hz), (ms) }
#define BUZZER_NOTE_BEEP(ms)          { 4000, ms }          // 4kHz 蜂鸣音
#define BUZZER_NOTE_REST(ms)          { 0,    ms }          // 休止符

// 乐谱播放完成回调函数指针
typedef void (*buzzer_melody_done_cb)(bool finished);

// ---- 蜂鸣器驱动接口 (由各平台实现) ----
void buzzer_init(void);
void buzzer_on(void);
void buzzer_off(void);
bool buzzer_toggle(void);
bool buzzer_is_on(void);
void buzzer_set_volume(uint8_t volume_pct);
void buzzer_play_melody(const buzzer_note_t *notes, uint16_t count,
                        bool repeat, buzzer_melody_done_cb cb);
void buzzer_stop_melody(void);
bool buzzer_melody_is_playing(void);

#endif // YUITAG_BUZZER_TYPES_H
