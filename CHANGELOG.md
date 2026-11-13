# Changelog

## v1.0.8 (2026-11-13)

- **修「投放到小爱音箱提示：播放遇到问题，稍后再试」**。根因不是网络，而是地址形式：
  音箱/电视是**按 URL 后缀挑解码器**的（lxserver 自己的小爱投送也因此在
  `streamToken.buildStreamUrl()` 里按 Content-Type 补 `.mp3/.flac/.m4a`），而 1.0.7 推给设备的是
  `/api/music/download?url=…`（恒无后缀），设备直接判成无法播放。
- 现在投放前先向服务端 `POST /api/music/xiaoai/stream-url` 申请**签名播放地址**
  （`/api/music/xiaoai/stream.<ext>?token=…`，后缀与真实内容一致），走 WebView 的会话 Cookie 鉴权；
  这条通道与「投送小爱」同源，但只签发地址、不碰任何小米设备或账号。
- 候选地址按顺序自动回退，且**逐个回查 `GetTransportInfo` 确认真的起播**，不再出现「App 显示已投放、
  音箱却在报错」的假成功：
  1. 原格式签名地址（电视/通用 DLNA 用，不转码）
  2. mp3 转码签名地址（小爱这类只吃 mp3 的设备用）
  3. 原始中转地址（服务端接口不可用时兜底）
- 切歌时重新签发地址（签名有有效期，且新歌是新 URL）。
- 投放成功/失败会把设备名、使用的地址、失败原因写进 `events.log`，便于排查。

## v1.0.7 (2026-11-13)

- **锁屏/通知栏显示真实播放进度**：之前 `PlaybackState` 用的是
  `PLAYBACK_POSITION_UNKNOWN`、`MediaMetadata` 里也没有 `METADATA_KEY_DURATION`，
  系统只能把进度画成 `----`。现在页面每秒上报 `currentTime / duration`，
  进度条正常显示且**可以拖动**（`ACTION_SEEK_TO` → seek 页面 `<audio>`）。
- **锁屏封面**：把页面封面地址（mediaSession artwork / `#player-cover`）传给原生，
  带 WebView Cookie 拉取后设为 `ALBUM_ART`，不再是一张灰色占位图。
- **DLNA / UPnP 投放（新增）**：工具栏「投放」→ 局域网扫描 MediaRenderer →
  选中后把当前播放地址推给设备（音箱/电视），音频由设备自己回源拉流，手机只做遥控。
  - 投放地址优先用同源地址（lxserver 的 `/api/music/download` 中转无鉴权，设备可直接拉）；
    跨域直链自动包成同一中转，规避 B 站这类 Referer 防盗链。
  - 投放期间本机静音、通知栏/锁屏的播放暂停与进度都改控设备（每 2 秒
    `GetPositionInfo` 回填），停止投放会把设备进度带回本机续播。
  - 发现用 SSDP + `MulticastLock`（不加锁很多机型搜不到设备），不引入第三方库。

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
