# Altimeter · 海拔计

一个没有广告的 Android 海拔计：用**气压计 + GPS 互补滤波**测当前海拔，室内、山沟里 GPS 没信号时也能出数。

> 做这个的起因很简单：应用商店里能看海拔的 App 基本都塞满了广告。这个只给自己用，不联网、不上报、不弹窗。

## 为什么不用系统/其他 App 的 GPS 海拔

- **GPS 海拔误差大**：水平精度通常 3–5 米，但垂直精度经常是 10–30 米，冷启动时还会直接吐 `0.0`。
- **室内直接没信号**：GPS 一进楼就断，海拔跟着停更。
- **气压计响应快、相对精度高**：能感知几十厘米的楼层变化，但绝对值会随天气漂移，需要校准。

所以这里把两者融合：**气压计管短时变化，GPS 管长期绝对值**。

## 功能

- 气压高度与 GPS 高度互补滤波融合，默认 `alpha = 0.85`（偏向气压）
- 气压原始值低通滤波（`0.85 / 0.15`），压掉传感器抖动
- 没有气压计时自动降级为纯 GPS 模式，界面明确提示
- 冷启动先读「最后已知位置」，秒出第一个数，不用干等卫星
- 过滤无效定位：没有高程的 fix、冷启动吐出的 0 米假 fix（无垂直精度）、垂直精度差于 50 米的 fix 直接丢弃；实时 fix 与上一个可信值瞬间相差超过 1000 米也丢弃，避免海拔被拉到 0
- 状态与诊断面板：定位可用性、精确定位权限、GPS/网络开关、原始 fix、GPS 次数、P0、错误信息
- 显示定位数据年龄（刚刚 / N 秒前 / N 分钟前 …），缓存值多旧一眼可见
- 日志包含权限、provider 开关和 GnssStatus 回调心跳，可直接判断是没信号还是没回调
- 无信号或只有缓存定位时提供「重新搜索卫星」，重新注册定位请求（GNSS 卡死时可自救）
- 显示实时经纬度（6 位小数，可一键复制）
- GPS 信号强度 5 格指示，按参与定位卫星数与平均载噪比估算
- 卫星数量：已定位 / 可见，另有「卫星详细」展开每颗卫星的星座、PRN、C/N0 和是否参与定位
- 一键导出诊断日志（含设备信息、当前状态、卫星明细、logcat），方便排查
- 直接使用系统 `LocationManager`，**不依赖 Google Play Services**，国行无 GMS 的机器也能用
- 仅为个人使用：无网络权限、无统计、无广告

## 工作原理

标准大气公式（`P` 单位 hPa，`P0` 为海平面气压）：

```kotlin
altitude = 44330 * (1 - (P / P0) ^ 0.1903)
```

由已知海拔反推校准值：

```kotlin
P0 = P / (1 - altitude / 44330) ^ (1 / 0.1903)
```

互补滤波（`fuse`）：

```kotlin
fused = alpha * baroAlt + (1 - alpha) * gpsAlt
```

- `alpha` 越大越信任气压（短时平滑、抗抖），越小越信任 GPS（长期不漂）
- 首次融合时取两者平均值
- 任意一路缺失时直接用另一路，不会因为丢信号变成 `--`

爬升累计用 3 米死区过滤，避免静止时抖动把爬升量刷上去（`AltitudeFusionEngine.accumulateGain`，目前尚未接到界面）。

## 界面

主界面自上而下：

| 区域 | 内容 |
| --- | --- |
| 主读数 | 当前融合海拔（米）、垂直误差约 ±X 米 |
| 状态 | 如「定位正常」「气压模式（GPS 无信号）」「搜索GPS信号中」 |
| 指标卡 | GPS 信号强度、已定位/可见卫星数、当前气压 |
| 位置 | 纬度、经度（6 位小数），可复制 |
| 卫星详细 | 展开后按「参与定位优先 + 信号强度」排序，显示星座、PRN、C/N0、是否参与定位 |
| 诊断信息 | 定位可用性、GPS 海拔、P0、定位次数、气压计、原始信号、错误 |
| 操作 | 导出诊断日志（分享为 txt） |

如果没给定位权限，界面会出现「授予定位权限」按钮。

## 环境要求

- Android 8.0（API 26）及以上
- 定位权限（`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`）
- 气压计为**可选**硬件；没有则自动降级为 GPS 模式

## 编译

项目使用：

- AGP 8.5.2 / Gradle 8.7+
- Kotlin 2.0.20
- Jetpack Compose（BOM 2024.09.00）
- JDK 17

用 Android Studio 直接打开即可。命令行构建：

```bash
# 在项目根目录配置 SDK 路径（local.properties 不会被提交）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

gradle assembleDebug
```

> 注意：仓库目前**没有 Gradle Wrapper**，需要本机已安装 Gradle；用 Android Studio 打开时会自动下载匹配版本。

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 目录结构

```
app/src/main/java/com/example/altitude/
├── MainActivity.kt                 # 入口，权限申请
├── domain/
│   └── AltitudeFusionEngine.kt     # 气压换算、互补滤波、爬升统计
├── data/
│   ├── BarometerDataSource.kt      # 气压传感器（低通滤波）
│   ├── LocationDataSource.kt       # 系统 LocationManager + GNSS 卫星 + fix 过滤
│   ├── DemRepository.kt            # DEM 查询（未接入）
│   └── LogExporter.kt              # 诊断日志导出
└── ui/
    ├── AltitudeViewModel.kt        # 状态管理、GPS/气压/卫星调度
    ├── HomeScreen.kt               # Compose 界面
    └── theme/
        └── Theme.kt                # 深色配色
```

## 已知限制

- **P0 校准没有界面入口**：`AltitudeViewModel.calibrateWithKnownAltitude()` 已经实现，但目前只能从代码调用。实际使用中如果发现整体偏高/偏低，这是最该补的一个 UI。
- **DEM 事后修正未接入**：`DemRepository` 查 Open-Meteo 的接口写好了但没被调用，而且 Manifest 里也没有 `INTERNET` 权限。
- **没有后台采集**：退到后台就停止更新，没做前台服务。
- **P0 会随天气变化**：长时间不校准，绝对海拔可能漂几米到十几米。
- **没有单元测试**：`AltitudeFusionEngine` 是纯 Kotlin、本来就为 JVM 单测设计，但测试还没写。

## 版本

当前版本：1.0.6.6

四段式：主版本.次版本.修订.构建。每次更新只把最后一段构建号 +1。

