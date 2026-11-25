# KanAh！维护指南

## 项目边界

- 本项目为 Android TV 上的「看啊」。当前应用 ID 为 `com.siaovon.kanah`，最低 Android 8.0（API 26）。历史安装可能使用 `com.siaovon.khub` 或 `com.live.tv`。
- 直播支持央视网页及 IPTV 播单；影视支持 TVBox type 1 / MacCMS JSON 及直接 HLS / MP4；音乐支持搜索、歌词、封面和本地缓存。保留用户配置及现有媒体缓存的兼容性。
- 保持播放器与来源设置在电视端运行。Web 面板、局域网远程、内置代理和 Web 服务已移除。影视、音乐与直播请求直连来源。
- 真机验收使用目标电视。操作前确认设备和本次授权；安装前核对包名、版本和签名。签名不匹配时停止覆盖安装，卸载会清除应用数据。
- 同一个版本号可能对应不同开发包。核对电视安装包时使用 APK SHA-256，并区分安装成功、启动成功与功能验收。

## 播放器维护

- 流媒体在同一个原生播放器界面准备与播放。恢复时先显示缓存帧，再按进度准备媒体；首次进入时准备视频首帧。采样主题色和预览帧时避免播放器控件及黑帧。
- 区分正在播放的资源、线路和选集，以及遥控焦点预览。当前集播完后从同一线路顺序播放下一集；末集停止。单集进度、缓存状态和最近播放均按资源引用保存。
- 长按选集进入缓存管理。缓存删除需要用户确认；来源失效时仍允许清理本地缓存。
- 修改遥控、控制层或焦点时，按直播、影视、音乐当前实现核对菜单键全局搜索、返回、播放／暂停及快进快退。[项目历史](docs/PROJECT-HISTORY.md)中的旧侧栏和三行布局属于对应迭代记录。
- 电视待机可能由系统强停应用。进程恢复和会话续播需要真机验证，避免把应用内计时或状态记录当作系统保活。

## 开发与验证

需要 JDK 17 或 21、Android SDK Platform 36、Build Tools 35.0.0。配置 `ANDROID_HOME`
或本地 `local.properties` 后，在仓库根目录运行 Gradle 8.13 Wrapper。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。
Windows 使用 `gradlew.bat`。依赖从公共 Maven 仓库获取，Cronet 使用 Google Maven。
`sh tests/run.sh` 提供 Java 和 JavaScript 行为检查，媒体片段用例需要 Node.js、FFmpeg 和 ffprobe。
根据改动选择检查范围。文档改动检查链接、示例命令及 Git diff。

## GitHub 标签发布

- 构建入口为 [Android APK release](.github/workflows/android-release.yml)。推送已进入 `main` 历史且包含工作流的标签后，自动运行单元测试和 Release lint，构建并核验签名。
- GitHub Release 附带 `KanAh.apk` 和 `SHA256SUMS.txt`。为新版本打标签前，先在 `app/build.gradle` 更新 `versionName` 并递增 `versionCode`。
- Actions Secrets 使用 `LIVE_KEYSTORE_BASE64`、`LIVE_KEY_ALIAS`、`LIVE_STORE_PASSWORD` 和 `LIVE_KEY_PASSWORD`，变量 `LIVE_CERTIFICATE_SHA256` 用于证书校验。独立发布签名用于 GitHub APK 的后续覆盖升级。已有不同签名的安装不能直接覆盖升级。
- 保留仓库外的独立签名备份和全部第三方许可声明，包括 [音乐模块 NOTICE](app/src/main/assets/music/NOTICE)。家庭设备旧签名流程见 [家庭自用发布](docs/FAMILY-RELEASE.md)，正式版验收见 [1.0 发布清单](docs/RELEASE-1.0.md)。

## 从旧版迁移

迁移目标为 `com.siaovon.kanah`，默认从 `com.siaovon.khub` 迁移，也支持仍安装 `com.live.tv` 的电视。迁移工具只适用于由同一调试签名签署的旧版与新版。

- 由旧应用在自己的身份下读取 Android Keystore 加密的影视来源，迁移时由新应用的 Keystore 重新加密。保留来源 ID、修订和代次，使本地缓存和进度仍能匹配原资源。迁移不得记录来源地址和鉴权信息。
- 迁移直播配置、影视和音乐会话、观看进度、暂停快照及对应媒体缓存。影片缓存需同时核对分片、任务索引与 Media3 数据库，音乐缓存需核对曲目清单和文件。
- 写入新版前校验迁移包和目标状态，使用测试资料核对导入、重试与失败恢复。电视上验证新版可读配置、离线播放缓存并从原位置续播后，旧应用和数据仍保留，供用户确认。

工具入口为 [scripts/migrate-legacy-debug.sh](scripts/migrate-legacy-debug.sh)。先运行
`./gradlew :app:assembleDebugAndroidTest -PmigrationSource=com.siaovon.khub` 并单独保留产出的旧版辅助 APK，
再运行 `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` 生成新版和新版辅助 APK。
电视上保留旧应用，安装两个辅助 APK 和新版后，在首次打开新版前执行
`scripts/migrate-legacy-debug.sh <adb-device>`。若旧版为 `com.live.tv`，构建旧版辅助 APK 时将 `migrationSource` 设为该包名，并在执行脚本时将包名作为第二个参数。工具会暂时停止两个应用，媒体与配置在设备内传输，校验文件后导入，并验证完成缓存可由纯缓存数据源读取。目标已有对应资料时停止，失败暂存材料保留以供检查。两套辅助程序均在独立测试 APK 内。

## 维护资料

- [项目历史与待办](docs/PROJECT-HISTORY.md)保留版本迭代、设备验收、历史 APK 摘要、未结事项及旧版迁移记录。
- [主线同步记录](docs/main-sync-20261025.md)记录早期源码、APK 和电视安装包的一致性。
- [小米盒子适配记录](docs/mibox-android6-lessons-20261025.md)记录 Android 6 / Gecko 特供版。主线继续要求 Android 8 或更新版本。
