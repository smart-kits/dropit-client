# dropit 浏览器扩展

[English](./README.md) · **简体中文**

把当前页面、一个链接或选中的文字投进 dropit —— 右键就完事。
支持 Chrome 和 Edge（Manifest V3）。**只投不收**：它从不读取任何内容。

## 安装

还没有上架 Chrome 应用商店，先以「已解压的扩展程序」方式加载：

1. `git clone https://github.com/smart-kits/dropit-client.git`
2. 打开 `chrome://extensions`（Edge：`edge://extensions`），打开右上角的**开发者模式**
3. 点**加载已解压的扩展程序**，选 `extension/` 目录

## 第一次使用

点 dropit 图标，会问「这是你的第一台设备吗？」：

- **是第一台** → 点「是，创建新账号」
- **已经在用 dropit** → 在另一台设备上生成配对码（比如 `dropit code`），填进输入框，点「加入」

## 怎么用

| 在哪里 | 投什么 |
|---|---|
| 选中文字后右键 | 这段文字 |
| 在链接上右键 | 链接地址 |
| 在图片上右键 | 图片的**地址**（不是文件本身） |
| 在页面空白处右键 | 当前页面地址 |
| 图标 → 投当前页面 | 当前页面地址 |

**反馈可信：**

- **成功** —— 绿色 ✓ 角标，持续 2.5 秒。同一天重复投同样的内容也算成功，会被识别出来，不会存两份。
- **失败** —— 红色 ✗ 角标，**同时**弹系统通知说明原因。断网绝不会显示成功。

要让这个浏览器断开：图标 → 在这个浏览器上退出。

## 权限

只有四个：`contextMenus` · `storage` · `activeTab` · `notifications`。

**不申请任何 host 权限** —— 扩展没法读取或修改任何网站。
代价是它拿不到图片的字节，所以右键图片投的是地址。要投真正的文件，
用[命令行](../cli/README.zh-CN.md)（`dropit send -f`）。

## 隐私

- 每个浏览器都作为**独立的一台设备**配对。token 存在浏览器的**本地**扩展存储里，不会同步到你别的浏览器 —— 所以每个浏览器都能单独吊销。
- 用配对码加入时，扩展拿到的是**只投**（`ingest_only`）的 token：就算泄露了，也读不到你的任何内容、不能配对新设备、不能改设置。

## 目录

```
extension/
├── manifest.json     Manifest V3
├── api.js            API 调用、服务地址切换、错误文案
├── background.js     右键菜单、投递、角标与通知反馈
├── popup.html/.js    弹出框：首次设置、投当前页
└── icon.png
```

无构建步骤 —— 改完文件，在扩展页点一下重新加载即可。

## 已知限制

- 只投不收。接收请用 [Obsidian 插件](https://github.com/smart-kits/dropit-obsidian/blob/main/README.zh-CN.md) 或 `dropit watch`。
- 图片投的是地址，不是文件。
- 界面语言跟随浏览器：默认英文，浏览器设为中文时显示简体中文。

## 许可

[MIT](../LICENSE)
