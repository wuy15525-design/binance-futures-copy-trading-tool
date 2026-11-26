# Windows x64 工程
桌面程序包含 .NET Framework WinForms/WebView2 宿主、Java 交易引擎、SQLite JDBC 和本地页面。公网 WebView 的网页消息与宿主对象均关闭，桌面 UI 使用独立的配置目录及受限的本地来源。

在有 Python 3.12、Pillow、Node 和 JDK 21 的 Linux 构建机上，从项目根目录执行：
```sh
python build-tools/fetch-deps.py
python build-tools/package.py
```
发布目录为 `build/CopyTerminal/`。下载的 Windows ZIP 内含编译后的交易引擎 `lib/terminal.jar`。Java 交易引擎源码在仓库的 [`src/com/localcopy/terminal/`](../src/com/localcopy/terminal/)，离线测试在 [`tests/`](../tests/)。

程序资源和运行时文件完整性清单的摘要嵌入宿主。首次 Windows 用户界面使用前需要安装 Evergreen WebView2 Runtime 和 .NET Framework 4.7.2+。

测试使用独立临时数据目录。基准计算、自动跟单和官网 DOM 测试继承自 Android 4.2.3。DesktopArchiveTest 验证真实 SQLite 事务和重启。tests/engine_flow.py 从发布包启动实际引擎，向浏览器适配层提供测试快照。tests/desktop_flow.py 在 Chromium 中加载正式桌面页面及官网解析器，通过离线页面夹具驱动同一引擎。CHROMIUM 可指定测试浏览器。所有测试均不需要账户密钥。
