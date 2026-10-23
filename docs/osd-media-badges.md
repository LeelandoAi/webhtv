# OSD 媒体信息徽标（osd-media-badges）

**Recovery anchor**

- 目标：播放器屏显能反射当前播放媒体的**可证事实**（分辨率 / HDR / 视频编码 / 帧率 / 码率 / 音频编码+声道+采样率 / 容器），并入屏显 OSD；开关进「屏显设置」多选（默认关），位置独立成行四角单选（默认右下角）。
- 验收：默认开启后不显示任何徽标；打开开关且媒体信息可解析时，在所选角落显示自绘 chip；Exo/MPV/IJK 三引擎均不崩溃、不新增图片资源、不引入任何商标图。
- Lane/scope：task_guard `osd-media-badges`（mode=standard），base HEAD `4dda4b56b8d67e0cb78e6617f7b0f776591383a9`，branch `dev1`。
- 回滚：单一逻辑提交，回滚该提交即可完整撤销；新增键 `player_osd_media_badges` / `player_osd_media_badge_position` 未被引用时是惰性偏好项，不会影响旧版本行为。
- 下一步：跑 `:app:testMobileDebugUnitTest --tests "*MediaBadgeResolverTest*" --tests "*PlayerDisplaySettingSyncTest*"` 与两 flavor 编译，然后 task_guard finish + recovery tag。

## 1. 需求与取舍

用户参考实现（截图）：播放器片上叠加 "4K ULTRA HD / WEB-DL NO RE-ENCODE / Dolby Vision / E-AC-3 5.1 48kHz / MKV / CHS 简中" 一套徽标，并要求"匹配媒体徽标""媒体徽标来自哪里""看是放到屏显中还是独立设置"。

结论分三档（用户已确认"接受只显示可证事实""允许共存"）：

| 档位 | 内容 | 本项目处置 |
| --- | --- | --- |
| 可证事实 | 分辨率、HDR/DV、视频编码、帧率、码率、音频编码/声道/采样率、容器 | 默认显示，构成本次实现 |
| 半可证 | HLS/DASH/网盘等流类型 | 不显示（避免误导，且 manifest 无容器语义） |
| 不可证 | WEB-DL / REMUX / BluRay / 压制组 / 简繁字幕标签 | **放弃** |

放弃发布标签的理由：本项目片源来自爬虫直链 / HLS / 网盘，`containerMimeType` 常为 `application/x-mpegURL`，硬猜 WEB-DL/REMUX 是编造事实；字幕语言要从 `Sub` 取且与"媒体信息"语义不同，另议。

## 2. 数据来源：为什么不能来自 TMDB

核查 `TmdbService.java`：TMDB `append_to_response` 只提供 images / credits / translations / release_dates / videos 等元数据，**没有任何码率、编码器、声道、采样率、容器字段**。因此：

- 徽标必须来自播放器运行时事实（`PlayerManager.getVideoFormat()` / `getVideoPlaybackDetails()` / `getAudioPlaybackDiagnostics()` / `getUrl()`）。
- 截图那套图标其实是社区 media-info 图标集（Kodi / Emby / Jellyfin / Kometa overlay 同源），混杂了"发布标签"和"流事实"两类信息，不能整体照搬。

## 3. 图标与商标

- **不引入任何第三方图片素材**：社区图标集（含 "4K ULTRA HD"、"Dolby Vision" 字标）属于各项目自有或商标方所有；直接打包有许可与商标风险，也会增加包体。
- DOLBY、"4K ULTRA HD" 等是注册商标 / 认证字标。本实现只用**中性技术缩写**（`4K`、`1080P`、`HDR10`、`HLG`、`DV.P8`、`HEVC`、`H.264`、`E-AC-3`）做指示性描述，属于对播放内容的客观说明，不做官方认证暗示。
- 视觉用自绘 chip：`shape_player_osd_badge.xml`（`#66000000` 底 + 3dp 圆角 + `#33FFFFFF` 1dp 描边）+ 10sp 白色大写文字。零新增图片、零新增依赖。

## 4. 三引擎事实覆盖度

| 事实 | ExoPlayer | MPV | IJK |
| --- | --- | --- | --- |
| width/height、frameRate、codecs、bitrate、ColorInfo | ✅ `Format` | ✅ `TrackInfo.toFormat()`（codecs/mime/宽高/帧率/ColorInfo/采样率/声道/平均码率） | ⚠️ `IjkSimplePlayer.buildFormat()` 字段较少 |
| Dolby Vision profile | ✅ `getVideoPlaybackDetails()` | ✅ | ➖ |
| 音频 codec/声道/采样率/码率 | ✅ `getAudioPlaybackDiagnostics().activeTrack()` | ✅（`MpvPlayer`） | ✅ `getAudioPlaybackDiagnostics()` |
| `containerMimeType` | ✅ | ❌ 不设置 | ❌ 不设置 |

