# PY-BRIDGE-DEPEND-01 Python 桥接依赖下载契约修复

## Recovery anchor

- 目标：修复「同一 py 源在 WebHTV 加载不了、在 OK影视 正常」的根因，并补齐同类桥接契约缺口。
- 验收标准：`getDependence()` 非空（如夸父.py 返回 `["requests"]`）的 py 源能在 WebHTV 正常初始化；依赖取回失败不再把整站降级为 `SpiderNull`；Java `app.callAttr("<name>")` 与 app.py 属性有自动回归校验。
- 车道 / 范围：`standard`；`chaquo/src/main/python/app.py`、`chaquo/src/main/java/com/fongmi/chaquo/Spider.java`、`chaquo/src/test/python/test_app_bridge_contract.py`、`docs/integration/extensions.md`、`docs/integration/spider.md`、本文件。
- 分支 / 基线：`dev4` @ `0bccba71c5f86818c99a44114d3f0d58e7989481`。
- 状态：三轮评审完成，全部必修与文档/去重意见均已落地并复验（49 项单测通过），随本次提交一起入库。证据版本归属见下方「验证」小节：单测与 `:chaquo:compileArm64_v8aDebugJavaWithJavac`（27s）为修复后复跑；模拟器端到端为修复前产物，本次修复只动 `catch` 子句、状态阈值、一处分支与注释，不改变其覆盖的行为。
- 下一步：`task_guard.sh finish` 提交 → 推送 `dev4` → 开 PR 到 `beta`。

## 现象与根因

用户报告：同一份 `夸父.py`（站点「网盘资源社」，需 cookie）在 WebHTV（本仓库）里加载不了，在 OK影视 里正常。

根因是 **Java↔Python 桥接契约破裂**，单一且已三重证实：

1. commit `935a479fc00`（2026-09-15，`fix: 简化 Python 蜘蛛源码加载`）把 `chaquo/src/main/python/app.py` 的源码加载责任从 Python 侧移到了 Java 侧 `Loader.source()`，**顺带删掉了 `def download(path, api)`**。
2. 但 `chaquo/src/main/java/com/fongmi/chaquo/Spider.java` 的 `init()` 仍然无条件调用 `app.callAttr("download", path, url)`，用于逐个取回 `getDependence()` 返回的依赖名。该调用点没有 try/catch。
3. 因此只要 `getDependence()` 返回非空列表，`init()` 就抛 `AttributeError: module 'app' has no attribute 'download'`；`PyLoader.getSpider()` 的 `catch (Throwable)` 把它吞成 `new SpiderNull()`，用户看到的是首页/分类/搜索全空，也就是「加载不了」。

触发条件是「`getDependence()` 非空」这一条，所以缺陷长期隐藏：绝大多数 py 站源直接继承 `base/spider.Spider`，基类默认返回 `[]`（`chaquo/src/main/python/base/spider.py:69-70`），永远走不到 `download()`。`夸父.py` 自带 `getDependence() { return ["requests"] }`，于是唯一被这条路径命中。

OK影视（FongMi 上游）的 `app.py` 仍保留 `download()`，所以同一脚本在那边正常。已安装 APK 内字节码对比证实：

| 壳子 | `assets/chaquopy/app.imy` 内 `app.pyc` | `strings app.pyc | grep -c download` |
| --- | --- | --- |
| OK影视 `com.fongmi.android.tv` 5.6.0 | 3348 字节 | 1（保留 `download`） |
| WebHTV `com.silent.android.webhtv`（修复前） | 4818 字节 | 0（已删除） |

同族缺口：`Spider.java` 还调用 `app.callAttr("manualVideoCheck")` 与 `app.callAttr("isVideoFormat", url)`，而 app.py 从来没有这两个模块级函数（`git log -S` 证实自始未存在）。它们只在 WebHome 嗅探路径被用到，且调用点外层有 try/catch 兜底到 `Sniffer.isVideoFormat`，所以不影响站点加载，但同为契约缺口，本次一并补齐。

## 改动

### 1. `chaquo/src/main/python/app.py`

