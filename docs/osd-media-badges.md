# OSD 媒体信息徽标（osd-media-badges）

**Recovery anchor**

- 目标：播放器**唤醒控制栏时**显示当前媒体的**可证事实**徽标（分辨率 / HDR / 视频编码 / 帧率 / 码率 / 音频编码+声道+采样率 / 容器），**同一行横排**，每条为「徽标牌位图或本地矢量图标 + 文本 + 类别色胶囊」；开关进「屏显设置」多选（默认关），位置独立成行四角单选（默认右下角）。
- 验收：默认开启后不显示任何徽标；打开开关且媒体信息可解析时，在所选角落按类别色胶囊横排显示徽标；Exo/MPV/IJK 三引擎均不崩溃。（第三轮起按用户明确授权打包 18 张现成徽标位图，其中含 Dolby / DTS 等注册商标图形，属于**用户已知并接受**的取舍。）
- Lane/scope：task_guard `osd-media-badges`（mode=standard），base HEAD `4dda4b56b8d67e0cb78e6617f7b0f776591383a9`，branch `dev1`。
- 回滚：单一逻辑提交，回滚该提交即可完整撤销；新增键 `player_osd_media_badges` / `player_osd_media_badge_position` 未被引用时是惰性偏好项，不会影响旧版本行为。
- 后续任务：`osd-badge-icons`（base HEAD `49e5b2c4bc05a5e1e05f2bc18327d1c02079b9f5`）把徽标从「常驻屏显」改为「控制栏可见时展示」并加图标；`osd-badge-plates`（base HEAD `7d4bee6ce1`）改为横排 + 类别色 + 现成徽标牌。

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

- **（第一轮决策，第三轮经用户授权推翻）** 原定不引入任何第三方图片素材：社区图标集（含 "4K ULTRA HD"、"Dolby Vision" 字标）属于各项目自有或商标方所有，直接打包有许可与商标风险。第三轮用户明确表示**忽略许可证顾虑**，要求「用主流现成图标」，因此改为打包 18 张现成徽标位图（仍不引入任何新依赖、运行时零网络请求）。若将来需要回到零第三方素材的方案，删除 `drawable-nodpi/badge_*.webp` 并让 `plateResource(...)` 返回 0 即可退回纯矢量兜底路径。
- DOLBY、"4K ULTRA HD" 等是注册商标 / 认证字标。本实现只用**中性技术缩写**（`4K`、`1080P`、`HDR10`、`HLG`、`DV.P8`、`HEVC`、`H.264`、`E-AC-3`）做指示性描述，属于对播放内容的客观说明，不做官方认证暗示。
- 视觉分两层：
  - **有对应牌的事实**（分辨率 / 动态范围 / 视频编码 / 音频编码）→ 直接显示**现成媒体徽标位图**，见下一节。
  - **没有对应牌的事实**（帧率 / 码率 / 容器）→ 10dp 自绘单色矢量图标（`ic_badge_frame_rate.xml` / `ic_badge_bitrate.xml` / `ic_badge_container.xml`）+ 10sp 白色大写文字。
