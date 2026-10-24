# LXServer Mobile

安卓客户端：把你自己部署的 [zues0077/lxserver](https://github.com/zues0077/lxserver) 的 Web 播放器套进一个原生 WebView 壳里，手机上像原生 App 一样打开使用。

> 为什么是 WebView 套壳？因为 lxserver 自带一个**已针对移动端深度优化**的 Web 播放器（`public/`，纯 Vanilla JS）。直接复用它，开发量最小、体积最小、体验最接近原生，无需用 React Native 重写整套 UI。

## 功能

- 在 App 内填写 **服务器地址 / 用户名 / 密码**，连接你自己的 lxserver（默认端口 `9527`）。
- 用系统浏览器内核（WebView）加载 Web 播放器，播放、歌单、搜索等全部由服务端前端提供。
- 支持 **HTTP 基础鉴权**自动填充；Web 播放器自身的表单登录仍在其页面内完成。
- 局域网 `http://` 明文流量已放开（Android 9+ 默认拦截，已在网络安全配置中允许）。
- 文件下载走系统下载管理器。
- **内置前端（可选）**：把 lxserver 的 `public/` 打包进 App，离线也能打开播放器页面，接口仍代理到你的服务器（见下文）。

## 使用

1. 安装 APK（见下方「获取 APK」）。
2. 打开 App → 右上角「设置」。
3. 填写：
   - **服务器地址**：例如 `http://192.168.1.100:9527`（手机与服务器需在同一网络，或服务器有公网地址）。
   - **用户名**（可选）：lxserver 默认开启 `USER_ENABLE_PATH`，建议填用户名以走 `/<用户名>` 路径模式。
   - **密码**（可选）：用于 HTTP 基础鉴权与参考。
   - **使用内置前端**（可选，默认关）：见下。
4. 保存后自动重连。

## 获取 APK

- **Release（推荐）**：在 [Releases](https://github.com/zues0077/lxserver-mobile/releases) 下载。
  - 打 `vX.Y.Z` tag 触发 CI 自动构建；若仓库配置了签名密钥，产出**签名 Release APK**；否则产出 **Debug APK**（同样可安装，用于个人测试）。
- **手动构建产物**：每次 CI 运行都会把 APK 作为 artifact 保留，可在 Actions 页面下载。

## 本地构建

> 本机需要 Android SDK（API 34）+ JDK 17 + Gradle 8.9。最简单方式是用 **Android Studio** 打开本仓库目录直接 Build → Build Bundle(s) / APK(s)。

无 Gradle Wrapper 也能构建：项目在 CI 里直接下载 Gradle 8.9 运行。本地若用 Android Studio，它会用自己的 Gradle 打开工程。

### 内置前端（可选）

「内置前端」模式会把 lxserver 的 `public/` 打进 App。源码仓库里该目录被 `.gitignore` 忽略，需要在构建前拉取：

```bash
bash scripts/fetch-frontend.sh main      # 从 zues0077/lxserver@main 拉取 public/
```

脚本会下载并裁剪掉可选的"空间音频滤波"资源以减小 APK。CI 在构建前会自动执行这一步。

## 发布与签名

CI（`.github/workflows/build-apk.yml`）在打 tag 时自动构建并发到 Release。

要产出**可正式分发的签名 APK**，在仓库 `Settings → Secrets` 配置：

| Secret | 说明 |
| --- | --- |
| `SIGNING_KEY` | 签名密钥库 `keystore.jks` 的 **base64**（见下） |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

生成并编码密钥库（仅首次）：

```bash
keytool -genkey -v -keystore lxserver-mobile.keystore -alias lxmobile \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 lxserver-mobile.keystore > signing_key.b64
# 把 signing_key.b64 的内容填到 SIGNING_KEY
```

不配置 `SIGNING_KEY` 时，CI 仍会构建出 **Debug APK**（可安装，适合个人使用；但不能覆盖安装正式签名版）。

## 项目结构

```
app/src/main/
├── java/com/zues007/lxservermobile/   # MainActivity / SettingsActivity / AppPreferences / LxWebViewClient
├── res/                               # 布局、菜单、字符串、主题、自适应图标
└── assets/webplayer/                  # 内置前端（gitignored，由 fetch-frontend.sh 生成）
scripts/fetch-frontend.sh              # 拉取并裁剪 lxserver 前端
.github/workflows/build-apk.yml        # CI 构建/签名/发布
```

## 许可证

Apache-2.0。lxserver 前端源自 [xcq0607/lxserver](https://github.com/xcq0607/lxserver)（Apache-2.0），本仓库仅做安卓壳封装。
