# Changelog

## v1.0.0 (2026-10-24)

- 初始版本：WebView 套壳安卓客户端。
- 设置页可填写服务器地址 / 用户名 / 密码，连接自部署 lxserver。
- 支持 HTTP 基础鉴权自动填充、局域网明文流量、文件下载。
- 可选「内置前端」模式：打包 lxserver 的 `public/`，接口代理到服务器（脚本 `fetch-frontend.sh`）。
- GitHub Actions 自动构建签名 APK 并发到 Release。