- 恢复 `download(path, api)`，插在 `redirect()` 与 `str2json()` 之间：
  - `http(s)` 源：`redirect(api)` 手动跟随跳转（沿用既有单跳 10s 超时、最多 10 跳上限），`status_code >= 300` 抛 `ValueError('download failed (<code>): <api>')`，否则 `writeFile(path, rsp.content)`。
  - 带 scheme 的非 http 源（`file://…` 等）抛 `ValueError('download source is not http(s): <api>')`。
  - 不带 `://` 的非 http 源：`writeFile(path, str.encode(api))`，与该仓库对非 http 内联源的既有约定一致。
  - 错误响应体**不写盘**：否则会用 HTML 错误页覆盖已有的可用缓存，后续 `SourceFileLoader` 只会报一个误导性的 `SyntaxError`。`writeFile()` 本身是原子写（mkstemp + fsync + os.replace），抛错时旧文件保持不变。
  - 门槛为 `status_code >= 300`（第一轮评审修正，原为 `>= 400`）：`redirect()` 只在「跳转链走不下去」时返回 3xx，那种响应体是跳转占位而非依赖源码；只挡 4xx/5xx 会把占位写进缓存。
  - 带 scheme 的非 http 源直接报错（第一轮评审修正）：`://` 说明这个字符串不可能是脚本本身，写下去只会得到误导性的 `SyntaxError`。
  - 上述三条判定最终以代码为准（`chaquo/src/main/python/app.py` 的 `download()`）。
- 新增 `manualVideoCheck(ru)` / `isVideoFormat(ru, url)`，二者共同走 `optionalBool(ru, name, *args)`（第二轮评审抽取，消除同一「getattr → callable → bool」模板的两份拷贝）：与 `getDependence()` 同样的 `getattr` 兜底风格，未实现（或存在但不可调用）即返回 `False`（「无意见」），实现本身的异常仍然照常抛出。
- 调用方若是 Python 源自己直接调用 `download()`（而非桥接层取依赖），`FileNotFoundError`/`OSError` 等会照常抛出，不落入「只记日志」那条路径。

### 2. `chaquo/src/main/java/com/fongmi/chaquo/Spider.java`

- 新增 `import com.github.catvod.crawler.SpiderDebug;` 与 `private static final String TAG = "python-spider";`。
- `init()` 的依赖循环改为 `try { download(item + ".py"); } catch (Throwable e) { SpiderDebug.log(TAG, "depend download failed name=%s error=%s", item, e); e.printStackTrace(); } finally { close(item); }`。

理由：单个依赖取不到不应让整个站点降级成 `SpiderNull`（那正是用户看到的「加载不了」）。改为记日志继续初始化；真正需要该模块的源会在首次使用时抛 `ImportError`，比整站静默空列表更好定位。`finally` 保证 `PyObject` 仍被释放，与该类其它 `toStr/toBool` 的释放惯例一致。

两点由第一轮评审修正：
- `catch (Exception)` → `catch (Throwable)`，否则跨桥抛出的 `Error` 仍会逃逸成整站降级。
- 增加无条件的 `e.printStackTrace()`。`SpiderDebug.log()` 经 `DebugLogStore.acceptsTag()` 门控，而 `acceptsTag` 要求「调试日志」开关（`catvod/.../DebugLogStore.java:37-39`，默认关），只靠结构化日志会让「某个依赖被跳过」在默认配置下不可见——这与 `PyLoader.java:59-64` 已记录的理由相同。

### 3. `chaquo/src/test/python/test_app_bridge_contract.py`

- `_Response` 增加 `content` 字段，`_RequestsStub` 保持不变。
- 新增 `DownloadTest`：http 写正文、跟随跳转、非 http 写字符串、4xx 抛错且不创建目标、5xx 抛错、跳转链走不下去的 3xx 抛错、带 scheme 的非 http 源抛错、下载失败保留旧缓存、成功后目录里不残留临时文件。
- 新增 `ManualVideoCheckBridgeTest`：缺失实现返回 `False` 而不是 `AttributeError`；有实现时正确委派。
- 新增 `BridgeContractTest`：用正则 `\bapp\.callAttr\("([A-Za-z_][A-Za-z0-9_]*)"` 从 `chaquo/src/main/java/com/fongmi/chaquo/*.java` 提取全部桥接调用名，断言 app.py 每个名字都有可调用属性。正则限定 `app.` 前缀，避免误收 `Platform.java` 对 `java.android` 和 `Loader.java` 对 `webhtv_logging` 的 `callAttr`（它们是不同的 Python 模块）。
  - `REQUIRED` 只收录 `chaquo/.../Spider.java` 私有 `app` 句柄上的 18 个调用名，**不含** `spider`：后者出自 `chaquo/src/main/java/com/fongmi/chaquo/Loader.java:35` 的 `app.callAttr("spider", ...)`，是加载器建站入口而非 `Spider` 实例的桥接调用，且已被 `test_bridge_helpers_are_importable_by_remote_scripts` 以可导入性单独覆盖。
  - 已知限制（第二轮评审记录、暂不修改）：正则只校名字存在性、不校 `callAttr` 的参数个数（arity-blind）；补齐需解析 Java 实参个数并维护签名表，收益低于维护成本。
  - 第一轮评审修正：原实现「期望集合」完全由 Java 源码推导。那样一旦没人再调用某个名字（或桥接源码被搬走），集合本身会一起缩小甚至变空，本测试要防的回归反而测不出来。现改为显式契约集合 `REQUIRED`（含 `download`）——`test_every_java_call_attr_exists_in_app_py` 校验「Java 调用的都在 app.py 里」，`test_the_bridge_still_calls_every_contracted_name` 校验「契约里的名字 Java 都还在调」，并断言确实找到了 Java 源文件。
