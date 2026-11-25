# KanAh！

面向 Android TV 的直播、影视和音乐播放器，安装后显示为「看啊」。支持 IPTV 播单、
MacCMS 影视来源、选集缓存与自动续播，以及音乐歌词和封面。需要 Android 8.0 或更新版本。

## 下载

从 [GitHub Releases](https://github.com/intpfx/KanAh/releases) 获取 `KanAh.apk`。
已有家庭版或开发版时，覆盖安装要求 APK 使用相同的签名。

## 本地构建

准备 JDK 17 或 21、Android SDK Platform 36 和 Build Tools 35.0.0，在仓库根目录执行：

```sh
./gradlew :app:assembleDebug
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。维护、验证与发布流程见
[AGENTS.md](AGENTS.md)，版本和设备记录见 [项目历史](docs/PROJECT-HISTORY.md)。

自有源码采用 [MIT 许可证](LICENSE)，第三方许可见 [NOTICE](NOTICE)。
