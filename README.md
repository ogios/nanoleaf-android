# Nanoleaf 原生 Android 灯带控制

使用 Kotlin 和 Android 原生 View、`NsdManager`、`TileService` 实现。对应相邻目录 `nanoleaf-demo` 和 `egui-android/crates/nanoleaf-example` 的功能，不依赖 Rust、egui、JNI 或 Python。

支持 Android 8.0（API 26）及以上版本。包名为 `dev.ogios.nanoleaf`，可与旧 egui 应用同时安装。旧应用的私有令牌不会自动迁移，请重新授权或手动输入已有令牌。

## 安装与使用

构建后的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`，已使用本机 Android debug keystore 签名，可直接用于个人安装。

1. 手机和灯带连接同一局域网，打开应用，点击“扫描设备”。扫描持续 8 秒，使用 `_nanoleafapi._tcp.` mDNS 服务，显示名称、型号、IP 和端口。
2. 点击扫描结果。在 Nanoleaf 官方 App 中进入这台设备的设置，点击 **Connect to API**，然后在 30 秒内回到本应用点击“获取授权”。应用自动申请、保存令牌。
3. 首页可开启、关闭、切换或刷新状态。开关操作会再次读取设备状态确认结果。授权失效、设备离线、格式异常等情况会显示错误和“状态未知”。
4. 点击“添加到下拉快捷面板”。Android 13 及以上会显示系统添加界面；较早版本请在系统快捷面板的编辑界面添加“灯带开关”。此流程采用 Android 官方的 [Quick Settings Tile API](https://developer.android.com/develop/ui/views/quicksettings-tiles)。

找不到设备时，使用“手动连接 / 输入已有令牌”，默认地址沿用 Python demo 的 `192.168.0.106:16021`。IP 和端口均可修改；留空令牌会申请新授权，填写令牌则先验证再保存。地址发生变化后需重新连接，或在路由器中为灯带保留固定地址。

快捷磁贴每次点击都会读取灯带当前状态，再反向切换并确认。磁贴标注“上次开 / 上次关”，因为其他控制器可能在两次操作之间改变灯带状态。锁屏时会先要求解锁；尚未配置时点击磁贴会打开应用。

## 构建

需要 JDK 17–25、Android SDK Platform 35、Build Tools 36.0.0。项目固定使用 Gradle 9.3.1、Android Gradle Plugin 9.1.1 及其内置 Kotlin 支持，无额外 UI 或网络运行时库。版本组合参考 [AGP 兼容性说明](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。

在 Android Studio 中打开此目录，或执行：

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要联网下载 Gradle、构建工具和测试依赖；缺失的 Build Tools 可通过 Android Studio SDK Manager 安装。`./gradlew :app:assembleRelease` 生成经过 R8 优化的未签名 release APK，正式分发前需要自行配置签名；请勿将 keystore 或密码提交到仓库。

## 实现与验证

- `NanoleafApi.kt`：局域网地址与令牌校验，授权和 HTTP 控制。通过 `/api/v1/new` 授权、GET `/api/v1/{token}/state/on` 读取状态、PUT `/api/v1/{token}/state` 发送 `{"on":{"value":true/false}}` 控制开关，与 Python 库及 [Matter WiFi Essentials 官方协议](https://nanoleaf.atlassian.net/wiki/spaces/NOAD1/pages/2296381472) 一致。兼容布尔值与嵌套开关响应。禁止跟随 HTTP 重定向，限制响应大小，并设置连接与读取超时。
- `NanoleafApplication.kt`：应用页面与磁贴共享的后台操作队列、私有 SharedPreferences 和最后观察到的状态。操作跨页面重建继续执行，不保存 Activity 引用。
- `DeviceDiscovery.kt`：按需扫描、串行解析发现结果，超时或离开页面时停止发现并释放 multicast lock。
- `MainActivity.kt` / `LightTileService.kt`：原生中文界面与系统快捷磁贴。无轮询、常驻服务或云端中转。
- `NanoleafApiTest.kt`：13 项测试覆盖局域网边界、令牌校验、不同状态格式、读写确认顺序、授权失败、失败后不误报成功，以及真实本地 HTTP 服务器上的 JSON、重定向和响应大小限制。设备协议回归测试明确拒绝旧的错误写入路径及扁平请求体，并验证开启、关闭和双向切换。

令牌仅保存在应用私有目录，不写入日志或构建产物；云备份及设备迁移均排除连接配置。手动输入令牌的对话框禁止截图。灯带协议只提供 HTTP，请在可信局域网使用。“清除连接信息”仅清除本机数据，不撤销设备端令牌。

当前 `targetSdk` 为 35，与旧 demo 一致。升级到要求局域网运行时权限的目标 SDK 前，需要补上相应授权流程。

本地验证：Debug APK 构建、13 项单元测试和 Android Lint。没有连接 Android 设备，尚未进行真机界面、mDNS、快捷面板及灯带端到端验证。
