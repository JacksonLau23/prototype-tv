# Prototype TV — 电视端 IPTV 播放器

YouTube TV 官方版式的 Android TV 直播应用。内置全网抓取、逐条验证的 1080P+ 频道清单，按分类 / 国家分区，军绿色强调色，纯遥控器操作。

## 特性

- 📺 内置 418 条频道线路 / 397 个频道（央视 / 卫视 / 地方台 / 港澳台 / 体育 / 海外新闻 / 日本 / 美国 / 英国 / 德国 / 法国… 分国家地区），全部经 Sony 电视真实网络逐条探测验证
- 🆕 **v1.10 变化**：
  - **音乐分类加入嘻哈**：新增 **REVOLT 嘻哈**（美国嘻哈文化台）、**Deluxe Rap 说唱**（德国纯说唱 MV）、**Trace Urban 都市嘻哈**（欧洲都市音乐台，主打 hip-hop / R&B），三个台在 Sony 电视上逐台实播抽帧确认，台标已打包
  - 音乐分类现为 7 个频道（音乐欣赏、REVOLT 嘻哈、Deluxe Rap 说唱、Trace Urban 都市嘻哈、CCTV-15 音乐等）
  - 说明：Pluto TV 系的 VH1+ Hip Hop & Rap / BET Throwbacks / Vevo True School Hip-Hop、Vevo Hip Hop 官方 CDN 均因地区限制（分片 403 / 会话 token 绑定 IP）在电视上无法播放，未写入内置表
- 🆕 **v1.9 变化**：
  - **新增音乐分类**：chips 里多了「音乐」，收录音乐欣赏、CCTV-15 等；音乐欣赏播放实测正常
  - **港澳台拆分**：原先单一的「港澳台」chip 拆成 **香港 / 台湾 / 澳门** 三个独立分类（内置表 14 条港澳台频道的分组已按实际地区重排）
  - **取消播放时叠加台标**：不再给没有台标的源补台标，全屏画面保持干净（只有卡片上仍有台标）
  - **新增实测可用**：三立戏剧台、TVBS 新闻（台湾）、明珠台（香港，2 条线路）、亚洲新闻台 CNA（新加坡）、音乐欣赏（台湾）；台视新闻补 2 条备用线路
  - 说明：**八大（八大第一 / 综合 / 戏剧 / 娱乐）本轮未能加入**。共试过 17 台主机的 30 余条线路（pili-live-hdl 已停止解析、cloud-play 返回 404、4gtv 镜像与 smt/yshmzmc 全部黑屏），电视端逐一抽帧全部失败，八大只在台湾有线与付费平台播出，公共源里没有可用直播。若你有付费源（4gtv / LiTV / 台湾宽带 IPTV），可用「手动输入 m3u 链接」接入
- 🆕 **v1.8 新增 30 个频道**：
  - **港澳台**：ViuTV、华视、民视、纬来体育、TVBS 亚洲
  - **日本**：NHK 综合、日本电视台、朝日电视台、东京电视台、富士电视台、读卖电视台、TBS 电视台
  - **美国**：AMC、Big Ten Network、CBS News、NBC News、Disney Channel、Food Network、ION、NHL Network、Nickelodeon、Paramount Network、PBS、SEC Network、Showtime、Starz、Syfy、Tastemade、Tennis Channel
  - **英国**：Channel 4、S4C
- 🎬 **YouTube TV 官方版式**（v1.8）：
  - 扁平顶栏（标题 + 正在播放胶囊 + 时钟），无厚重卡片背景
  - **分类 chips 过滤条**：全部 / 央视 / 卫视 / 地方台 / 港澳台 / 体育 / **音乐** / **香港** / **台湾** / 美国 / 英国 / 日本 / 德国…，选中白底黑字、聚焦军绿描边
  - 频道面板只占屏幕 **68%**，上方 1/3 始终露出正在播放的画面
  - 焦点卡片：纯白底 + 黑字 + 军绿 4dp 粗描边 + 放大 + 投影，远距离一眼锁定；未聚焦卡片保持高可读亮度
- 🔄 **应用内在线更新**（v1.7+）：菜单「检查更新」自动比对 GitHub Release，下载 APK 一键安装；启动时也会静默检查
- 🏷️ **频道台标**：卡片大 logo（v1.9 起不再在播放画面上叠加台标），250+ 台标离线打包进 APK，零网络依赖
- 🛡️ 多源抓取（Guovin / YanG / vbsky / YueChan / iptv-org / Free-TV）→ 逐条电视端探测，剔除死链、盗版告警、购物台、测试卡等假流
- 🤝 **与 VPN 共存**（v1.5+）：应用自动绑定物理网卡（`bindProcessToNetwork` 绕过 tun0），Clash / Zion 等 tun 代理开着也能直连运营商线路秒开
- 🔓 信任所有证书的自定义 Http 数据源：IPTV 圈大量自签名 https 和跨协议重定向不再翻车
- ⚡ RTSP 强制 TCP 交织传输（运营商 IPTV 修复），北京联通 RTSP 秒开线优先
- 📶 缓冲时右下角实时显示网速（Mb/s）
- 🔁 每频道多线路自动切换：14 秒缓冲看门狗，超时自动换下一条（RTSP → 国内 CDN → 海外源 依次降级）
- 🔬 **线路全量实测**：内置 ProbeReceiver 以播放器同网络栈逐一探测，剔除全部死链
- 🎮 纯遥控器操作：↑/↓ 换台、左/OK 呼出频道面板、CH+/CH- 换台、菜单键换源与检查更新
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
- 分类 chips 由 `Catalog.category / regionOf` 驱动，按频道表动态生成

## 安装

**方式一：直接下载 APK（无需自行构建）**

👉 [Releases v1.8](https://github.com/JacksonLau23/prototype-tv/releases/tag/v1.8) 下载 `Prototype-TV-1.8.apk`（约 13MB 含离线台标，纯 Java 无 native 库，全架构通用）

> 已装 v1.7 的用户：菜单键 → 检查更新，可在线升级。

```bash
adb connect <电视IP>:5555
adb install -r Prototype-TV-1.8.apk
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