- 扩展 `test_bridge_helpers_are_importable_by_remote_scripts`，纳入 `download/manualVideoCheck/isVideoFormat`。

### 4. 文档

- `docs/integration/extensions.md`：第 34 条补依赖取回与失败语义；错误处理条目由「依赖下载失败会初始化失败」改为「只记日志并继续初始化」。两处都写明日志同时走 `python-spider`（调试日志的「网络请求」分类）与 Logcat，后者在未开调试日志时仍可见。
- `docs/integration/spider.md`：补 `app.download()` 的取回规则（`>= 300` 不覆盖旧文件、带 scheme 的非 http 源直接报错），并明确 **`cache/py` 不在 `sys.path` 上**——依赖文件只是落到缓存目录，脚本必须自己 `loadModule()/loadSpider()`；直接 `import requests` 走的是 Chaquopy 随包安装的 `requirements.txt`，与 `getDependence()` 无关。

## 验证

### 单元测试

```
& 'G:\Git\bin\bash.exe' -c 'cd /f/Workspace/webtv4/webhtv && /g/Python310/python.exe -m unittest discover -s chaquo/src/test/python'
Ran 45 tests in 0.730s
OK
```

（改动前 36 项，新增 9 项 `DownloadTest`/`ManualVideoCheckBridgeTest`/`BridgeContractTest`。）

第一轮评审修正后复跑：

```
& 'G:\Git\bin\bash.exe' -c 'cd /f/Workspace/webtv4/webhtv && /g/Python310/python.exe -m unittest discover -s chaquo/src/test/python'
Ran 49 tests in 1.728s
OK
```

（再新增 4 项：4xx 报错、跳转链走不下去的 3xx 报错、带 scheme 的非 http 源报错、桥接契约集合反向校验。）

第二轮评审修正（`catch` 注释措辞、`optionalBool` 抽取、文档同步）后再次复跑：

```
Ran 49 tests in 0.878s
OK
```

### 编译

以下两条构建产物**早于第一轮评审修正**（`19s` / `2m 41s`），仅作历史记录；修复后的复跑结论见紧接其后的第三段。

```
JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.8-hotspot" ./gradlew :chaquo:compileArm64_v8aDebugJavaWithJavac -PchaquopyBuildPython=G:/Python310/python.exe
BUILD SUCCESSFUL in 19s
```

```
./gradlew :app:assembleMobileArm64_v8aDebug -PchaquopyBuildPython=G:/Python310/python.exe
BUILD SUCCESSFUL in 2m 41s
```

第二轮评审修正后的复跑（对应当前工作区，即本文件所记录的全部改动）：

```
JAVA_HOME="/c/Program Files/Microsoft/jdk-21.0.12.8-hotspot" ./gradlew :chaquo:compileArm64_v8aDebugJavaWithJavac -PchaquopyBuildPython=G:/Python310/python.exe --console=plain
BUILD SUCCESSFUL in 27s
19 actionable tasks: 3 executed, 16 up-to-date
```

### 真机端到端（模拟器 HD1910，Android 9 / SDK 28）

安装修复后的 `app-mobile-arm64_v8a-debug.apk` 后，用 App 自带的本地服务（`adb forward tcp:19978 tcp:9978`）驱动，全程不需要点 UI：

