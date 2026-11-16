# Prototype TV — 电视端 IPTV 播放器

YouTube TV 官方版式的 Android TV 直播应用。内置全网抓取、逐条验证的 1080P+ 频道清单，按内容 / 平台 / 国别大分类浏览，军绿色强调色，纯遥控器操作。

## 特性

- 📺 内置 605 条频道线路 / 443 个频道（应用内实测；央视 / 卫视 / 地方台 / 香港 / 台湾 / 澳门 / 体育 / 音乐 / **Twitch** / **YouTube** / 海外国别…），逐条探测 + 抽样实播 / 抽帧核对，全表 URL 严格去重
- **v1.23 变化：选频道聚焦当前台、换台信息浮层、字幕未连接不再抢屏**
  - **确定键进入选频道时聚焦当前播放的频道**：全屏按 OK 打开列表后，焦点/滚动直接落在当前正在看的频道卡片上（不再是列表第一项），接着换台更顺手；在分发层拦截确定键，缓冲状态下也能可靠打开列表
  - **换台信息浮层**：切台瞬间在画面左下角显示带军绿竖条的频道名 + 地区/线路，3.6 秒淡出；独立于右下角状态胶囊，不会被「缓冲中」或字幕错误刷掉
  - **字幕服务未连接不再抢屏**：字幕服务不可达时原来每 15 秒重连失败就 `setStatus()` 弹「字幕服务未连接」，把换台信息全盖掉；现在只记日志不弹 UI，且字幕关闭时压根不发起 SSE 连接（连日志都不刷）
  - 频道表不变（605 条线路 / 443 个频道），`ASSET_VER` 仍为 18，升级不重置节目源与自定义分类顺序
- 🆕 **v1.22 变化：所有频道音量平衡统一，切台不再忽大忽小**
  - 根因是不同源的音频响度基准不一致（有的 -14 LUFS、有的 -24 LUFS）；`player.setVolume()` 只能调小不能调大，所以改为在 `AudioSink` 的 processor 链里做**实时响度归一化**
  - `LoudnessNormalizer`：对解码后的 PCM 做近似 K 加权响度测量 → 2.5 秒长期积分 → 「快压慢放」平滑增益（压低 0.12s / 抬高 1.5s 时间常数）→ 逐样本增益过渡 + 峰值保护；只统一频道整体响度，不压平节目内对白/掌声的动态
  - **切台保留当前增益**并进入 1.6 秒快速收敛窗口，不会瞬间突变；静音门限避免广告间隙把底噪拉爆；增益限制在 -11dB ~ +10dB
  - 菜单新增「频道音量平衡（开/关）」与「音量目标（标准 / 柔和 / 响亮）」，**默认开启**；关闭时透明透传，音频链无需重建
  - 强制 16bit PCM 输出并禁用 offload，保证所有 HLS / RTSP / 平台源都经过处理；不支持的编码原样透传，不会导致播放失败
  - 实机定量验证：两路原始响度相差 12.1dB 的音频，归一化后输出响度分别为 0.1205 / 0.1197（**差 0.06dB**）；切台从 -5.9dB 平滑收敛到 +6.2dB，无 FATAL / ANR / NPE
  - 频道表不变（605 条线路 / 443 个频道），`ASSET_VER` 仍为 18，升级不重置节目源与自定义分类顺序
- 🆕 **v1.21 变化：非中文频道可叠加实时简中字幕（旁路 ASR + 机器翻译）**
  - 直播流本身没有简中字幕轨，所以走旁路：Mac / NAS 上的 `subtitle-service` 用 ffmpeg 抽同一路流的音频 → 流式 ASR → 机器翻译 → SSE 推送带时间戳的双语字幕，电视端按 `player.getCurrentPosition()` 排队显示
  - 菜单新增「实时简中字幕（开/关）」「字幕范围（仅外语频道 / 全部频道）」「字幕服务地址…」「字幕时间偏移…」；遥控器 `CAPTIONS` 键可一键开关
  - 电视端用裸 Socket 直连 + 服务端 HTTP/1.0 纯文本流，避开这台 Sony 上 OkHttp 长连接被判 `unexpected end of stream` 的问题；默认经 `adb reverse` 走本机 loopback
  - 默认关闭、主播放链路零改动；切台 / 自动跳台会清空字幕队列并让服务端换 session，旧频道迟到的字幕丢弃；3 秒无字幕自动收起，不阻塞播放
  - **MVP 默认 demo provider**：抽音频与 VAD 是真实链路，字幕文本为固定样例，用于验证全链路与时钟对齐；接入真实识别只需配环境变量（见下），无需改 App
  - 频道表不变（605 条线路 / 443 个频道），`ASSET_VER` 仍为 18，升级不会重置节目源与自定义分类顺序