- 两类 chip 共用同一胶囊底色：`PlayerOsdController.categoryBackground(Icon)` 在建运行时生成 `GradientDrawable(LEFT_RIGHT, {withAlpha(categoryColor, 0x8A), 0xD9040A12})` + `setCornerRadius(dp(4))` + 1dp 描边 `withAlpha(categoryColor, 0xE6)`；类别色见 `mediaBadgeColor(Icon)`（分辨率 `#5A8DEE` 蓝 / 动态范围 `#7B61FF` 紫 / 视频编码 `#14B8A6` 青 / 帧率 `#10B981` 绿 / 码率 `#F59E0B` 橙 / 音频 `#EF4444` 红 / 容器 `#64748B` 灰）。旧的 `shape_player_osd_badge.xml` 静态背景已不再使用（文件保留，避免影响既有引用）。

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
| `app/src/main/java/com/fongmi/android/tv/player/MediaBadgeResolver.java` | 纯逻辑：`Format` + `VideoPlaybackDetails` + `AudioPlaybackDiagnostics.Track` + url → `List<MediaBadge>`（`public record MediaBadge(String text, Icon icon, String plate)`，`enum Icon {RESOLUTION,DYNAMIC_RANGE,VIDEO_CODEC,FRAME_RATE,BITRATE,AUDIO,CONTAINER}`），顺序 分辨率→动态范围→视频编码→帧率→码率→音频→容器，去空去重；音频编码经 `AudioPlaybackDiagnostics.codecLabel` 归一，`mp4a.40.x` → `AAC`；`private record Plate(String key, List<String> labels)` + `stripPlate(...)` 把「牌图已经写明的事实」从文本里剥掉（4K 牌 + "4K" 文本 → 文本变空），DV 牌只写 "Dolby Vision" 故 `DV.P8` 保留为文本 |
| `app/src/test/java/com/fongmi/android/tv/player/MediaBadgeResolverTest.java` | 新增。11 个纯 JVM 用例（4K/HDR10、DV.P8 取代 HDR10、整数/小数帧率、HLG、SDR 省略、音频缺字段、容器 mime、HLS/DASH 不产容器、扩展名兜底、全空、去重） |
| `app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java` | 新增键 `player_osd_media_badges`（默认 false）与 `player_osd_media_badge_position`（默认 3=右下）；`getDisplayChecked()` 变 8 元素、`putDisplayChecked()` 追加索引 7；`isOsdEnabled()` 计入徽标 |
| `app/src/main/java/com/fongmi/android/tv/bean/Backup.java` | 两个新键加入 `APP_PREFS`，否则备份/恢复丢设置 |
| `app/src/main/res/layout/view_player_osd.xml` | 内层 FrameLayout 新增 `FlexboxLayout @+id/osdMediaBadges`（默认 gone、`layout_gravity=bottom\|end`、`flexDirection=row` + `flexWrap=wrap` + `justifyContent=flex_end` + `alignItems=center` → **同一行横排、放不下才换行**）；4 个既有 TextView 的 id/属性未动 |
| `app/src/main/res/drawable-nodpi/badge_*.webp` | 新增 18 张现成媒体徽标位图（合计 97,058 字节）：`badge_resolution_{4k,1440p,1080p,720p}`、`badge_visual_{dolby_vision,hdr10,hdr,hlg}`、`badge_encoder_{hevc,avc,av1}`、`badge_audio_{aac,flac,opus,dolby_digital,dolby_digital_plus,dolby_truehd,dts}`；放在 `-nodpi` 保证不随屏幕密度缩放 |
| `app/src/main/res/drawable/ic_badge_*.xml` | 保留 7 个 24dp 单色矢量图标；其中 **frame_rate / bitrate / container 三个仍作为「无牌事实」的兜底图标**（`mediaBadgeIcon(Icon)`），其余四个在有牌时不再使用；运行时 `setColorFilter(0xFFFFFFFF)` 着白 |
| `app/src/main/java/com/fongmi/android/tv/ui/custom/PlayerOsdController.java` | 构造内 `root.findViewById(R.id.osdMediaBadges)`（**构造签名不变**）；`controlsVisible` 分支调 `setMediaBadges(player)` 并计入 `hasVisible`；`suppressed && controlsVisible`（mobile 播放器抑制 OSD 根）走 `renderMediaBadgesOnly()` 只放行徽标；常驻路径与 `hidePersistent()` 一律隐藏徽标；`createMediaBadge(MediaBadge)` **三形态**：有牌 → `ImageView`（`wrap_content` × `dp(16)`、`setAdjustViewBounds(true)`、`ScaleType.FIT_CENTER`、`plateResource(plate)`）；无牌 → 通用矢量图标 `dp(11)` 着白；有文本才追加 10sp 白字 `TextView`。全部套 `categoryBackground(Icon)` 动态胶囊；签名串 `icon:plate:text|`，只有签名变化才重建子 View；`applyMediaBadgePosition()` 设四角 gravity 并按控制栏高度内缩（上 64dp / 下 80dp） |
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