1. 把真实 `夸父.py` 推到设备 `/sdcard/TV/kua.py`，配置写为 `{"sites":[{"key":"kuafu","name":"网盘资源社","type":3,"api":"file://TV/kua.py",...}]}`，经 `/manage/configs` + `/manage/config/use` 注入（`file://` 会被 `UrlUtil.convert` 改写成 `http://127.0.0.1:9978/file/TV/kua.py`，走 App 自己的 `Local` 路由，因此不需要 `adb reverse`）。
2. `GET /spider?key=kuafu&method=homeContent` 返回真实分类列表：`{"code":0,"data":{"class":[{"type_id":"1","type_name":"电视剧/剧集"},{"type_id":"2","type_name":"短剧"},{"type_id":"9","type_name":"电影资源"},...]}}`。
3. `GET /spider?key=kuafu&method=categoryContent&tid=1&pg=1` 返回真实条目（如 `dir_158309 【4K全规格高码/至016集】《嫁金钗》…`，`vod_remarks":"夸克 百度 迅雷"`）。
4. 依赖落盘证据：`cache/py/` 出现 `requests.py`（31 字节，内容为夹具正文），脚本本体落盘为 `cache/py/db0b4963b285663df7f75fb487fb5364.py`（25556 字节，即 md5(api) 命名）。

### 失败路径验证（把依赖源设成 404/500）

删掉 `/sdcard/TV/requests.py` 后重新加载配置，App 的 `Local` 路由对缺失文件返回 500：

```
python-spider: depend download failed name=requests error=com.chaquo.python.PyException: ValueError: download failed (500): uri:[redacted]
```

同时：

- `homeContent` **仍然返回完整分类列表**（站点没有被降级成 `SpiderNull`）；
- 调试日志 `init failed` 计数 = **0**，`AttributeError` 计数 = **0**；
- `cache/py/requests.py` 的旧内容保持完整（未被错误响应体覆盖）。

修复前的对照行为由 Python 侧复现确认：加载当前 `app.py` 后 `hasattr(app, 'download')` 为假，而 `Spider.init()` 会调用它 → 必然 `AttributeError` → `PyLoader` 降级 `SpiderNull`。

## 评审记录

### 第 1 轮（双轴评审，基准点 `0bccba71c5f86818c99a44114d3f0d58e7989481`）

Standards 轴与 Spec 轴并行评审 `35516a58ffc`，结论：6 个文件与改动清单一一对应，无 scope creep；发现 3 项必修 + 2 项说明。

必修（已修复）：

1. **失败日志默认不可见**（Standards/Spec 共同命中）。`SpiderDebug.log()` 经 `DebugLogStore.acceptsTag()` 门控，该判断要求「调试日志」开关（`catvod/src/main/java/com/github/catvod/crawler/DebugLogStore.java:37-39`，默认 false），因此「依赖取回失败只记日志」在默认配置下对用户不可见。同仓 `app/src/main/java/com/fongmi/android/tv/api/loader/PyLoader.java:59-64` 已为同样原因补了 `e.printStackTrace()`。→ 补无条件 Logcat 通道。
2. **`catch (Exception)` 覆盖不全**。JVM 级 `Error`（`OutOfMemoryError`/`StackOverflowError`）会逃逸到 `PyLoader`，重新触发「整站降级」这一原始故障。→ 改为 `catch (Throwable)`。注意第二轮的校正：Python 侧异常经 Chaquopy 桥到达 Java 时是 `PyException`（继承 `java.lang.RuntimeException`），本来就落在 `Exception` 里；这条改动的作用域是 JVM 错误，不是跨桥异常。
3. **契约测试的期望集合可由被测代码自身缩小**。原实现完全从 Java 源码推导名字集合，删掉最后一个调用点会让集合一起缩小甚至为空，本测试要防的回归反而测不出来。→ 改为显式 `REQUIRED` 契约集合 + 双向校验。

同时按 Spec 轴意见补强：
- `download()` 门槛由 `>= 400` 改为 `>= 300`，避免「跳转链走不下去」时的跳转占位正文被写进缓存。
- 带 scheme 的非 http 源（`file://…`）改为直接报错，避免把 URL 文本当脚本写进缓存。
- 补 4xx 用例（原先只有 503/500）。

