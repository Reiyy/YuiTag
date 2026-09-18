/**
 * @file yuitag_buzzer.c
 * @brief 压电蜂鸣器驱动实现 (TIMER0 互补 PWM + Sleeptimer 旋律引擎)
 * @note PA05 (CC0 同相) / PA06 (CC1 反相)，谐振驱动峰峰值 ~6.6Vpp
 */

#include "yuitag_buzzer.h"
#include "sl_sleeptimer.h"
#include "sl_power_manager.h"
#include "em_cmu.h"
#include "em_gpio.h"
#include "em_timer.h"

/* --- 引脚与默认参数定义 --- */
#define BUZZER_PORT_P           gpioPortA
#define BUZZER_PIN_P            5
#define BUZZER_PORT_N           gpioPortA
#define BUZZER_PIN_N            6

#define BUZZER_DEFAULT_FREQ     4000U       // 4kHz 压电陶瓷谐振频率
#define BUZZER_DEFAULT_VOLUME   100U
#define BUZZER_TIMER_CLOCK_HZ   38400000U   // TIMER0 运行基准时钟 (38.4 MHz)

/* --- 模块内部状态 --- */
static bool s_buzzer_on;
static uint32_t s_freq = BUZZER_DEFAULT_FREQ;
static uint8_t  s_volume_pct = BUZZER_DEFAULT_VOLUME;

static const buzzer_note_t     *s_melody_notes;
static uint16_t                 s_melody_count;
static uint16_t                 s_melody_index;
static bool                     s_melody_repeat;
static bool                     s_melody_playing;
static buzzer_melody_done_cb    s_melody_done_cb;
static sl_sleeptimer_timer_handle_t s_melody_timer_handle;

static void melody_timer_callback(sl_sleeptimer_timer_handle_t *handle, void *data);
static void play_current_note(void);
static void timer_hw_init(void);
static void timer_apply(void);

static uint32_t s_prescaler = 1;

/* --- 定时器底层控制 --- */

static uint32_t presc_to_reg(uint32_t presc)
{
    uint32_t reg = 0;
    while (presc > 1U) { presc >>= 1U; reg++; }
    return reg << _TIMER_CFG_PRESC_SHIFT;
}

static void timer_hw_init(void)
{
    CMU->CLKEN0_SET = CMU_CLKEN0_TIMER0;

    TIMER_Init_TypeDef timerInit = TIMER_INIT_DEFAULT;
    timerInit.enable   = false;
    timerInit.mode     = timerModeUp;
    timerInit.prescale = timerPrescale1;
    timerInit.clkSel   = timerClkSelHFPerClk;
    TIMER_Init(TIMER0, &timerInit);

    // CC0: 同相输出
    TIMER_InitCC_TypeDef ccInit = TIMER_INITCC_DEFAULT;
    ccInit.mode  = timerCCModePWM;
    ccInit.outInvert = false;
    TIMER_InitCC(TIMER0, 0, &ccInit);

    // CC1: 反相输出 (构成差分驱动)
    ccInit.outInvert = true;
    TIMER_InitCC(TIMER0, 1, &ccInit);
}

static void timer_apply(void)
{
    uint32_t presc = 1U;
    uint32_t top_raw = BUZZER_TIMER_CLOCK_HZ / s_freq;
    while (top_raw > 65536U && presc <= 512U) {
        presc <<= 1U;
        top_raw = BUZZER_TIMER_CLOCK_HZ / (presc * s_freq);
    }
    if (top_raw > 65536U) { top_raw = 65536U; }
    if (top_raw < 2U)     { top_raw = 2U; }
    uint32_t top = top_raw - 1U;

    uint8_t duty_pct = s_volume_pct;
    if (duty_pct > 100U) { duty_pct = 100U; }
    uint32_t cc = ((top + 1U) * duty_pct) / 200U;
    if (cc > top) { cc = top; }

    if (presc != s_prescaler) {
        s_prescaler = presc;
        TIMER0->CFG = (TIMER0->CFG & ~_TIMER_CFG_PRESC_MASK)
                    | presc_to_reg(presc);
    }

    TIMER_TopSet(TIMER0, top);
    TIMER_CompareSet(TIMER0, 0, cc);
    TIMER_CompareSet(TIMER0, 1, cc);
}

/* --- 蜂鸣器基础控制接口 --- */

void buzzer_init(void)
{
    s_buzzer_on    = false;
    s_volume_pct   = BUZZER_DEFAULT_VOLUME;
    s_freq         = BUZZER_DEFAULT_FREQ;
    s_prescaler    = 1U;

    timer_hw_init();
    timer_apply();

    GPIO_PinModeSet(BUZZER_PORT_P, BUZZER_PIN_P, gpioModePushPull, 0);
    GPIO_PinModeSet(BUZZER_PORT_N, BUZZER_PIN_N, gpioModePushPull, 0);
}