- 徽标**只在控制栏可见时渲染**；控制栏隐藏后常驻屏显不再显示徽标（与标题/时间/进度的常驻语义相互独立）。
- 四个角位都会从控制栏占用的区域**内缩**（上两角 `topMargin=64dp`，下两角 `bottomMargin=80dp`）：徽标只在控制栏可见时出现，而控制栏本身压在画面顶部与底部，不内缩时右上角会与顶栏按钮列、底部进度条重叠。内缩量按 48dp 按钮行加行高估算，小窗与全屏一致。
- mobile 播放器在 `showControl()` 里先 `setOsdSuppressed(true)` 隐藏整个 OSD 根，因此徽标走 `renderMediaBadgesOnly()` 这条独立放行路径；leanback 与 TMDB 内联控件由 `controlsVisible` 分支统一处理。
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
- 位置四角（`player_osd_media_badge_position` → `@id/osdMediaBadges` bounds；小窗画面 `@id/control`=[42,70][964,588]，顶栏 70–182、底部 seek 509–555；竖排一列 251×216px）：
  `0` 左上 `[67,207][318,423]`、`3` 右下 `[688,207][939,423]`（默认）。上两角下移 64dp、下两角上移 80dp，均不再压住顶栏按钮或进度条。
- 设置页 `@id/osdMediaBadgePosition` 行 → `ChoiceDialog` 四角单选，选中后行值即时更新并落盘对应 `int`。
- 徽标内容实测（竖屏 H.264/AAC 样片，竖排一列、每条左侧 10dp 矢量图标）：`656X1280` / `H.264` / `29.94FPS` / `3.1MBPS` / `AAC 2.0 44.1KHZ 64KBPS` / `MP4`。`mp4a.40.2` 已归一为 `AAC`（改造前显示原始 codec 串）。
- 控制栏自动隐藏后再 dump，只剩 `@id/osd`，**没有 `@id/osdMediaBadges` 节点** → 「只在控制栏可见时显示」语义成立；徽标子 View 不消费触摸，覆盖区域下的 `@id/setting` 仍可点击。
- 全屏下（`@id/control`=[0,0][1920,900]、顶栏 0–112、底部 seek 759–805）位置 `1`（右上）徽标 `[1644,519][1895,735]`，与进度条不再重叠。

### 第三轮：现成徽标牌 + 横排炫彩（task_guard `osd-badge-plates`，base HEAD `7d4bee6ce1`）

用户要求：徽标改「同一行横排 + 炫彩配色 + 用主流现成图标」（并明确授权忽略许可证问题）。

- 布局：`view_player_osd.xml` 的 `osdMediaBadges` 从竖排一列改回 `row` + `wrap` + `justifyContent=flex_end`。
- 视觉：`PlayerOsdController` 新增 `categoryBackground(Icon)` / `mediaBadgeColor(Icon)` / `plateResource(String)` / `SVG 兜底 mediaBadgeIcon(Icon)`；chip 背景改为按类别着色的半透明渐变胶囊 + 同类色描边。
- 牌图：18 张 `drawable-nodpi/badge_*.webp`；`MediaBadgeResolver` 增加 `plate` 字段与 `Plate` 表，牌已写明的事实在文本里去重（`4K` 牌不再跟 `4K` 文本，`Dolby Vision` 牌保留 `DV.P8` 文本）。
- 单测：`MediaBadgeResolverTest` 扩到 15 用例，含 `plateBadgesDoNotRepeatTheirOwnText`、`audioBadgeWithoutAPlateKeepsTheCodecAsText`、`mp4aCodecIsShownAsAac`。
- 设备实测（emulator-5554，1920×1080，sdk28，zh-CN，ARM 转译）：`@id/osdMediaBadges` bounds `[119,383][939,423]`（宽 820 高 40，**一行**），子节点依次 `656X1280` / AVC 牌 / `29.94FPS` / `3.1MBPS` / `AAC 2.0 44.1KHZ 64KBPS` / `MP4`；截图目视确认 6 个徽标横排、类别色胶囊描边、AVC 与 AAC 用内置品牌牌、其余为兜底图标 + 文本。
- 构建：`:app:testMobileArm64_v8aDebugUnitTest --tests *MediaBadgeResolverTest* --tests *PlayerDisplaySettingSyncTest* --tests *ControlDialogOsdSettingsTest*` 与 `:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 均 BUILD SUCCESSFUL；`:app:assembleMobileArm64_v8aDebug` 产出 APK 201,466,985 字节，`adb install -r -t` 成功。

- 既有回归守卫（必须保持通过）：`PlayerDisplaySettingSyncTest`（数组顺序、设置页偏好、四角白色文本、persistentSuppressed 顺序、Backup 键）、`ControlDialogOsdSettingsTest`（两 flavor 芯片 id 与 `getDisplayChecked()`/`putDisplayChecked(checked)`、live 5 项、`onDisplayChanged()` 重启）。
