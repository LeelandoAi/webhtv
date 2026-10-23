# PY-BRIDGE-DEPEND-01 Python 桥接依赖下载契约修复

## Recovery anchor

- 目标：修复「同一 py 源在 WebHTV 加载不了、在 OK影视 正常」的根因，并补齐同类桥接契约缺口。
- 验收标准：`getDependence()` 非空（如夸父.py 返回 `["requests"]`）的 py 源能在 WebHTV 正常初始化；依赖取回失败不再把整站降级为 `SpiderNull`；Java `app.callAttr("<name>")` 与 app.py 属性有自动回归校验。
- 车道 / 范围：`standard`；`chaquo/src/main/python/app.py`、`chaquo/src/main/java/com/fongmi/chaquo/Spider.java`、`chaquo/src/test/python/test_app_bridge_contract.py`、`docs/integration/extensions.md`、`docs/integration/spider.md`、本文件。
- 分支 / 基线：`dev4` @ `0bccba71c5f86818c99a44114d3f0d58e7989481`。
- 状态：实现、单测、编译、真机端到端验证全部完成，等待提交。
- 下一步：`task_guard.sh finish` 提交并打恢复标签。

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
  - `http(s)` 源：`redirect(api)` 手动跟随跳转（沿用既有单跳 10s 超时、最多 10 跳上限），`status_code >= 400` 抛 `ValueError('download failed (<code>): <api>')`，否则 `writeFile(path, rsp.content)`。
  - 非 `http` 源：`writeFile(path, str.encode(api))`，与该仓库对非 http 内联源的既有约定一致。
  - 错误响应体**不写盘**：否则会用 HTML 错误页覆盖已有的可用缓存，后续 `SourceFileLoader` 只会报一个误导性的 `SyntaxError`。`writeFile()` 本身是原子写（mkstemp + fsync + os.replace），抛错时旧文件保持不变。
- 新增 `manualVideoCheck(ru)` / `isVideoFormat(ru, url)`：与 `getDependence()` 同样的 `getattr` 兜底风格，未实现即返回 `False`（「无意见」），实现本身的异常仍然照常抛出。

### 2. `chaquo/src/main/java/com/fongmi/chaquo/Spider.java`

- 新增 `import com.github.catvod.crawler.SpiderDebug;` 与 `private static final String TAG = "python-spider";`。
- `init()` 的依赖循环改为 `try { download(item + ".py"); } catch (Exception e) { SpiderDebug.log(TAG, "depend download failed name=%s error=%s", item, e); } finally { close(item); }`。

理由：单个依赖取不到不应让整个站点降级成 `SpiderNull`（那正是用户看到的「加载不了」）。改为记日志继续初始化；真正需要该模块的源会在首次使用时抛 `ImportError`，比整站静默空列表更好定位。`finally` 保证 `PyObject` 仍被释放，与该类其它 `toStr/toBool` 的释放惯例一致。

### 3. `chaquo/src/test/python/test_app_bridge_contract.py`

- `_Response` 增加 `content` 字段，`_RequestsStub` 保持不变。
- 新增 `DownloadTest`：http 写正文、跟随跳转、非 http 写字符串、4xx/5xx 抛错且不创建目标、下载失败保留旧缓存、成功后目录里不残留临时文件。
- 新增 `ManualVideoCheckBridgeTest`：缺失实现返回 `False` 而不是 `AttributeError`；有实现时正确委派。
- 新增 `BridgeContractTest`：用正则 `\bapp\.callAttr\("([A-Za-z_][A-Za-z0-9_]*)"` 从 `chaquo/src/main/java/com/fongmi/chaquo/*.java` 提取全部桥接调用名，断言 app.py 每个名字都有可调用属性，并显式断言 `download` 仍在 Java 侧被调用。正则限定 `app.` 前缀，避免误收 `Platform.java` 对 `java.android` 和 `Loader.java` 对 `webhtv_logging` 的 `callAttr`（它们是不同的 Python 模块）。
- 扩展 `test_bridge_helpers_are_importable_by_remote_scripts`，纳入 `download/manualVideoCheck/isVideoFormat`。

### 4. 文档

- `docs/integration/extensions.md`：第 34 条补依赖取回与失败语义；错误处理条目由「依赖下载失败会初始化失败」改为「只记日志并继续初始化」。
- `docs/integration/spider.md`：补 `app.download()` 的取回规则（含 4xx 不覆盖旧文件），并明确 **`cache/py` 不在 `sys.path` 上**——依赖文件只是落到缓存目录，脚本必须自己 `loadModule()/loadSpider()`；直接 `import requests` 走的是 Chaquopy 随包安装的 `requirements.txt`，与 `getDependence()` 无关。

## 验证

### 单元测试

```
& 'G:\Git\bin\bash.exe' -c 'cd /f/Workspace/webtv4/webhtv && /g/Python310/python.exe -m unittest discover -s chaquo/src/test/python'
Ran 45 tests in 0.730s
OK
```

（改动前 36 项，新增 9 项 `DownloadTest`/`ManualVideoCheckBridgeTest`/`BridgeContractTest`。）

### 编译

```
JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.8-hotspot" ./gradlew :chaquo:compileArm64_v8aDebugJavaWithJavac -PchaquopyBuildPython=G:/Python310/python.exe
BUILD SUCCESSFUL in 19s
```

```
./gradlew :app:assembleMobileArm64_v8aDebug -PchaquopyBuildPython=G:/Python310/python.exe
BUILD SUCCESSFUL in 2m 41s
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

## 回滚

单次提交可整体 `git revert`；改动集中在 `chaquo` 模块内，不涉及 ABI、锁文件或打包产物，无数据迁移。

## 未纳入本次范围

- `loadModule()/loadSpider()` 从 `cache/py` 载入依赖的可用性未做端到端夹具验证（`cache/py` 不在 `sys.path`，需要在脚本里显式调用）。本次只负责把依赖文件正确取回并落盘，并在文档里写清这个限制。
- `夸父.py` 依赖内嵌 `DEFAULT_COOKIE` 访问 `www.wpzy.org`；cookie 过期会表现为「有分类但列表为空」，与本缺陷无关，未做处理。