因此容器徽标的策略是：**先看 `containerMimeType`，为空或未知时再退回 URL 扩展名白名单**（mkv/mp4/m4v/webm/mov/avi/flv/ts/m2ts/wmv/mpg/mpeg/3gp/ogv）；`m3u8`/`mpd` 不在白名单内，所以 HLS/DASH 永远不会冒出容器徽标，宁缺毋滥。

## 5. 方案对比

1. **不改**：屏显已有时间/网速/分辨率/进度/迷你/标题/诊断，缺"编码-码率-音轨"这一层信息；用户明确要求补，且这是排查"为什么糊/为什么没声音"的第一手线索。
2. **照搬社区徽标方案**（打包整套 PNG/SVG 图标集 + 发布标签）：包体增加、许可与商标风险、且发布标签在本项目片源上不可证 → 拒绝。
3. **窄适配（选中）**：运行时事实 + 自绘 chip + 可开关 + 可选角落。可证、可测（纯 JVM 单测）、零图片、可回滚。

## 6. 实现落点

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/fongmi/android/tv/player/MediaBadgeResolver.java` | 新增。纯逻辑：`Format` + `VideoPlaybackDetails` + `AudioPlaybackDiagnostics.Track` + url → `List<String>`，顺序 分辨率→动态范围→视频编码→帧率→码率→音频→容器，去空去重 |
| `app/src/test/java/com/fongmi/android/tv/player/MediaBadgeResolverTest.java` | 新增。11 个纯 JVM 用例（4K/HDR10、DV.P8 取代 HDR10、整数/小数帧率、HLG、SDR 省略、音频缺字段、容器 mime、HLS/DASH 不产容器、扩展名兜底、全空、去重） |
| `app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java` | 新增键 `player_osd_media_badges`（默认 false）与 `player_osd_media_badge_position`（默认 3=右下）；`getDisplayChecked()` 变 8 元素、`putDisplayChecked()` 追加索引 7；`isOsdEnabled()` 计入徽标 |
| `app/src/main/java/com/fongmi/android/tv/bean/Backup.java` | 两个新键加入 `APP_PREFS`，否则备份/恢复丢设置 |
| `app/src/main/res/layout/view_player_osd.xml` | 内层 FrameLayout 新增 `FlexboxLayout @+id/osdMediaBadges`（第 5 个 overlay 元素，默认 gone、`layout_gravity=bottom\|end`）；4 个既有 TextView 的 id/属性未动 |
| `app/src/main/java/com/fongmi/android/tv/ui/custom/PlayerOsdController.java` | 构造内 `root.findViewById(R.id.osdMediaBadges)`（**构造签名不变**，3 个调用点与既有源码断言不受影响）；render() 在 `setMiniProgress` 之后调 `setMediaBadges(player)`，`controlsVisible` 与 `hidePersistent()` 分支隐藏；按 `TextUtils.join("|", badges)` 签名缓存，只有签名变化才重建子 View，不给 1 秒轮询增负 |
| `app/src/main/res/drawable/shape_player_osd_badge.xml` | 新增自绘 chip 背景 |
| 三语 `strings.xml` | `display_media_badges`、`osd_media_badge_position`、`select_media_badge_position` 四角数组；`select_player_osd` **末尾**追加第 8 项（既有 6 项顺序保持不变） |
| 两设置页布局 + `SettingPlayerActivity` / `SettingPlayerFragment` | `@+id/osdMediaBadgePosition` 行 + `ChoiceDialog.showSingle` 四角单选（leanback 保留 focusable 以支持遥控） |
| `app/src/main/res/layout/dialog_display.xml` | 新增 `@+id/display_media_badges` 芯片（`@style/DisplayControl`，默认 gone，仅 `Mode.PLAYER_OSD` 显示） |
| 两 flavor `app/src/{leanback,mobile}/res/layout/dialog_control.xml` | OSD 芯片行追加 `@+id/displayMediaBadges`（`@style/ControlSheetButton`） |
| `app/src/main/java/com/fongmi/android/tv/ui/dialog/DisplayDialog.java` | `enum Display` 加 `MEDIA_BADGES`、`setDisplay` switch 写 `putDisplayMediaBadges`、`initPlayerOsdView()`/`initPlayerOsdEvent()` 接线 |
| 两 flavor `app/src/{leanback,mobile}/java/.../ui/dialog/ControlDialog.java` | `displays` 列表末尾追加 `binding.displayMediaBadges`；leanback 另把该芯片加入 `bindRemoteFocus()` 焦点链 |

复用既有设施，不新增依赖：屏显渲染走 `PlayerOsdController.render()` 既有多分支结构；位置单选走既有 `ChoiceDialog.showSingle`；设置持久化走既有 `Prefers`；多选开关走既有 `PlayerOsdDialog`（`select_player_osd` 驱动，`getItemCount() = min(items.length, checked.length)`，8 项 + 8 个 checked 自然渲染）。

## 7. 开关入口（三个，全部等价）

| 入口 | 载体 | 文案 |
| --- | --- | --- |
| 设置 → 屏显设置（多选） | `select_player_osd` 第 8 项 → `PlayerSettingsRow` 开关 | 「媒体信息」（zh-rCN）/「媒體資訊」（zh-rTW）/「Media info」 |
| 播放控件 → 屏显芯片弹窗（TV/手机） | 两 flavor `ControlDialog` 第 8 个芯片 `displayMediaBadges` | 同上 |
| TMDB 详情页内联控件 → 屏显芯片弹窗 | `DisplayDialog`（`Mode.PLAYER_OSD`）`display_media_badges` | 同上 |

三者都读写同一对键（`player_osd_media_badges` + `player_osd_media_badge_position`），`ControlDialog.toggleDisplaySetting(index)` 与 `DisplayDialog.setDisplay(...)` 都汇入 `PlayerSetting.putDisplayChecked(...)` / `putDisplayMediaBadges(...)`，随后调用 `onDisplayChanged()` / `inlineControlDialogDisplayChanged()`，由 `PlayerOsdController.render()` 下一次轮询生效——因此三个入口互不冲突，也不会出现偏移一位的索引错位（`getDisplayChecked()` 与 `displays` 列表均按同一顺序追加到末尾）。

## 8. 已知缺口（有意为之）

- 徽标只在屏显「控制栏隐藏」的常驻路径渲染；打开控制栏时按既有设计整体隐藏（与标题/时间/进度一致）。
- 徽标与标题/分辨率/时间**允许同角共存**（用户确认）：徽标容器是独立 `FlexboxLayout`，不占用四个既有 `MaterialTextView` 的位置，因此同角重叠时由 `layout_gravity` 决定层级，二者可能相互覆盖——这是有意的「零侵入」取舍，避免改动既有角标排版契约。
- 只显示可证事实：HLS/DASH 清单 URL（`.m3u8`/`.mpd`）永不产生容器徽标；MPV/IJK 引擎的 `Format` 不设 `containerMimeType`，其容器徽标只能来自 URL 扩展名白名单，网盘直链可能因此缺容器徽标。
- 不显示发布标签（WEB-DL / REMUX / 压制组）与字幕语言徽标：本源为爬虫直链/HLS/网盘，无法从运行时事实反推，硬猜即成编造。

## 9. 验证

- `./gradlew :app:testMobileArm64_v8aDebugUnitTest --tests "*MediaBadgeResolverTest*" --tests "*PlayerDisplaySettingSyncTest*" --tests "*ControlDialogOsdSettingsTest*"`
- `./gradlew :app:testLeanbackArm64_v8aDebugUnitTest --tests "*PlayerDisplaySettingSyncTest*" --tests "*MediaBadgeResolverTest*"`
- `./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac`
### 设备实测（emulator-5554，x86_64 + ARM 转译，1920×1080，sdk28，zh-CN）

- 第 8 个芯片：播放控件弹窗内 `resource-id=com.silent.android.webhtv:id/displayMediaBadges`、`text=媒体信息`，与 `displayTitle`/`displayParams` 同一行；点击后 `selected` false→true，prefs 落盘 `<boolean name="player_osd_media_badges" value="true" />`。
- 屏显设置多选摘要：勾选后 `@id/osdText` 显示「媒体信息」，证明 8 项 `select_player_osd` 与 8 元素 `getDisplayChecked()` 长度同步、无索引错位。
- 位置四角（`player_osd_media_badge_position` → `@id/osdMediaBadges` bounds，容器同宽 733px，left=67 / right=939）：
  `0` 左上 `[67,95][800,131]`、`1` 右上 `[206,95][939,131]`、`2` 左下 `[67,527][800,563]`、`3` 右下 `[206,527][939,563]`（默认）。
- 设置页 `@id/osdMediaBadgePosition` 行 → `ChoiceDialog` 四角单选，选中后行值即时更新并落盘对应 `int`。
- 徽标内容实测（竖屏 H.264/AAC 样片）：`656X1280` / `H.264` / `29.94FPS` / `3.1MBPS` / `MP4A.40.2 2.0 44.1KHZ 64KBPS` / `MP4`。

- 既有回归守卫（必须保持通过）：`PlayerDisplaySettingSyncTest`（数组顺序、设置页偏好、四角白色文本、persistentSuppressed 顺序、Backup 键）、`ControlDialogOsdSettingsTest`（两 flavor 芯片 id 与 `getDisplayChecked()`/`putDisplayChecked(checked)`、live 5 项、`onDisplayChanged()` 重启）。
