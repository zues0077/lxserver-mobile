# Changelog

## v1.0.6 (2026-11-13)

修复「播放一会儿 App 自动关闭」——根因是两个结构性缺口，不是网页 bug：

- **WebView 渲染进程死亡自愈**：实现 `onRenderProcessGone`。此前未实现时若渲染进程被系统回收或因内存崩溃，
  WebView 会（按渲染进程死因）连带杀掉整个 App 进程，且属于 native kill——不抛 Java 异常，崩溃日志也抓不到，
  用户看到的就是「App 无任何提示突然消失」。现在旧实例销毁重建、页面自动重载，App 不再被带走。
- **前台服务保活（息屏/切后台不被冻结）**：新增 `PlaybackService`（`foregroundServiceType="mediaPlayback"`），
  网页开始播放即拉起，暂停后 8 秒宽限自动收掉；同时持有 `PARTIAL_WAKE_LOCK` + `WifiLock`，避免息屏后
  CPU 休眠 / Wi-Fi 省电把音频流掐断。通知栏与锁屏提供上一首 / 播放暂停 / 下一首（转发回页面已有的
  `playPrev()` / `togglePlay()` / `playNext()`，不修改 lxserver 前端）。
- **事件日志**：新增「进程重建 / 渲染进程死亡（含 didCrash）/ 前台服务启动失败」写入
  `Android/data/com.zues007.lxservermobile/files/logs/events.log`，用于区分「被内存回收」和「真崩」。
- **设备侧降低被回收概率**：`android:largeHeap="true"`；设置页新增「忽略电池优化（白名单）」入口和
  当前 WebView 内核版本显示 —— realme / OPPO / 小米 的后台限制比原生 Android 更激进。
- **设置页新增「允许 WebView 调试」**：开启后可用 Chrome `chrome://inspect` 直接看页面控制台与进程状态。
- **返回键改为退到后台**（无历史时 `moveTaskToBack`），避免误触返回就掐掉正在播放的音乐。
- **WebView 改为代码创建**：渲染进程死亡后必须销毁旧实例并换新实例，写在 XML 里的实例无法替换。

## v1.0.0 (2026-10-24)

- 初始版本：WebView 套壳安卓客户端。
- 设置页可填写服务器地址 / 用户名 / 密码，连接自部署 lxserver。
- 支持 HTTP 基础鉴权自动填充、局域网明文流量、文件下载。
- 可选「内置前端」模式：打包 lxserver 的 `public/`，接口代理到服务器（脚本 `fetch-frontend.sh`）。
- GitHub Actions 自动构建签名 APK 并发到 Release。
