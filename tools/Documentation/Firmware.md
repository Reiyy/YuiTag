# 固件文档

YuiTag项目提供了多个不同芯片方案的固件，如无特殊说明，不同芯片方案的固件功能完全一致。
在项目发布中已经提供了编译好的固件，如果你使用项目的官方PCB硬件，直接查看固件烧录部分即可。

## 固件烧录

### EFR32BG22  
软件：J-Flash  
烧录器：J-Link(需要支持**Arm® Cortex® -M33**内核，比如J-Link RA4M2)  

打开J-Flash，新建项目，选择“Silicon Labs EFR32BG22CxxxF512”。  

将Bootloader和App固件烧录即可。

### nRF52
软件：J-Flash  
烧录器：J-Link

打开J-Flash，新建项目，选择“Nordic Semi nRF52832_xxAA”/“Nordic Semi nRF52810_xxAA”

将固件烧录即可。

## 固件编译
