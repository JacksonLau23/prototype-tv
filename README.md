# Prototype TV — 电视端 IPTV 播放器

Apple TV（tvOS）风格的 Android TV 直播应用。内置全网抓取、逐条验证的 1080P+ 频道清单，按分类/国家分组，军绿色强调色，纯遥控器操作。

## 特性

- 📺 内置 331 条频道线路（央视 37 / 卫视 64 / 体育 60 / 影视 47 / 国际 45 + 美·韩·俄·德·法·英·日分国家分区），全部 1080P+ 且经真实播放验证
- 🛡️ 六源抓取（Guovin / YanG / vbsky / YueChan / wwb521 / iptv-org）→ 并发 ffprobe 过滤 1080P → 逐频道抽帧目检，剔除盗版告警、电视购物、测试卡等假流
- 🍎 tvOS 设计语言：纯黑底、12dp 圆角卡片、聚焦微放大、军绿强调色（#8A9B62）
- 🖼️ 顶栏时钟 + Hero 大卡（军绿渐变）+ 纵向分区横向卡片流
- ⚡ RTSP 强制 TCP 交织传输（运营商 IPTV 修复），北京联通秒开线优先
- 📶 缓冲时右下角实时显示网速（Mb/s）
- 🔁 每频道多线路自动切换：14 秒缓冲看门狗，超时自动换下一条
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

## 安装

**方式一：直接下载 APK（无需自行构建）**

👉 [Releases v1.4](https://github.com/JacksonLau23/sony-tv-iptv/releases/tag/v1.4) 下载 `Prototype-TV-1.4.apk`（约 5MB，纯 Java 无 native 库，全架构通用）

SHA-256: `610888ed01b316a7cbd6669235ba996a273bf41dccc0416107be662c737009f8`

```bash
adb connect <电视IP>:5555
adb install -r Prototype-TV-1.4.apk
```

**方式二：自行构建**

```bash
./build.sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ 如果电视上运行了 Clash 等代理（自动路由全部流量），国内 IPTV 流可能超时或被假流农场按 IP 推送盗版告警，建议给 `com.lau.iptv` 设置直连分流。

## 免责声明

内置频道清单来自公开的社区 IPTV 项目（Guovin/iptv-api、vbskycn/iptv、iptv-org 等），仅供学习研究，请支持正版内容。

## License

来自北京朝阳刘嘉尔
