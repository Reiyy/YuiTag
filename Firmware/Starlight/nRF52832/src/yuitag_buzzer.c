// ============================================================
// YuiTag 蜂鸣器驱动实现 - nRF52832 / Zephyr PWM
// 采用双路互补 PWM 差分驱动压电陶瓷蜂鸣器
// 通道 0 正相，通道 1 反相 (相位差 180 度)
// ============================================================

#include "yuitag_buzzer.h"

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/pwm.h>
#if defined(CONFIG_PM_DEVICE)
#include <zephyr/pm/device.h>
#endif

#define BUZZER_NODE      DT_NODELABEL(pwm1)
#define BUZZER_DEV       DEVICE_DT_GET(BUZZER_NODE)
#define BUZZER_CH_P      0
#define BUZZER_CH_N      1

#define BUZZER_DEFAULT_FREQ     4000U
#define BUZZER_DEFAULT_VOLUME   100U
// nRF52832 PWM 时钟频率 (HFCLK 16MHz)
#define BUZZER_TIMER_CLOCK_HZ   16000000U

static bool s_buzzer_on;
static uint32_t s_freq = BUZZER_DEFAULT_FREQ;
static uint8_t  s_volume_pct = BUZZER_DEFAULT_VOLUME;

static const buzzer_note_t *s_melody_notes;
static uint16_t             s_melody_count;
static uint16_t             s_melody_index;
static bool                 s_melody_repeat;
static bool                 s_melody_playing;
static buzzer_melody_done_cb s_melody_done_cb;

static struct k_work_delayable s_melody_work;

static void melody_step(struct k_work *work);
static void play_current_note(void);
static void timer_apply(void);

static void timer_apply(void)
{
    if (!device_is_ready(BUZZER_DEV)) {
        return;
    }

#if defined(CONFIG_PM_DEVICE)
    pm_device_action_run(BUZZER_DEV, PM_DEVICE_ACTION_RESUME);
#endif

    // 计算周期计数值 = 时钟频率 / 目标频率
    uint32_t period = BUZZER_TIMER_CLOCK_HZ / s_freq;
    if (period < 2U) {
        period = 2U;
    }

    uint8_t vol = s_volume_pct;
    if (vol > 100U) {
        vol = 100U;
    }
    // 满音量对应 50% 占空比 (获得最大差分驱动幅值)
    uint32_t pulse = (period * vol) / 200U;
    if (pulse < 1U) {
        pulse = 1U;
    }
    if (pulse >= period) {
        pulse = period - 1U;
    }

    pwm_set_cycles(BUZZER_DEV, BUZZER_CH_P, period, pulse, PWM_POLARITY_NORMAL);
    pwm_set_cycles(BUZZER_DEV, BUZZER_CH_N, period, pulse, PWM_POLARITY_INVERTED);
}

void buzzer_init(void)
{
    s_buzzer_on    = false;
    s_volume_pct   = BUZZER_DEFAULT_VOLUME;
    s_freq         = BUZZER_DEFAULT_FREQ;
    s_melody_playing = false;

    if (device_is_ready(BUZZER_DEV)) {
        pwm_set_cycles(BUZZER_DEV, BUZZER_CH_P, 0, 0, PWM_POLARITY_NORMAL);
        pwm_set_cycles(BUZZER_DEV, BUZZER_CH_N, 0, 0, PWM_POLARITY_NORMAL);
#if defined(CONFIG_PM_DEVICE)
        pm_device_action_run(BUZZER_DEV, PM_DEVICE_ACTION_SUSPEND);
#endif
    }

    k_work_init_delayable(&s_melody_work, melody_step);
}

void buzzer_on(void)
{
    if (s_buzzer_on) {
        return;
    }
    timer_apply();
    s_buzzer_on = true;
}

void buzzer_off(void)
{
    if (!s_buzzer_on) {
        return;
    }
    if (device_is_ready(BUZZER_DEV)) {
        // 关闭 PWM 输出: 两通道占空比清零并恢复 NORMAL 极性，
        // 保证 OUT0 (P0.13) 与 OUT1 (P0.14) 均拉低至 0V，避免静态直流漏电。
        pwm_set_cycles(BUZZER_DEV, BUZZER_CH_P, 0, 0, PWM_POLARITY_NORMAL);
        pwm_set_cycles(BUZZER_DEV, BUZZER_CH_N, 0, 0, PWM_POLARITY_NORMAL);

#if defined(CONFIG_PM_DEVICE)
        pm_device_action_run(BUZZER_DEV, PM_DEVICE_ACTION_SUSPEND);
#endif
    }
    s_buzzer_on = false;
}

bool buzzer_toggle(void)
{
    if (s_buzzer_on) {
        buzzer_off();
    } else {
        buzzer_on();
    }
    return s_buzzer_on;
}

bool buzzer_is_on(void)
{
    return s_buzzer_on;
}

void buzzer_set_volume(uint8_t volume_pct)
{
    if (volume_pct > 100U) {
        volume_pct = 100U;
    }
    s_volume_pct = volume_pct;
    if (s_buzzer_on) {
        timer_apply();
    }
}

static void play_current_note(void)
{
    if (s_melody_index >= s_melody_count) {
        return;
    }
    const buzzer_note_t *note = &s_melody_notes[s_melody_index];

    if (note->freq == 0 || note->duration_ms == 0) {
        buzzer_off();
    } else {
        if (note->freq != s_freq) {
            s_freq = note->freq;
            timer_apply();
        }
        if (!s_buzzer_on) {
            buzzer_on();
        }
    }

    if (note->duration_ms > 0) {
        k_work_reschedule(&s_melody_work, K_MSEC(note->duration_ms));
    }
}

static void melody_step(struct k_work *work)
{
    (void)work;
    if (!s_melody_playing) {
        return;
    }

    s_melody_index++;
    if (s_melody_index >= s_melody_count) {
        if (s_melody_repeat) {
            s_melody_index = 0;
            play_current_note();
        } else {
            s_melody_playing = false;
            buzzer_off();
            if (s_melody_done_cb) {
                s_melody_done_cb(true);
            }
        }
    } else {
        play_current_note();
    }
}

void buzzer_play_melody(const buzzer_note_t *notes, uint16_t count,
                        bool repeat, buzzer_melody_done_cb cb)
{
    if (!notes || count == 0) {
        return;
    }
    buzzer_stop_melody();

    s_melody_notes   = notes;
    s_melody_count   = count;
    s_melody_index   = 0;
    s_melody_repeat  = repeat;
    s_melody_done_cb = cb;
    s_melody_playing = true;

    play_current_note();
}

void buzzer_stop_melody(void)
{
    if (!s_melody_playing) {
        return;
    }
    k_work_cancel_delayable(&s_melody_work);
    s_melody_playing = false;
    buzzer_off();
    if (s_melody_done_cb) {
        s_melody_done_cb(false);
    }
}

bool buzzer_melody_is_playing(void)
{
    return s_melody_playing;
}