判为「不修改、仅记录」：
- Spec 轴指出 `BUILD SUCCESSFUL` 与模拟器端到端证据在仓库内不可复现。属实，这两项是执行时的一次性证据，未固化成夹具；本次不为此新增夹具（超出范围）。
- Spec 轴指出本文档 Recovery anchor 的「等待提交」在提交瞬间过期。已改为记录当前真实状态。

### 第 2 轮（双轴复审，对象＝第一轮修复后的工作区）

Standards 轴独立复核后确认：3 项必修均已生效，`>= 300` 不影响 200/204 与正常跳转路径；并从 Java 源码实测 19 个 `app.callAttr` 名全部被正则覆盖、`Platform.java:92` 与 `Loader.java:25` 未误收，无漏检无误检。Spec 轴同时确认 45→49 与真实运行一致。

据此再落三处（本轮新增）：

1. **注释与事实不符**。原文写「跨桥抛出的 `Error`」，但 `PyException` 是 `RuntimeException`，跨桥异常本就在 `Exception` 内；`catch (Throwable)` 的真实收益是 JVM 级 `Error`。已把 `Spider.java` 注释与上文第 2 条改成准确表述（行为不变）。
2. **重复代码**。`manualVideoCheck`/`isVideoFormat` 是同一个「getattr → callable → bool」模板的两份拷贝，已抽出 `optionalBool(ru, name, *args)`，两个桥接函数各剩一行，语义与测试断言不变。
3. **文档缺一条例外**。如果调用方是 Python 源自己直接调用 `download()`，`FileNotFoundError`/`OSError` 会照常抛出，不会走「只记日志」；已在 `docs/integration/spider.md` 补明。

判为「暂不修改」：`BridgeContractTest` 只校验名字与存在性、不校验 `callAttr` 的参数个数（arity-blind）。补齐需要解析 Java 实参个数并维护每个函数的签名表，收益（改名/改参这类改动编译期就会失败）低于维护成本，本次记录不改。

### 第 3 轮（变异验证，对象＝第二轮修复后的工作区）

前两轮的结论都是「读代码得出」，本轮改为**动手证伪**：对工作区施加变异，确认新增测试真的会失败，再原样还原（每次还原后核对 md5 与 `git diff --stat`）。

| 变异 | 预期 | 实测 |
| --- | --- | --- |
| `app.py` 的 `status_code >= 300` 改回 `>= 400` | 3xx 用例失败 | `FAILED (failures=1)` |
| 删掉 `app.py` 的 `elif '://' in api:` 分支（486 字节） | scheme 用例失败 | `FAILED (failures=1)` |
| `Spider.java` 把 `callAttr("download", …)` 改名 `downloadZ` | 双向契约各失败一条 | `FAILED (failures=2)`：`test_every_java_call_attr_exists_in_app_py`、`test_the_bridge_still_calls_every_contracted_name` |

第三项是关键：它同时证明「Java 调用的名字在 app.py 里都有」与「契约里的名字 Java 都还在调」两条断言都真的会咬人，即本轮补的 `REQUIRED` 反向校验不是摆设。

另外修正一处**文档陈述与代码不符**（Standards 轴迟到意见命中）：第 2 轮正文曾写「`'://' in api` 不会误伤内联源码」，这不成立——内联源码里若字面量出现 `https://`，同样会抛 `ValueError`。该句已删除，只保留经实测的 `>= 300` 与 19 个 `callAttr` 名覆盖结论。

判为「不修改」的遗留项（均已在上文记录理由）：`api.startswith('http')` 大小写敏感（沿用既有写法，`HTTP://` 会落到 scheme 分支并报「不是 http(s)」）、`>= 300` 也会挡 304（无条件的 GET 不会产生 304）、`catch (Throwable)` 会吞 `OutOfMemoryError` 后继续 init（与 `PyLoader` 及本类既有风格一致）、契约正则 arity-blind。

## 回滚

单次提交可整体 `git revert`；改动集中在 `chaquo` 模块内，不涉及 ABI、锁文件或打包产物，无数据迁移。

## 未纳入本次范围

- `loadModule()/loadSpider()` 从 `cache/py` 载入依赖的可用性未做端到端夹具验证（`cache/py` 不在 `sys.path`，需要在脚本里显式调用）。本次只负责把依赖文件正确取回并落盘，并在文档里写清这个限制。
- `夸父.py` 依赖内嵌 `DEFAULT_COOKIE` 访问 `www.wpzy.org`；cookie 过期会表现为「有分类但列表为空」，与本缺陷无关，未做处理。