- 🆕 **v1.20 变化：加回 Twitch / YouTube 源，并给两个平台单独分类**
  - 恢复此前移除的 8 个网络电台，并扩到 **28 条平台源**：Twitch 15 个（音乐 6 / 体育·电竞 9）、YouTube 13 个（音乐 4 / 体育 6 / 新闻 3），全部逐条实测在播且能取回 HLS 清单
  - 顶部分类条新增「**Twitch**」「**YouTube**」两个独立分类（共 19 个）；新分类自动补到已保存的自定义顺序末尾，可自行拖动
  - 平台分类内部再按 **音乐 / 体育 / 新闻 / 综合** 二次分区；同一频道仍同时出现在「音乐」「体育」等内容分类里，但不会混进按国家划分的海外分类
  - 平台源按 URL scheme（`ytlive://` / `twitchlive://`）自动识别，播放前实时解析 YouTube 24/7 直播 HLS 与 Twitch usher 清单；24/7 流会换 ID、Twitch 频道也会下线，失效时自动跳台会跳过
  - **当前节点注意**：YouTube 对部分代理出口返回 `LOGIN_REQUIRED`，此时只能回退 Invidious，而其清单的分片签名绑定实例后端 IP，电视会 403。解析器已改为优先走 YouTube 原生清单（绑定电视自身出口 IP），换到未被风控的节点即可正常播放；**Twitch 不受此限制，当前节点实测可播**
  - 修正 `BET` 关键词误命中（`YouTube TalkSPORT` 里恰好含子串 `BET` 被误判为音乐台），改为按单词匹配
  - 最终 **605 条线路 / 443 个频道**、URL 零重复；`ASSET_VER` 18，升级后自动重置到内置源 + 全部分类（自定义分类顺序不受影响）
- 🆕 **v1.19 变化：修复海外音乐台（美国居多）播不出来**
  - 与代理节点无关：`jmp2.uk/stvp-*` 短链会 302 到 Pluto TV / Samsung TV Plus 的 `.m3u8`，但 URI 本身没有后缀，media3 把它当普通文件探测；叠加 `TrustAllDataSource` 关闭后被再次读取的空指针，最终报 `Source error`
  - 现在对 HLS 短链显式声明 `APPLICATION_M3U8`，`TrustAllDataSource.read()` 加 EOF 防护，重定向耗尽时也改为明确报错
  - 海外 HLS 首次加载需要 302 + 签名 + 拉分片，缓冲看门狗对海外源放宽到 22 秒（国内源仍是 14 秒快速跳台）
  - 实机复测 12 条（8 条无后缀短链 + 2 条 plu + 2 条国内对照）：修复前 2 条必报 `Source error` / NPE、多条缓冲超时；修复后全部出画面，NPE 归零
  - 频道表不变（577 条线路 / 415 个频道），`ASSET_VER` 仍为 17，升级不会重置自定义分类顺序
