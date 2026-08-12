# 横屏播放崩溃问题分析与修复

> **本分支仅作存档，不要合入 dev / main。** 文档中「已实施的宿主侧修复」那套实现经高强度评审未通过，10 条缺陷见文末《评审结论》。分析部分（根因、证据、责任边界）是可信的，代码部分不是。

- 分析日期：2026-08-12
- 崩溃版本：5.5.6-202608101555（崩溃时间 2026-08-11 21:09:50）
- 设备：Xiaomi 24117RK2CC，Android 16（SDK 36）

## 结论

动态加载的外部 CatVod 蜘蛛 JAR 在布局回调里对长度 4 的字符串执行 `substring(7)`，抛出未捕获异常直接杀掉进程。横屏只是触发条件，不是根因；与 Exo / IJK / MPV 解码内核无关。

置信度：高。

## 截图证据

```text
java.lang.StringIndexOutOfBoundsException: length=4; index=7
  at java.lang.String.substring(String.java:2499)
  at com.github.catvod.spider.merge.O0O0o0OOo0oO0oO0o.o0OOO0oO0oOoO0O0oO.onGlobalLayout(
     r8-map-id-750c34238994c5baee8bce6d3d4694ef2ecdecb0794b67116148894cc4380b30:740)
  at android.view.ViewTreeObserver.dispatchOnGlobalLayout(ViewTreeObserver.java:1145)
  at android.view.ViewRootImpl.performTraversals(ViewRootImpl.java:5541)
```

三条关键信息：

1. 崩溃类在 `com.github.catvod.spider.merge` 包下，包名 `O0O0o0OOo0oO0oO0o`、类名 `o0OOO0oO0oOoO0O0oO` 是混淆产物。
2. SourceFile 是 `r8-map-id-<sha256>`，说明这个 JAR 自身是用 R8 混淆构建并保留了行号表。宿主的 `proguard-rules.pro` 没有 `-repackageclasses` / `-flattenpackagehierarchy`，不会把自己的类改名到这个包下；`catvod` 模块也没有 `spider` 子包。所以这个类只可能来自外部 JAR。
3. `dispatchOnGlobalLayout` ← `performTraversals`：异常发生在系统布局遍历阶段，不在宿主任何 `try/catch` 的作用域内。

因果链：

```text
点击全屏 → 宿主改布局并请求横屏 → 系统重新测量布局 → dispatchOnGlobalLayout
→ JAR 注册的监听器执行 → 对长度 4 的字符串 substring(7) → 主线程未捕获异常 → 进程崩溃
```

## 宿主代码位置

- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` `enterFullscreen()`：改容器尺寸并 `setRequestedOrientation(...)`。
- `app/src/main/java/com/fongmi/android/tv/playback/PlaybackOrientation.java`：横向视频返回 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。
- `app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` `onConfigurationChanged()`：方向变化后重新应用布局，触发新的布局遍历。

这三处本身没有 `substring(7)`，是触发者而非缺陷方。

`JarLoader.getSpider()` 的 `try/catch` 只覆盖类加载和 `spider.init(...)` 同步执行期间；JAR 在 init 时注册的 `OnGlobalLayoutListener` 稍后由系统调用，已经离开该作用域，所以异常直接冒泡到主线程，由 `Startup` 注册的 CustomActivityOnCrash 展示为错误页。

## 未能取到的证据

本地 4 台模拟器缓存的 9 个蜘蛛 JAR 已用 `dexdump` 逐个扫描：6 个含 `onGlobalLayout`，但混淆字典是 `merge/A/o` 风格，且全部没有行号表、没有直接调用 `substring`。崩溃 JAR 是另一个（O/o 字典的）版本，只存在于真机上。要反编译定位第 740 行的原始代码，需要用 `adb pull` 从 Xiaomi 24117RK2CC 二进制导出 `cache/jar/*.jar`。

## 已实施的宿主侧修复

外部 JAR 不在本仓库，无法直接修第 740 行。宿主侧做两件事：

### 1. 主线程崩溃拦截（`SpiderCrashGuard`）

在主线程消息泵外层套一层 `while (true) { try { Looper.loop(); } catch (...) }`。这是唯一能拦到布局回调异常的位置。

只有当异常能归因到某个蜘蛛 JAR 时才吞掉并继续跑，否则原样抛出、照旧走崩溃页。归因由 `SpiderJarRegistry` 完成：遍历异常链的每个栈帧类名，问每个 `CspDexClassLoader` 的 `findLoadedClass`。`findLoadedClass` 只对该 loader 自己定义的类返回非空，父加载器代理过来的宿主类不会命中，所以不需要按包名前缀猜（宿主和 JAR 共用 `com.github.catvod` 前缀，按名字判断会误判）。

代价：吞掉布局阶段的异常后，该次布局可能处于不一致状态。换来的是横屏不再杀进程。

### 2. 重复出事的 JAR 自动隔离（`SpiderIsolation`）

按 JAR 指纹累计吞掉的故障次数，存在 `Prefers` 的 `spider_fault_<md5>` 下。达到 5 次后 `JarLoader.parseJar()` 直接跳过该 JAR（连下载都不做），`getSpider()` 随之返回 `SpiderNull`，只影响这一个源。计数写入在到阈值后停止，故障风暴不会反复写盘。

恢复入口：设置里的「清理缓存」。`Path.jar()` 在 `Path.cache()` 下，所以清缓存同时删掉 JAR 文件并调用 `SpiderIsolation.reset()` 清空隔离标记。

### 涉及文件

- 新增 `app/src/main/java/com/fongmi/android/tv/api/loader/SpiderCrashGuard.java`
- 新增 `app/src/main/java/com/fongmi/android/tv/api/loader/SpiderJarRegistry.java`
- 新增 `app/src/main/java/com/fongmi/android/tv/api/loader/SpiderIsolation.java`
- 修改 `CspDexClassLoader.java`（暴露 `loadedClass`）、`JarLoader.java`（注册 / 跳过隔离 JAR）、`Startup.java`（安装守卫）、`FileUtil.java`（清缓存时重置隔离）
- 新增 `app/src/test/java/com/fongmi/android/tv/api/loader/SpiderCrashGuardWiringTest.java`

## 真机验证步骤

1. 同一 `csp_merge` 站点、同一视频，竖屏播放后点全屏：应不再进错误页，日志出现 `spider-guard main thread fault swallowed jar=...`。
2. 反复横竖屏切换 5 次以上：该 JAR 应被隔离，日志出现 `parse skip isolated key=...`，其他源不受影响。
3. 设置里「清理缓存」后重新进站：JAR 重新下载，隔离解除。
4. 制造一个与蜘蛛无关的主线程异常（如宿主代码空指针）：必须仍然进崩溃页，确认守卫没有把宿主自己的 bug 也吞掉。

## 彻底修复的责任边界

- 真正的缺陷在 `com.github.catvod.spider.merge` 第 740 行附近，应改成带语义校验的前缀剥离（`startsWith(prefix)` 后再 `substring(prefix.length())`），并在 View 销毁时移除 `OnGlobalLayoutListener`。
- 这需要 JAR 提供方修复，或用户更换 / 回退配置里的蜘蛛 JAR。宿主侧只能做到不因此崩溃、并隔离反复出事的 JAR。

## 评审结论（未通过，勿合入）

评审日期 2026-08-12。以下 4 条已在本仓库逐一核实。

### 致命：归因机制不成立

`SpiderJarRegistry.owner()` 用 `CspDexClassLoader.findLoadedClass(name)` 判断某个栈帧类是否属于该 JAR，代码注释写的是「只对该 loader 自己定义的类返回非空」——与契约相反。`ClassLoader.findLoadedClass` 的语义是 **initiating loader**，不是 defining loader：JAR 代码引用过的 `java.lang.String`、`android.view.View` 等都会记在该 loader 名下。ART 的 `VMClassLoader_findLoadedClass` 查表落空后还有一条 `FindClassInBaseDexClassLoader` 快速路径会沿父链真正加载，进一步放大误判。

后果：只要注册过任意一个 JAR，`attribute()` 从第一帧起就命中，`SpiderCrashGuard` 里 `if (jarKey == null) throw e;` 实质是死代码。守卫退化成无条件吞掉一切主线程异常——宿主自己的崩溃报告全部丢失，文档「验证步骤 4」永远不成立。

即便按最窄读法也仍歧义：每个 JAR 都定义了同名的 `com.github.catvod.spider.Init` / `Proxy`，同源构建的 JAR 还共享 `merge.*` 混淆名，`owner()` 按 HashMap 哈希顺序返回第一个命中者，故障会记到无辜 JAR 上。`StackTraceElement` 无法反推 defining loader（这些 loader 匿名，`getClassLoaderName()` 返回 null），所以按类名归因是构造性歧义。

### 隔离粒度错误：不是「只影响一个源」

`Site.objectFrom()` 里 `if (site.getJar().isEmpty()) site.setJar(spider);`——配置里未单独指定 jar 的站点全部共用配置级 JAR，隔离键是它的 md5。隔离它等于废掉全部 csp 站点。而 `BaseLoader.parseJar()` 在 `jarLoader.parseJar` 跳过后仍无条件 `setRecent(key)`，于是 `requireRecentLoader()` 对每次 JSON 聚合解析抛 `IllegalStateException`。

### 反向禁用既有逃生阀

吞掉异常后 `CrashActivity` 不再启动，而 `Prefers.put("crash", true)` 只有它会写。`SiteApi` 里「上次崩过就跳过一次 spider 首页」的既有保护（`Prefers.getBoolean("crash")`）从此永不触发。

### 隔离状态会被备份带到其它设备

`Backup.create()` 全量导出 `Prefers.getPrefers().getAll()`，`filter` 对未知 key 落到 `return options.isSpider();`，`spider_fault_*` 因此进备份并被恢复到其它设备——那台设备上该源开箱即废；用户本机「清理缓存」解除隔离后，下次恢复又写回来。同仓库的 `ExoTunnelingRuntimeState` 刻意只放进程内 map，正是为避免这个。

### 其余已记录缺陷

- `while (true) { Looper.loop(); }` 在 `loop()` 正常返回（`mQuitting`）时没有出口，退化成无阻塞点的主线程自旋 → 冻屏 + ANR。AOSP 自己把正常返回当异常事件处理。
- 隔离检查排在 `if (loaders.containsKey(key)) return;` 之后，且没有任何地方从 `loaders` / `spiders` 摘掉已加载的 JAR，所以阈值达到后本进程内毫无效果，要等 `clear()` 或重启。文档真机验证第 2 步在同一进程内无法复现。
- `JarLoader.clear()` 清空注册表时 JAR 注册在 ViewTreeObserver 上的监听器还活着——守卫在最该起作用的场景（切配置后旋转）失效并崩溃。`loaders.put` 与 `register` 非原子，交错时静态注册表会成为 `CspDexClassLoader` 的唯一强引用，泄漏整份 dex。
- `catch (Throwable)` 连 OOM / StackOverflowError 一起吞；catch 体本身无保护，`Prefers.put` 是 `Prefers` 里唯一没有异常包裹的方法，低内存下会二次 OOM 并顶掉真正的故障。
- cause 链遍历无环检测、无深度上限；异常来自不可信 JAR，`getStackTrace()` 返回 null 会直接 NPE。同仓库其它 cause 遍历都有 visited 集合或限深。
- 吞掉异常但不摘监听器、不重建视图：异常从 `performDraw()` 之前逃出，该帧永不绘制，用户从「崩溃页 + 可重启」变成「冻屏 + 无任何信息」。两条 `SpiderDebug.log` 在调试日志默认关闭时是空转。
- `SpiderCrashGuardWiringTest` 全部是源码文本 `contains` 和字符下标顺序断言，任何无害重排都会红，且发现不了以上任何运行时缺陷——它断言的 `findLoadedClass` 归因方式恰恰就是第一条缺陷本身。

### 下一步建议

宿主侧若要做，应换成零风险的诊断增强：把站点 key / api / JAR 指纹写进崩溃页，让用户知道该换哪个源；不吞异常、不隐式禁用。彻底修复仍在 JAR 提供方。

