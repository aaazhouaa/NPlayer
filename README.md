![N Player banner](fastlane/metadata/android/en-US/images/featureGraphic.png)

# N Player

N Player 是一个基于 Kotlin 与 Jetpack Compose 的 Android 原生视频播放器，源自 [Anil Beesetti](https://github.com/anilbeesetti) 的开源项目 [Next Player](https://github.com/anilbeesetti/nextplayer)。本仓库是面向个人使用的二次开发版本：应用名改为 N Player，包名为 `com.nplayer`，在保留上游全部功能的基础上补充了隐藏媒体、网格选择器、AB 循环等改动。

- 上游项目：<https://github.com/anilbeesetti/nextplayer>
- 本仓库：<https://github.com/aaazhouaa/NPlayer>

## 下载

最新版本 **v0.21.0（versionCode 78）**：<https://github.com/aaazhouaa/NPlayer/releases/latest>

发布资源 `app-arm64-v8a-release-with-debug-signing.apk`：arm64-v8a release 构建，debug 签名，可直接安装。

## v0.21.0 更新内容

基于上游 0.21.0，包含以下新增与修复。

### 新增

- 应用更名为 N Player，启用线稿风格新图标，包名更改为 `com.nplayer`
- 隐藏媒体：支持隐藏文件夹与 `.nomedia` 扫描，读取容器索引提取时长与分辨率，持久化缓存并支持冷启动复用
- 视频选择器：新增网格布局（默认），显示分辨率、文件大小与「新」角标；顶栏新增「已读」按钮，按选择模式作用于选中项，并与观看历史解耦
- 播放器：进度条拖动即实时跳帧，上滑 60dp 取消本次跳转；新增 AB 循环
- 播放器：默认开启长按加速、长按默认 5 倍速、倍速上限 8 倍，跳转灵敏度上限提至 5
- 设置：长按加速与倍速相关偏好、控制栏默认 3 秒自动隐藏

### 修复

- 返回动画卡顿（保留下层入口常驻组合），关闭返回手势预览，返回动画时长与缓动曲线对齐进入动画
- Navigation3 返回时 SaveableStateHolder 重复键崩溃
- 隐藏媒体日期单位错误与扫描缓存新鲜度过期，导致新视频与角标计数不显示
- 缩略图：TS 视频非零起始时间导致定位帧错误
- 长按文件夹/文件震动重复，改为仅震动一次
- 网格模式标题与路径左对齐并按存储根目录显示相对路径

## 功能特性

- Material 3 (You) 界面，支持文件夹树、文件夹与文件三种浏览模式
- 支持本地媒体库、存储访问框架（SAF）与网络存储（SMB/FTP/SFTP/WebDAV）
- 播放本地视频或 URL，支持竖滑调节亮度/音量、横滑拖动进度
- 音轨与字幕轨选择、外挂字幕、字幕延迟、播放速度、缩放、画面比例、音量增强
- 画中画与后台播放，Android TV 版本，搜索功能

## 支持的格式

- **视频**：H.263、H.264 AVC、H.265 HEVC、MPEG-4 SP、VP8、VP9、AV1（具体支持取决于设备）
- **音频**：Vorbis、Opus、FLAC、ALAC、PCM/WAVE、MP1/MP2/MP3、AMR、AAC、AC-3/E-AC-3、DTS/DTS-HD、TrueHD（由 ExoPlayer FFmpeg 扩展提供）
- **流媒体**：DASH、HLS、RTSP
- **字幕**：SRT、SSA、ASS、TTML、VTT、DVB（SSA/ASS 样式支持有限）

播放器支持 MX Player 兼容的 VIEW Intent，详见 [Player intent API 文档](PLAYER_API.md)。

## 致谢

- 上游作者 [Anil Beesetti](https://github.com/anilbeesetti)，上游仓库 [Next Player](https://github.com/anilbeesetti/nextplayer)
- 翻译由 [Weblate](https://hosted.weblate.org/engage/next-player/) 社区维护
- 部分实现参考了 [Findroid](https://github.com/jarnedemeulemeester/findroid)、[Just (Video) Player](https://github.com/moneytoo/Player)、[LibreTube](https://github.com/libre-tube/LibreTube)、[ReadYou](https://github.com/Ashinch/ReadYou)、[Seal](https://github.com/JunkFood02/Seal) 等项目

## License

N Player 与上游 Next Player 一致，采用 GNU General Public License v3.0 授权，详情见 [LICENSE](LICENSE)。