- 🆕 **v1.18 变化：海外频道按国家分类**
  - 顶部分类条新增「美国 / 日本 / 英国 / 德国 / 韩国 / 法国 / 俄罗斯 / **国际**」8 个国别分类（共 17 个，横向可滚动、焦点自动滚入视野），点进去只看该国频道；「国际」兜底收其余海外台
  - **海外体育 / 音乐台一并收录**（仍同时保留在「体育」「音乐」分类）；按原分组、`tvg-id` 国家码、台名国家词与电视台关键词综合判定归属，国内频道不会混入
  - 每个国别分类内部再按 **新闻 / 体育 / 音乐 / 综合** 二次分区；体育、音乐大类仍保持平铺
  - 新分类自动补到已保存的自定义顺序末尾，可用菜单「自定义分类顺序…」把它拖到前面
  - 按反馈**移除 8 个 Twitch / YouTube 网络电台**（音乐分类里的 `ytlive://` / `twitchlive://` 频道），内置表不再收录这两类源；运行时解析器代码保留，自定义 m3u 仍可按需接入
  - 最终 **577 条线路 / 415 个频道**、URL 零重复，其中国内 159 个、海外 256 个（美国 74 / 国际 120 / 俄罗斯 14 / 韩国 12 / 德国 11 / 英国 9 / 日本 9 / 法国 7）；`ASSET_VER` 17，升级后自动重置到内置源 + 全部分类
- 🆕 **v1.17 变化：精简央视分类**
  - 按反馈移除「高尔夫网球」「熊猫直播」两个频道（各 1 条线路），其余频道与线路不变；最终 585 条线路零重复
  - 体育分类里的国际 Golf / Tennis 台属于独立体育频道，本次保留
  - 内置表版本推进到 16，升级后自动重置到内置源 + 全部分类，删掉的频道立即消失
- 🆕 **v1.16 变化：融合 zyscj.com 直播源分区的电视直播 / 体育直播清单**
  - 抓取 `zyscj.com/zy/video/tv.html` 的「电视直播网站」「直播源」「体育直播」三个分区指向的公开清单（iptv-org、joevess/IPTV、Tvlist-awesome、MyIPTV、epg.pw 等），共解析 2 万余条候选
  - 1015 条重点候选做并发 HTTP 探测 + 700 条定向补探，剔除死链、电台、带时效签名的地址；按现有分类归一后新增 **111 条**可用线路（体育 38 / 音乐 34 / 地方台 20 / 台湾 13 / 央视 5 / 香港 1）
  - 按反馈**移除 CGTN 西语 / 法语 / 俄语 / 阿语 / 纪录频道**共 9 条（含本次新增 3 条），仅保留 CGTN 主频道、CGTN 英语、CGTN Global Biz；净增 102 条
  - 抽样抽帧目检确认内容正常（苏州文化生活、Fox Sports、B4U Music 等），无测试卡 / 购物垫片 / 错标；最终 587 条线路零重复
  - 升级后自动重置到内置源 + 全部分类（`ASSET_VER` 15），无需手动切源
  - 说明：各直播网站的播放页多为 JS 加密 / 临时赛事流，无法直接进 App；实际融合的是这些站点「直播源」分区给出的公开直链清单，体育部分以 24/7 体育台为主
- 🆕 **v1.15 变化：分类顺序可自定义**
  - 菜单键 → **「自定义分类顺序…」** 进入排序模式：焦点落在分类条上，**← → 直接把当前分类左右移动**，即时生效、即时保存；「全部」固定在首位不参与排序
  - 按 **OK / 返回 / 菜单** 退出排序模式；排序期间屏蔽换台、切分类等按键，避免误操作
  - 顺序写入本地偏好，重启应用 / 切换节目源后保持；菜单里 **「恢复默认分类顺序」** 一键还原
  - 兼容旧配置：保存顺序中的已废弃分类自动过滤，后续新增分类自动补到末尾
- 🆕 **v1.14 变化：播不出来的频道自动跳过 + 移除 GitHub 配图**
  - **失败自动跳台**：同一频道最多尝试 3 次（有备线时顺带换线路，无备线则重试当前线路），仍放不出来就自动切到下一个频道；状态胶囊提示「试了 3 次不通 · 自动切台 n/12」，播放成功后计数清零
  - **失败判定更全**：播放错误、14 秒缓冲超时、YouTube / Twitch 运行时解析失败、地址构造异常走同一套重试逻辑；每次播放尝试带代号，丢弃迟到的旧解析回调，避免一次失败被重复计数或换台后被旧回调打断
  - **防死循环**：连续自动跳台最多 12 个台，整表都不通时停下提示检查网络；走到列表末尾自动回到第一个台；调试入口（`play_url`）默认仍停在失败状态便于抽帧核对，加 `--ez auto_hop true` 可实机验证跳台
  - **老电视兼容**：网络绑定在 Android 5.x（API 21）回退 `setProcessDefaultNetwork`，不再因 `bindProcessToNetwork` 的 `NoSuchMethodError` 闪退
  - **移除 GitHub 配图**：README 与 Release 不再附界面截图
