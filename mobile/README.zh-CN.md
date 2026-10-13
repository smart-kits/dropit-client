# dropit 安卓版

[English](./README.md) · **简体中文**

> 开发中，还没有发布。商店和网站上都还装不到。

不打开 App 就能投：*分享* → **dropit**、选中文字 → **dropit**、*用其他应用打开* → **dropit**，或者在控制中心、
长按图标里点「发剪贴板」。打开 App 可以写字、选文件，看这台手机投出去了什么。加入时扫另一台设备上的二维码，
或者输入它的配对码；能加设备、移除设备，看各个套餐给什么。在手机上接收的部分还在做。

样子和 dropit 的其他客户端一致：从一只航空信封里投出去，红邮票就是投递，没投出去的盖「退回」章。
默认英文，手机设成中文时显示中文；浅色 / 深色跟随系统。

## 目录

| 目录 | 是什么 |
|---|---|
| `shared/` | Kotlin Multiplatform：协议和各客户端共同遵守的规则（投递内容的格式、批次、错误文案）。同时编译 JVM 和 iOS，以后 iPhone 版可以直接复用 |
| `androidApp/` | 安卓 App：各个投递入口、后台上传、界面 |
| `androidApp/src/main/res/font/` | 两个标签字体：拉丁字母用 Barlow Condensed 600；中文用得意黑的子集（改名为「dropit Label ZH」），只含标签用到的那些字。都是 SIL Open Font License，许可文件在 `licenses/` |
| `tools/label_font.py` | 改了中文标签后重新生成中文子集；缺字时有测试会失败 |
| `../test/vectors/` | 由浏览器扩展算出的对照用例；共享层的测试检查这里算出的结果和扩展一致 |

## 构建和测试

需要 JDK 17 和 Android SDK（platform 36）。

```bash
./gradlew :androidApp:assembleSiteDebug          # 调试包
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test :androidApp:testSiteDebugUnitTest
```

iOS 测试需要 Xcode。把 `DROPIT_DEV_URL` 设成本地的 dropit 服务时，JVM 测试还会对它跑一条端到端检查。
调试包在手机上连 `127.0.0.1:8799`，用 `adb reverse tcp:8799 tcp:8799` 转发。

三种构建类型：`debug`、`release` 和 `qa` —— 和 release 一样压缩，但像调试包一样连本地服务，
用来检查代码压缩之后有没有坏（`./gradlew :androidApp:assembleSiteQa`）。

相机只用来扫配对码，点扫码按钮时才打开：画面由开源的识别库（ZXing）在手机上识别，不离开手机。
只有 6 位配对码或 dropit 自己的配对链接才起作用，别的二维码一律忽略。

两种构建：`site`（官网版，自己更新）和 `play`（Google Play）。正式签名读放在本仓库之外的
`keystore.properties`：先看 `DROPIT_SIGNING` 指向的路径，没有就读 `../../signing/keystore.properties`。
其中 `storeFile` 相对这个文件。找不到它时，release 包不签名。
