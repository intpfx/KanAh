# Redmi recovery3 实机验收（2026-10-22）

设备：MiTV-MFTR0，Android 14，ARMv7。
已安装家庭候选：com.live.tv，1.0.0-rc1-recovery3，versionCode 73。
本轮没有修改应用代码、重新安装 APK 或改变电视系统设置。
主线现已纳入相同功能修复，构建保留 ARMv7 / ARM64 双架构；本文验证的是此前安装的 ARMv7 设备包，不是新主线 APK 的部署验收。
版本构成及 Mac 源站测试见 [绕过基线记录](redmi-bypass-baseline.md)。

## 连播观察

从 00:02:15 开始，到 00:17:18 结束，实际 **903.3 秒（15 分 3 秒）**。
原定 30 分钟；用户确认“连播观察算通过了”，要求进入下一项，因此提前结束。
不得把这次记录当成完整 30 分钟测试。

- 始终保持 CCTV-13 和同一应用进程 24974。
- 57 次状态采样、15 张定时截图，记录到 225 个不同视频分片。
- 未记录到播放失败、自动重试、播放器重建或直播请求错误；状态探测无错误。
- 已查看的画面样本正常，内容及新闻时钟推进；用户也反馈感觉已正常。
- 分片请求和截图是采样证据，不能据此声称排除了每一帧的短暂卡顿。

## 切台与重新进入

每项在控制指令完成后继续观察约 60 秒。
表中出画面耗时来自客户端观察到的 revealed 日志时间，减去控制指令完成时间。
它包含通信及日志采集误差，不是逐帧精密测量。

| 场景 | 出画面耗时 | 结果 |
| --- | ---: | --- |
| CCTV-13 → CCTV-1 | 26.3 秒 | 官方播放器出画面，后续分片持续更新 |
| CCTV-1 → CCTV-2 | 6.5 秒 | 快速播放器正常 |
| CCTV-2 → CCTV-4 | 17.1 秒 | 快速播放器正常 |
| CCTV-4 → CCTV-1 | 3.7 秒 | 返回已记忆频道，快速播放器正常 |
| CCTV-1 → CCTV-13 | 3.1 秒 | 快速播放器正常 |
| 回到电视桌面，10 秒后重进 | 4.4 秒 | 恢复 CCTV-13，同一进程 |
| 强停应用后冷启动 | 5.9 秒 | 新进程 26617，恢复 CCTV-13 |
| 待机唤醒后重新打开 Live | 6.0 秒 | 新进程 27216，恢复 CCTV-13 |

上述八项没有记录到播放失败、网络错误或自动重试；末段截图均已查看，画面正常。
冷启动最初一次 API 连接被拒绝，随后恢复，是服务尚未启动的探测结果，未计为播放错误。
系统音频信息显示 Live 获得音频焦点、扬声器输出、未静音；没有进行主观听音验收。

## 待机自动续播：未通过，原因已定位

00:25:05 发送 KEYCODE_SLEEP（223），系统从 Awake 进入 Asleep。
约 15 秒后发送 KEYCODE_WAKEUP（224）没有唤醒设备，读取到的状态仍为 Asleep。
00:26:20 再确认 Asleep 后发送遥控器电源键对应的 KEYCODE_POWER（26），成功唤醒。

系统日志给出了应用消失的直接原因：

```text
00:25:05.688 PowerManagerService: gotoSleepStayAliveProcess current:com.live.tv not in whitelist
00:25:07.790 ActivityManager: Force stopping com.live.tv appid=10061 user=0: from pid 654
00:25:07.792 ActivityManager: Killing 26617:com.live.tv/u0a61 ... from pid 654
00:26:20.745 PowerManagerService: Waking up from Asleep ... reason=WAKE_REASON_POWER_BUTTON
```

进程退出记录同样显示 FORCE STOP，发起方为系统进程 654。
唤醒后前台是 com.mitv.tvhome，Live 的管理端口没有监听。
因此本轮不能宣称“待机唤醒自动续播通过”：电视待机策略强停了未在白名单中的 Live。
重新打开 Live 后约 6 秒恢复原频道，继续观察一分钟正常。
本轮没有更改保活白名单、启动设置或系统电源策略。

## 结论与证据

当前 recovery3 的 CCTV 播放、频道切换、返回前台和冷启动在本轮观察中正常。
首次进入部分频道仍慢于已记忆频道；短期验收不代表全部频道或所有网络时段均已覆盖。
待机后的自动启动/续播仍受电视系统强停策略限制，需要作为独立需求处理。
本轮测试结束时电视保持唤醒，Live 正在播放 CCTV-13。

原始数据位于 [本地验收目录](../artifacts/redmi-stability-20261022/)（不随仓库发布）：

- `soak.json`、`live-events.jsonl`、`minute-*.png`：连播过程。
- `scenario-summary.json`：各项耗时、事件及末次状态。
- 各场景同名 `.json`、`.log` 和 `.png`：状态、日志和截图。
- `standby-system-events.log`、`power-key-wake.json`：系统强停与实际电源键唤醒证据。
- `stability-watch.py`、`lifecycle-watch.py`：本轮使用的观测脚本。

没有发生需要与 Mac 同时复测的直播源请求故障；本轮待机失败有系统强停证据，
未将其误判为 CDN 故障。之前的 Mac 物理网卡直连结果保留在绕过基线记录中。