- 🆕 **v1.13 变化：音乐扩到 28 个（以嘻哈为主）+ 接入 YouTube / Twitch 直播 + 界面再缩小**
  - **音乐分类 13 → 28 个频道**，嘻哈 / 说唱为主：新增 Yo! MTV 经典嘻哈、BET Pluto TV 嘻哈、Stingray Hip Hop、Vevo Hip-Hop R&B、MTV Spankin New 等 FAST 频道，以及 **BET Throwbacks / Vevo / VH1 / Trace Urban** 等此前因地区限制未收录的台
  - **接入 YouTube 24/7 嘻哈直播**：`ytlive://<videoId>` 运行时通过 Invidious 公共实例解析（YouTube 的 HLS 带 expire 签名且绑 IP，写死进 APK 几小时就失效），缓存 15 分钟，失败自动回退 YouTube 网页
  - **接入 Twitch 24/7 嘻哈电台**：`twitchlive://<频道名>` 运行时走 Twitch GraphQL `PlaybackAccessToken` → usher 取回带签名 HLS，缓存 30 分钟；内置 Lofi Girl、Chillhop、Leek Beats、ChilledCat、Real Rap、Relax Beats 六个台
  - **海外源自动切换网络**：普通频道仍绑定物理网卡直连运营商，YouTube / Twitch 频道播放时临时恢复默认网络走代理，解决绕过 VPN 后海外 CDN 连不上的问题
  - **修死链**：`i.mjh.nz/SamsungTVPlus/<id>.m3u8` 实为 404，改用 `jmp2.uk/stvp-<id>` 正确格式
  - **外部源合并与全表去重**：抓取 `github.com/ngo5/IPTV` 与 `zyscj.com` 的公开列表，电视端探测 95 条候选，仅合并 2 个干净新频道（风云音乐、音乐现场）；全表按 URL 严格去重，485 条线路无一条重复
  - **频道区再缩小**：浏览面板 55% → **42%**（播放画面露出 58%），卡片 190×106dp → **164×92dp**，焦点放大 1.16 倍
  - 说明：FAST / YouTube / Twitch 源需要可访问海外 CDN 的代理节点；国内运营商线路不受影响
- 🆕 **v1.12 变化：央视线路全面复核与重建**
  - **以央视官网 EPG 为基准逐条比对**：CCTV-1《好好的时光》第 17/18 集、CCTV-13《新闻直播间》、CCTV-5 法甲第 10 轮集锦 / 速度滑冰世界杯（中国站）。对原 52 条央视线路做了 Mac 端三轮抽帧 + Sony 电视实播截图 + 画面指纹比对
  - **查出的三类问题**：① 第三方聚合源会随机插播「麦可通」类购物广告垫片（订购电话 400-689-3979、咨询热线 400-788-3916），CCTV-3 三条线路三轮采样 3/3 全是广告；② `ali-m-l.cztv.com` 那条 CCTV-13 是带「1月19日 10:42」角标的旧录播，根本不是直播；③ 错标：`cctv4k.m3u8` 实际是 CCTV-4K 超高清却标成 CCTV-4 中文国际，`espanol-liveali.cgtn.com` 是 CGTN 西语却标成 CGTN 法语
  - **重建线路池**：`38.75.136.137/gslb/dsdqpub` 与 `204.12.221.218/3m1080p` 两组实测与官网同步的源互补覆盖 CCTV-1～17，全部提为首选；剔除 21 条垫片 / 旧录播 / 死链（`0472.org`、`bztv.tvbus.cc`、CGTN 纪录 english 流等），北京联通 RTSP 降级为末位备源（该网段下电视端实播黑屏）
  - **修正错标**：新增独立频道 **CCTV-4K 超高清**（含离线台标），CGTN 法语 / 西语归位；央视分组 52 → 45 条线路 / 25 个频道
  - 说明：公共第三方源仍可能偶发插播广告，遇到时按菜单键换源即可；要彻底消除需接入运营商 IPTV 或央视官方 CDN（后者需可访问官方 VDN 的国内节点）