void buzzer_on(void)
{
    if (s_buzzer_on) { return; }

    CMU->CLKEN0_SET = CMU_CLKEN0_TIMER0;

    GPIO_PinModeSet(BUZZER_PORT_P, BUZZER_PIN_P, gpioModePushPull, 0);
    GPIO_PinModeSet(BUZZER_PORT_N, BUZZER_PIN_N, gpioModePushPull, 0);

    GPIO->TIMERROUTE[0].CC0ROUTE = ((uint32_t)BUZZER_PORT_P << _GPIO_TIMER_CC0ROUTE_PORT_SHIFT)
                                   | ((uint32_t)BUZZER_PIN_P  << _GPIO_TIMER_CC0ROUTE_PIN_SHIFT);
    GPIO->TIMERROUTE[0].CC1ROUTE = ((uint32_t)BUZZER_PORT_N << _GPIO_TIMER_CC1ROUTE_PORT_SHIFT)
                                   | ((uint32_t)BUZZER_PIN_N  << _GPIO_TIMER_CC1ROUTE_PIN_SHIFT);
    GPIO->TIMERROUTE_SET[0].ROUTEEN = GPIO_TIMER_ROUTEEN_CC0PEN
                                    | GPIO_TIMER_ROUTEEN_CC1PEN;

    TIMER_CounterSet(TIMER0, 0);
    TIMER_Enable(TIMER0, true);
    s_buzzer_on = true;

    // 发声期间锁定 EM1 供电
    sl_power_manager_add_em_requirement(SL_POWER_MANAGER_EM1);
}

void buzzer_off(void)
{
    if (!s_buzzer_on) { return; }

    TIMER_Enable(TIMER0, false);
    GPIO->TIMERROUTE_CLR[0].ROUTEEN = GPIO_TIMER_ROUTEEN_CC0PEN
                                    | GPIO_TIMER_ROUTEEN_CC1PEN;

    GPIO_PinModeSet(BUZZER_PORT_P, BUZZER_PIN_P, gpioModePushPull, 0);
    GPIO_PinModeSet(BUZZER_PORT_N, BUZZER_PIN_N, gpioModePushPull, 0);

    CMU->CLKEN0_CLR = CMU_CLKEN0_TIMER0;

    s_buzzer_on = false;
    // 释放 EM1 要求，允许系统恢复 EM2 深度休眠
    sl_power_manager_remove_em_requirement(SL_POWER_MANAGER_EM1);
}

bool buzzer_toggle(void)
{
    if (s_buzzer_on) { buzzer_off(); } else { buzzer_on(); }
    return s_buzzer_on;
}

bool buzzer_is_on(void) { return s_buzzer_on; }

void buzzer_set_volume(uint8_t volume_pct)
{
    if (volume_pct > 100U) { volume_pct = 100U; }
    s_volume_pct = volume_pct;

    CMU->CLKEN0_SET = CMU_CLKEN0_TIMER0;

    bool was_on = s_buzzer_on;
    if (!was_on) { TIMER_Enable(TIMER0, true); }
    timer_apply();
    if (!was_on) { TIMER_Enable(TIMER0, false); }
}

/* --- 旋律播放引擎 (非阻塞调度) --- */

static void play_current_note(void)
{
    if (s_melody_index >= s_melody_count) { return; }
    const buzzer_note_t *note = &s_melody_notes[s_melody_index];

    if (note->freq == 0 || note->duration_ms == 0) {
        buzzer_off();
    } else {
        if (note->freq != s_freq) {
            s_freq = note->freq;
            bool was_on = s_buzzer_on;
            if (was_on) { buzzer_off(); }
            timer_apply();
            if (was_on) { buzzer_on(); }
        }
        if (!s_buzzer_on) { buzzer_on(); }
    }

    if (note->duration_ms > 0) {
        sl_sleeptimer_start_timer_ms(&s_melody_timer_handle,
                                     note->duration_ms,
                                     melody_timer_callback, NULL, false, 0);
    }
}

static void melody_timer_callback(sl_sleeptimer_timer_handle_t *handle, void *data)
{
    (void)handle; (void)data;
    if (!s_melody_playing) { return; }

    s_melody_index++;
    if (s_melody_index >= s_melody_count) {
        if (s_melody_repeat) {
            s_melody_index = 0;
            play_current_note();
        } else {
            s_melody_playing = false;
            buzzer_off();
            if (s_melody_done_cb) { s_melody_done_cb(true); }
        }
    } else {
        play_current_note();
    }
}

void buzzer_play_melody(const buzzer_note_t *notes, uint16_t count,
                        bool repeat, buzzer_melody_done_cb cb)
{
    if (!notes || count == 0) { return; }
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
    if (!s_melody_playing) { return; }
    sl_sleeptimer_stop_timer(&s_melody_timer_handle);
    s_melody_playing = false;
    buzzer_off();
    if (s_melody_done_cb) { s_melody_done_cb(false); }
}

bool buzzer_melody_is_playing(void) { return s_melody_playing; }

