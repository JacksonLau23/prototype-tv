# Prototype TV — 电视端 IPTV 播放器

Apple TV（tvOS）风格的 Android TV 直播应用。内置全网抓取、逐条验证的 1080P+ 频道清单，按分类/国家分组，军绿色强调色，纯遥控器操作。

## 特性

- 📺 内置 359 条频道线路 / 272 个频道（央视 / 卫视 / 体育 / 影视 / 港澳台 / 海外新闻 / 国际 + 美·韩·俄·德·法·英·日分国家分区 / 联通IPTV 秒开线），全部经电视端实测验证
- 🆕 **v1.7 新增 33 个频道**：宁夏/海南/三沙/厦门卫视、凤凰香港、TVBS/台视/东森/三立/纬来、beIN/足球频道、BBC/CNN/Fox/ABC/半岛/法兰西24/DW/NHK/Arirang/TV5/Euronews/Bloomberg/CNBC/Sky/TRT/CNA、德国 Das Erste/ZDF/RTL——全部经 Sony 电视真实网络逐条探测可用
- 🔄 **应用内在线更新**（v1.7）：菜单「检查更新」自动比对 GitHub Release，下载 APK 一键安装；启动时也会静默检查
- 🏷️ **频道台标**：卡片大 logo + Hero 台标 + **全屏播放左上角常驻台标**（CCTV/卫视等源画面自带水印的台自动跳过，避免重复），230+ 台标离线打包进 APK，零网络依赖
- 🛡️ 六源抓取（Guovin / YanG / vbsky / YueChan / wwb521 / iptv-org）→ 并发 ffprobe 过滤 1080P → 逐频道抽帧目检，剔除盗版告警、电视购物、测试卡等假流
- 🏷️ **频道台标**（v1.6 新增）：卡片大 logo + Hero 大台标，210 个台标离线打包进 APK，零网络依赖
- 🤝 **与 VPN 共存**（v1.5）：应用自动绑定物理网卡（`bindProcessToNetwork` 绕过 tun0），Clash / Zion 等 tun 代理开着也能直连运营商线路秒开，不会被代理出口 IP 触发假流注入
- 🔓 信任所有证书的自定义 Http 数据源：IPTV 圈大量自签名 https 和跨协议重定向不再翻车
- 🍎 tvOS 设计语言：纯黑底、12dp 圆角卡片、聚焦放大 1.2× + 军绿粗描边 + 白卡黑字、军绿强调色（#8A9B62）
- 🖼️ 顶栏时钟 + **紧凑 Hero 条**（v1.7：大幅压缩高度，频道区占屏幕 64%+）+ 纵向分区横向卡片流
- ⚡ RTSP 强制 TCP 交织传输（运营商 IPTV 修复），北京联通 RTSP 秒开线（59 频道）优先
- 📶 缓冲时右下角实时显示网速（Mb/s）
- 🔁 每频道多线路自动切换：14 秒缓冲看门狗，超时自动换下一条（RTSP → 国内 CDN → 海外源 依次降级）
- 🔬 **线路全量实测**（v1.6）：内置 ProbeReceiver 以播放器同网络栈逐一探测，剔除全部死链
- 🎮 纯遥控器操作：↑/↓ 换台、OK 呼出频道列表、CH+/CH- 换台、菜单键换源
- 🌐 支持内置精选源 + 在线源 + 自定义 m3u 链接

## 构建

依赖：JDK 17、Android SDK（platform 34 / build-tools 34.0.0）、Gradle 8.7

```bash
./build.sh
# 产物: app/build/outputs/apk/release/app-release.apk
```

纯 Java 实现（无 native 库），单一 APK 覆盖全架构，armeabi-v7a 32 位老电视也能跑。

## 技术栈

- Java + Media3 ExoPlayer 1.4.1（RTSP / HLS）
- AGP 8.5.2 / Gradle 8.7，minSdk 21
- 内置清单：`app/src/main/assets/bjunicom.m3u`
- 自研 TransferListener 测速（缓冲状态实时显示）
- 自研 ProbeReceiver 线路实测通道（诊断/维护用）
- 自研 TrustAllDataSource（自签名证书 + 跨协议重定向）
- ConnectivityManager 物理网络绑定（VPN 共存）

## 安装

**方式一：直接下载 APK（无需自行构建）**

👉 [Releases v1.7](https://github.com/JacksonLau23/prototype-tv/releases/tag/v1.7) 下载 `Prototype-TV-1.7.apk`（约 12MB 含离线台标，纯 Java 无 native 库，全架构通用）

> v1.6 及以前版本装机用户：菜单键 → 检查更新，即可在线升级到最新版。

```bash
adb connect <电视IP>:5555
adb install -r Prototype-TV-1.6.apk
```

**方式二：自行构建**

```bash
./build.sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

> 🤝 **VPN 共存说明**：v1.5 起应用自动把播放流量绑定到物理网卡（Wi-Fi/以太网），Clash Meta、Zion 等 tun 代理无需任何分流配置即可共存——国内 IPTV 直连秒开，代理同时照常服务其他应用。

## 免责声明

内置频道清单来自公开的社区 IPTV 项目（Guovin/iptv-api、vbskycn/iptv、iptv-org 等），仅供学习研究，请支持正版内容。

## License

来自北京朝阳刘嘉尔