- 🆕 **v1.11 变化**：
  - **分类去重精简**：chips 固定为 **全部 / 央视 / 卫视 / 地方台 / 香港 / 台湾 / 澳门 / 体育 / 音乐** 九个；去掉了与「香港/台湾/澳门」重复的「港澳台」，也不再动态生成美国/英国/日本/德国等一堆国家小分类
  - **体育、音乐不再按国家细分**：选中这两个内容大分类后，列表直接平铺，不再出现「俄罗斯」「德国 1 个频道」这类二级分区
  - **补上 CCTV-5 体育**：3 条实测可用线路（均按内置表已有央视聚合源规则推出，电视端实播抽帧确认为 CCTV-5 体育台标），「体育」分类第一张即为 CCTV-5，台标已打包
  - **焦点更醒目**：白卡 + 军绿实描边 + 外层柔光晕 + 放大 1.22 倍，未聚焦卡片降到 76% 亮度；chip 聚焦也会放大并加亮描边
  - **频道区缩小**：浏览面板从 68% 降到 **55%**（播放画面露出 45%），卡片从 212×120dp 缩到 190×106dp
  - **回补地区限制频道**：Vevo Hip Hop、Vevo R&B、VH1 Hip Hop、BET Throwbacks、Vevo True School、Hip Hop TV 六个嘻哈台写入音乐分类（共 13 个频道）。这些源在部分网络下分片 403 / token 绑定请求方 IP，切换到对应地区节点即可播放
  - **修正内置表格式**：41 个多线路条目原本被解析器误读，会产生 82 个「频道 NNN」垃圾频道；已全部拆成「一条 EXTINF 对应一条 URL」的规范写法，频道数由 398（含垃圾）变为 316 个真实频道，多线路备源现在也能正确显示与切换
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
  - **分类 chips 过滤条**：全部 / 央视 / 卫视 / 地方台 / 香港 / 台湾 / 澳门 / **体育** / **音乐** / **Twitch** / **YouTube** / **美日英德韩法俄 · 国际**（v1.20 共 19 个，横向滚动；国别分类与平台分类内另有新闻 / 体育 / 音乐 / 综合二次分区），选中白底黑字、聚焦军绿描边 + 放大
  - 频道面板只占屏幕 **42%**（v1.13），上方 **58%** 始终露出正在播放的画面
  - 焦点卡片：纯白底 + 黑字 + 军绿实描边 + 外层柔光晕 + 1.16 倍放大，远距离一眼锁定；未聚焦卡片压暗到 76% 以拉开对比
