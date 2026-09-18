#ifndef SL_BT_IN_PLACE_OTA_DFU_CONFIG_H
#define SL_BT_IN_PLACE_OTA_DFU_CONFIG_H

// <<< Use Configuration Wizard in Context Menu >>>

// <h> In-Place OTA DFU Security

// <q SL_BT_IN_PLACE_OTA_DFU_BONDING_REQUIRED> Bonding required for OTA transfer
// <i> Bonding required to proceed with the in-place OTA transfer.
// <i> Default: 0
#define SL_BT_IN_PLACE_OTA_DFU_BONDING_REQUIRED            0

// </h>

// <h> In-Place OTA DFU Transfer

// <o SL_BT_IN_PLACE_OTA_DFU_CALC_DELAY_MULTIPLIER> Calculated delay multiplier <3-20>
// <i> Default: 5
#define SL_BT_IN_PLACE_OTA_DFU_CALC_DELAY_MULTIPLIER       5

// <o SL_BT_IN_PLACE_OTA_DFU_MIN_DELAY_TO_DISCONNECT> Minimum delay [msec] <10-5000>
// <i> Default: 1000
#define SL_BT_IN_PLACE_OTA_DFU_MIN_DELAY_TO_DISCONNECT     1000

// </h>

#endif // SL_BT_IN_PLACE_OTA_DFU_CONFIG_H
