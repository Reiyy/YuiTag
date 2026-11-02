# 固件文档

YuiTag项目提供了多个不同芯片方案的固件，如无特殊说明，不同芯片方案的固件功能完全一致。
在项目发布中已经提供了编译好的固件，如果你使用项目的官方PCB硬件，可直接烧录项目提供的固件。
如果你使用了其他硬件则需要根据实际硬件的连线修改固件源码配置的GPIO等。

## 固件烧录

### nRF52
软件：J-Flash  
烧录器：J-Link

打开J-Flash，新建项目，选择“Nordic Semi nRF52832_xxAA”/“Nordic Semi nRF52810_xxAA”

从发布下载固件，将固件烧录即可。

### EFR32BG22  
软件：J-Flash  
烧录器：J-Link(需要支持**Arm® Cortex® -M33**内核，比如J-Link RA4M2)  

打开J-Flash，新建项目，选择“Silicon Labs EFR32BG22CxxxF512”。  

将Bootloader和App固件烧录即可。
(BG22平台暂不提供编译产物，可自行通过**Simplicity Studio V5**从源码构建)