- 🔄 **应用内在线更新**（v1.7+）：菜单「检查更新」自动比对 GitHub Release，下载 APK 一键安装；启动时也会静默检查
- 🏷️ **频道台标**：卡片大 logo（v1.9 起不再在播放画面上叠加台标），250+ 台标离线打包进 APK，零网络依赖
- 🛡️ 多源抓取（Guovin / YanG / vbsky / YueChan / iptv-org / Free-TV）→ 逐条电视端探测，剔除死链、盗版告警、购物台、测试卡等假流
- 🤝 **与 VPN 共存**（v1.5+）：应用自动绑定物理网卡（`bindProcessToNetwork` 绕过 tun0），Clash / Zion 等 tun 代理开着也能直连运营商线路秒开
- 🔓 信任所有证书的自定义 Http 数据源：IPTV 圈大量自签名 https 和跨协议重定向不再翻车
- ⚡ RTSP 强制 TCP 交织传输（运营商 IPTV 修复），北京联通 RTSP 秒开线优先
- 📶 缓冲时右下角实时显示网速（Mb/s）
- 🔁 **播放失败自动恢复**（v1.14）：14 秒缓冲看门狗 + 单频道 3 次尝试（自动换线路 / 无备线则重试），仍不通就自动切下一个频道，连续最多跳 12 个台后停下提示检查网络
- 🔬 **线路全量实测**：内置 ProbeReceiver 以播放器同网络栈逐一探测，剔除全部死链
- 🎮 纯遥控器操作：↑/↓ 换台、左/OK 呼出频道面板、CH+/CH- 换台、菜单键换源 / 检查更新 / 自定义分类顺序
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
- 实时简中字幕旁路：`SubtitleClient`（SSE + 播放时钟排队）/ `subtitle-service`（ffmpeg + 可插拔 ASR/MT provider，纯 Python 标准库）
- 频道音量平衡：`LoudnessNormalizer`（media3 `BaseAudioProcessor`，近似 K 加权 + 快压慢放 AGC），经自定义 `DefaultRenderersFactory.buildAudioSink()` 注入
- 分类 chips 由 `Catalog.category / regionOf / overseasChip` + `Channel.platform`（按 `ytlive://` / `twitchlive://` scheme 自动识别）驱动，按频道表动态生成

## 安装

**方式一：直接下载 APK（无需自行构建）**

