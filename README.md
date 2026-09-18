# YuiTag
[<img src="https://img.shields.io/github/license/reiyy/yuitag?color=brightgreen">](https://raw.githubusercontent.com/Reiyy/YuiTag/main/LICENSE)
[<img src="https://img.shields.io/github/v/release/reiyy/yuitag?color=brightgreen&include_prereleases">](https://github.com/Reiyy/YuiTag/releases)
[<img src="https://img.shields.io/github/downloads/reiyy/yuitag/total.svg">](https://github.com/Reiyy/YuiTag/releases)

## 简介 

**YuiTag**是一个可以帮你在忘记东西放在哪时寻找物品的蓝牙寻物Tag。  
通过**YuiTag App**在连接设备后可以发送响铃指令通过蜂鸣器声音寻找Tag。  
同时还兼容 **FindMy** 网络，并实现了基于时间的无限密钥派生，能够在室内寻物的基础上提供一定的全球定位能力。  
配套**YuiTag App**支持个性化配置，包括查找页面主题，Tag图标，蓝牙广播间隔，FindMy密钥轮换时间等配置。  
  
YuiTag有两个版本 Sparks火花 / Starlight星光  
Sparks为纯室内寻物版本，不支持FindMy网络  
Starlight在具有Sparks全部功能的基础上支持FindMy网络  

## 兼容性

Android 12+ (minSdk 31)  
BLE 5.0+

## 演示图
![主页和Tag页](/tools/Documentation/img/主页和Tag页.jpg)  
![查找页和个性化查找页](/tools/Documentation/img/查找页和个性化查找页.png)  
![PCB和成品图](/tools/Documentation/img/PCB和成品图.jpg)  

## 运行

1. 焊接PCB硬件
2. 烧录固件
3. 配置macless-haystack后端
4. 配置YuiTag App
5. 使用YuiTag App绑定Tag
6. 配置Tag
7. Enjoy, heart❤️! =w=

以上为简要步骤，具体请查看：  
关于硬件，请查看 [硬件文档](/tools/Documentation/Hardware.md)  
关于固件，请查看 [固件文档](/tools/Documentation/Firmware.md)  
关于YuiTag App，请查看 [App文档](/tools/Documentation/App.md)  
关于macless-haystack后端，请查看 [lovelyelfpop/macless-haystack项目](https://gitee.com/lovelyelfpop/macless-haystack)

## 下载

- 预编译固件和App及PCB制板文件，  
前往 [Github 发布页](https://github.com/Reiyy/YuiTag/releases)  

## 注意事项/其他
我并非专业人士，本项目仅为我业余开发。  
本项目代码几乎100%由AI编写，虽所有决策和方案设计由人类完成，且已经过人工复核功能的可用性，但对于可靠性，长期运行稳定性以及是否有潜在功能异常问题仍不能保证。  
**请勿依赖本Tag为你寻找重要物品！请勿将其作为你获知任何重要物品位置的唯一途径！**  
如你有查找重要物品的需求，请使用正版的商业Tag产品，如苹果AirTag，小米Tag，华为Tag，三星Tag，谷歌PixelTag等产品！  

本项目的主要设计目的是以室内寻物为主，因我和我家人经常找不到东西，故设计了此Tag，用于和家中各种物品放在一起，通过蓝牙信号强度大致判断区域范围，再使用蜂鸣器响铃功能精确定位。  
FindMy功能并非是本Tag的主要支持功能，  
初版基于BG22芯片开发，FindMy功能是看到芯片Flash还剩不少才加上，由于之前就用过[lovelyelfpop/macless-haystack](https://gitee.com/lovelyelfpop/macless-haystack)项目的DIY FindMy Tag，但固定的一组密钥长期使用之后定位就不怎么刷新了，由于我的Tag本身为了接收响铃指令已经是可连接设计，也已经有了App，故直接用了基于时间的方法来实现无限密钥派生。

FindMy种子复用绑定流程中协商出的通信密钥，随后App向Tag发送当前时间戳进行校时，Tag将自身定时器时间与校时时间锚定，之后双方即可无需沟通各自通过相同的种子密钥和当前时间派生出一致的广播密钥。

Tag时间采用**乐观恢复机制**，即默认为每次断电的时间应该较短，比如几分钟换个电池。  
每5分钟Tag会将当前时间覆写回校时时间戳，Tag断电后时间最多会偏差5分钟，再加上换电池操作的时间，最终总偏差一般不会超过10分钟，加上App端的查询范围扩展的补偿机制，也能正确的获取到所有定位数据，Tag自身的时钟漂移在也可通过查询范围扩展补偿。  
查询范围扩展也为可自定义配置的参数，可根据实际的使用习惯进行设置。  

FindMy的定位信息是否实时和准确取决于周边Apple设备的密度，以及Tag硬件的射频性能。  
我没有专业的射频测试设备，无法进行准确的PCB天线射频性能调校，故该DIY Tag硬件的射频性能无法与成熟的经过专门调校的商业产品比较，射频性能不佳会导致信号无法传输的更远，也就降低了能被周围Apple设备发现的机会，进而影响定位数据的实时性。

## 功能
**已实现：**  
基于蓝牙信号的大致范围定位  
基于蜂鸣器响铃的精确声音定位  
FindMy兼容提供一定的全球定位查找能力  
多参数可自定义的配套查找App  
支持OTA固件更新(Sparks不支持)

**待实现：**  
[App&固件]可自定义蜂鸣器响铃旋律  
[App]更好的位置轨迹

## 致谢

- [lovelyelfpop/macless-haystack](https://gitee.com/lovelyelfpop/macless-haystack): 提供macless-haystack后端，和FindMy实现参考
- [acalatrava/openhaystack-firmware](https://github.com/acalatrava/openhaystack-firmware): 提供FindMy数据格式和固件编写参考

## 许可证

YuiTag 采用 **GNU General Public License v3 (GPL-3)** 许可证 (https://www.gnu.org/licenses/gpl-3.0.html).