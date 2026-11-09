# Sony TV — 电视端 IPTV 播放器

Apple TV 风格的 Android TV 直播应用。内置每日实测可用的 IPTV 频道清单，按国家/地区分组，支持遥控器上下键换台。

## 特性

- 📺 内置 580+ 频道 / 700+ 线路（央视、卫视、体育、境外台），全部经真实播放验证
- 🌍 频道按国家/地区自动分组：中国大陆 → 港澳台 → 美国 → 欧洲/日韩…
- 🏀 体育频道置顶：NBA TV、NFL Network、ESPN、CCTV-5+、广东体育等（国外台需代理）
- 🎮 纯遥控器操作：↑/↓ 换台、OK 呼出频道列表、CH+/CH- 换台、菜单键换源
- 🔁 每频道多条备用线路，播放失败自动切换（最多 4 次）
- 🌐 支持 7 个在线源（GitHub 每日更新源）+ 自定义 m3u 链接
- 🍎 仿 Apple TV 界面：黑色毛玻璃侧栏、白色高亮聚焦卡片、全屏播放

## 构建

依赖：JDK 17、Android SDK（platform 34 / build-tools 34.0.0）、Gradle 8.7

```bash
./build.sh
# 产物: app/build/outputs/apk/release/app-release.apk
```

纯 Java 实现（无 native 库），单一 APK 覆盖全架构，armeabi-v7a 32 位老电视也能跑。

## 技术栈

- Java + Media3 ExoPlayer 1.4.1
- AGP 8.5.2 / Gradle 8.7，minSdk 21
- 内置清单：`app/src/main/assets/verified.m3u`

## 安装

**方式一：直接下载 APK（无需自行构建）**

[apk/Sony-TV-1.3.apk](apk/Sony-TV-1.3.apk)（v1.3，约 5MB，纯 Java 无 native 库，全架构通用）

SHA-256: `3906bf76c3ec0dd480fbf1db81d541585ce86d32d57f35e5ecbfc97ee1aa2b26`

```bash
adb connect <电视IP>:5555
adb install -r Sony-TV-1.3.apk
```

**方式二：自行构建**

```bash
./build.sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 免责声明

内置频道清单来自公开的社区 IPTV 项目（Guovin/iptv-api、vbskycn/iptv、iptv-org 等），仅供学习研究，请支持正版内容。

## License

MIT