👉 [Releases v1.23](https://github.com/JacksonLau23/prototype-tv/releases/tag/v1.23) 下载 `Prototype-TV-1.23.apk`（约 13MB 含离线台标，纯 Java 无 native 库，全架构通用）

> 已装 v1.7 的用户：菜单键 → 检查更新，可在线升级。

```bash
adb connect <电视IP>:5555
adb install -r Prototype-TV-1.23.apk
```

**方式二：自行构建**

```bash
./build.sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

> 🤝 **VPN 共存说明**：v1.5 起应用自动把播放流量绑定到物理网卡（Wi-Fi/以太网），Clash Meta、Zion 等 tun 代理无需任何分流配置即可共存——国内 IPTV 直连秒开，代理同时照常服务其他应用。

## 频道音量平衡（v1.22）

不同直播源的响度基准差异很大，切台时音量会忽大忽小。v1.22 在 media3 的 `AudioSink` 上挂了一个实时响度归一化处理器，把每个频道的**长期节目响度**拉到同一目标；默认开启，无需任何配置。

工作原理：解码后的 PCM 先做近似 K 加权（一阶高通去次低频 + 高频搁架提升）测响度，再用 2.5 秒时间常数积分得到频道整体响度，据此算出目标增益。增益用「快压慢放」平滑（突然变响 0.12 秒压下去、变轻 1.5 秒抬起来），并在每个音频块内逐样本线性过渡，配合 0.97 峰值上限防止削波。因为只跟踪长期响度，节目内部的对白、掌声、广告动态不会被压平。

切台时**保留上一频道的增益**，只重置测量并进入 1.6 秒快速收敛窗口，所以不会有瞬间突变；静音段不更新估计，避免把底噪放大。增益被限制在 -11dB ~ +10dB，超出范围的极端源只能部分补偿。

菜单里可调：

- 「频道音量平衡（已开启 / 已关闭）」：一键开关，关闭时音频透明透传
- 「音量目标（标准 / 柔和 / 响亮）」：标准推荐；柔和整体更轻、保留更多动态；响亮整体更响

诊断可用 ADB：`adb shell am start -n com.lau.iptv/.MainActivity --ez volume_normalize true --ei volume_target 0`。运行时日志标签 `VOLNORM` 会每 5 秒打印测量响度、收敛增益（dB）与峰值。

已知限制：音频直通（AC3/EAC3 passthrough）或系统 offload 路径下处理器拿不到 PCM，已通过强制 16bit PCM 输出 + 禁用 offload 规避；若个别设备仍走直通，该频道不会参与平衡。CPU 开销约每样本几次浮点运算，Sony BRAVIA 实测无卡顿。

## 实时简中字幕（v1.21）

非中文频道没有现成的简中字幕轨，因此字幕由旁路服务实时生成：服务端拉同一路直播流、抽音频、识别、翻译，电视端按播放时钟把双语字幕叠在画面上。电视只连局域网，主播放链路与频道表都不受影响。

```bash
# 1) 在 Mac / NAS 上启动旁路服务（需 ffmpeg，Python 3 标准库即可）
python3 subtitle-service/server.py          # 默认 0.0.0.0:8899

# 2) 自检：不依赖外网与电视，验证抽音频 → VAD → provider → SSE
python3 subtitle-service/selftest.py

# 3) 电视：菜单 → 打开「实时简中字幕」（默认地址 http://127.0.0.1:8899）
#    或 ADB 一键开启：
adb shell am start -n com.lau.iptv/.MainActivity \
    --ez subtitle_on true --es subtitle_url http://127.0.0.1:8899
```

服务启动时会自动执行 `adb reverse tcp:8899 tcp:8899`，把电视的 `127.0.0.1:8899` 转发到本机，**不需要**给 macOS 防火墙加例外、也不依赖电视上代理的局域网分流规则。实测 Sony BRAVIA 上 `bindProcessToNetwork` 返回 `FAILED(allowBypass denied)`，应用无法自行绕过 VPN，局域网地址会被 tun 接管，因此默认走 loopback + ADB 转发。ADB 断开后转发失效，重连后重跑一次服务或手工 `adb reverse tcp:8899 tcp:8899` 即可；设 `SUBTITLE_ADB_REVERSE=0` 可关闭自动转发。若服务部署在 NAS 上无法用 ADB，则在电视菜单里填 `http://<NAS IP>:8899`，并自行放行防火墙 / 给该网段配直连规则。

接入真实识别 / 翻译（默认 `SUBTITLE_PROVIDER=demo`）：

```bash
export SUBTITLE_PROVIDER=cloud
export SUBTITLE_ASR_URL=https://<厂商>/v1/audio/transcriptions
export SUBTITLE_ASR_KEY=...
export SUBTITLE_ASR_MODEL=FunAudioLLM/SenseVoiceSmall
export SUBTITLE_MT_URL=https://<厂商>/v1/chat/completions
export SUBTITLE_MT_KEY=...
export SUBTITLE_MT_MODEL=qwen-mt-turbo
python3 subtitle-service/server.py
```

也可设 `SUBTITLE_PROVIDER=whisper` 在本机跑 faster-whisper（CPU int8；翻译端点仍建议配置）。国内云 ASR / MT 可直连，不依赖代理出口。

诊断开关：`SUBTITLE_ADB_REVERSE=0`（关闭自动端口转发）、`SUBTITLE_FORCE_URL=/path/to/test.wav` 让服务端忽略电视传来的 URL、循环读本地音频，用于在没有可用直播流的环境验证链路；`SUBTITLE_CHUNK_SEC`（默认 2.0）、`SUBTITLE_VAD_RMS`（默认 120）、`SUBTITLE_CUE_MS`（默认 3200）、`SUBTITLE_PORT`（默认 8899）可调。

已知限制：

- MVP 字幕内容为演示样例，界面标注「演示模式」；识别与翻译质量取决于后续接入的 provider。
- 时钟对齐采用 `pts + 可调偏移`（默认 -1500ms），不同源的缓冲差异需要用「字幕时间偏移…」现场微调；精确 PTS 对齐留到接真实 ASR 后迭代。
- 每个正在观看的频道会多拉一路流（双份带宽），只给当前频道开，不要全表启用。
- 音乐台多为 BGM，ASR 会乱识别，建议用「仅外语频道」范围；央视 / 卫视 / 港澳台本身是中文，无需开启。
- 转播内容的机器翻译字幕可能错译，界面标注「AI 实时字幕」，请自行判断并合规使用。

## 免责声明

内置频道清单来自公开的社区 IPTV 项目（Guovin/iptv-api、vbskycn/iptv、iptv-org 等），仅供学习研究，请支持正版内容。

## License

来自北京朝阳宇宙无敌帅 刘嘉尔🇨🇳😎
