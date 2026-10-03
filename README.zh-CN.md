# dropit 客户端

[English](./README.md) · **简体中文**

**发给自己 —— 从任何设备投，在每台设备收。**

看到一条链接、想记一句话、手边一个文件：按一下就进队列，回到另一台设备上它已经在那儿了。
内容在投递后保留 1 天（付费版 10 天）—— dropit 负责投递，不负责存储 —— 在这之前随时可以重新拉取。

这个仓库是 dropit 的开源客户端。Obsidian 插件在单独的仓库：
[smart-kits/dropit-obsidian](https://github.com/smart-kits/dropit-obsidian)。

## 让你的 AI 来装

在用 AI 编程助手（Claude Code、Codex、Cursor……）？把下面这段贴给它：

```text
按照 https://raw.githubusercontent.com/smart-kits/dropit-client/main/AGENTS.md
帮我在这台电脑上安装 dropit。创建账号之前先问我；任何时候都不要显示或提交我的 token。
```

它会安装并配对命令行、验证能用，再一步步带你完成需要你亲手操作的部分
（加载浏览器扩展、搭建 iOS 快捷指令）。给 AI 的说明在 [AGENTS.md](./AGENTS.md)。

## 客户端一览

| 客户端 | 平台 | 投 | 收 | 说明 |
|---|---|:-:|:-:|---|
| **iOS 快捷指令** | iPhone · iPad | ✅ 文字、链接、照片、视频、文件 | — | [shortcuts/](./shortcuts/README.zh-CN.md) |
| **浏览器扩展** | Chrome · Edge | ✅ 页面、链接、选中的字、图片、文件、截图 | — | [extension/](./extension/README.zh-CN.md) |
| **命令行** | macOS · Linux（Node.js ≥ 18） | ✅ 文本、链接、文件 | ✅ 落到本地文件夹 | [cli/](./cli/README.zh-CN.md) |
| **Obsidian 插件** | 桌面 · 移动 | ✅ 选中的字、笔记、文件 | ✅ 落成 vault 里的笔记 | [dropit-obsidian](https://github.com/smart-kits/dropit-obsidian/blob/main/README.zh-CN.md) |
| **Web 收件箱** | 任意浏览器，打开 [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) | ✅ 文字、链接、一次最多 5 个文件 | ✅ 就在页面里 | — |

Raycast、Alfred、macOS 服务菜单都基于命令行，见 [cli/](./cli/README.zh-CN.md)。

## 能发什么、怎么发

| 在哪 | 文字 | 链接 | 照片和视频 | 文件（PDF、Excel……） |
|---|---|---|---|---|
| **iPhone / iPad** | 任意 App 里选中 →「共享」→ **dropit** | 「共享」→ **dropit**（Safari 或任意 App） | 「照片」里选一张或几张 →「共享」→ **dropit** | 「文件」App 里长按 →「共享」→ **dropit** |
| **Chrome / Edge** | 选中 → 右键 →「把选中的字投进 dropit」；或 <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> | 在链接上右键；或点图标 → <kbd>回车</kbd> 投这个页面 | 右键 →「把这张图片投进 dropit」（文件）或「把图片链接投进 dropit」；截图粘贴进弹窗 | 右键指向文件的链接；在弹窗里粘贴或选文件 |
| **终端** | `dropit send "你好"` · `pbpaste \| dropit send` | `dropit send https://…` | `dropit send -f photo.jpg` | `dropit send -f report.pdf` |
| **Obsidian** | 选中 → 右键 →「发到 dropit」 | — | 在文件列表里右键它 | 右键一篇笔记或几个文件（可多选） |
| **任意浏览器** | 在 Web 收件箱里输入 → 按邮戳 | 同左 | 选文件、拖进来或粘贴（最多 5 个） | 同左 |

- **不用做任何选择。** 发出去，几秒后就在你的其他设备上：Obsidian 里是笔记，Web 收件箱里，或命令行的文件夹里。
- **大小：** 单个文件免费版最大 5 MB，付费版 100 MB。长视频很容易超过。
- **iPhone 上会弹通知**告诉你发出了什么（「已发送 3 张照片/视频」），或者为什么没发出去。网页里的 PDF 发的是它的链接；想发 PDF 本身，先存到「文件」App。
- **不小心发了两次？** 1 分钟内紧挨着发同样的内容，只算一次。

## 特性

- **投递只要一步。** 投放路径上没有表单、没有选择题 —— 分享、右键或者一条命令。
- **实时送达。** 接收端通过长连接收到新内容，离线后重新上线会自动补齐。
- **不丢内容。** 接收端只在内容安全落盘之后才推进进度；中途崩溃最多重复一条，绝不漏一条。
- **自动去重。** 1 分钟内紧挨着投两次同样的内容（连点、重试），只存一份；隔一会儿再投，照常送达。
- **每台设备独立。** 每台设备单独配对、可单独吊销。只投不收的设备（浏览器、手机）读不到你的任何历史内容。
- **权限最小。** 浏览器扩展安装时不申请任何网站权限。图片和文件按页面平时加载的方式去取；只有不让这样取的网站，才就那一个网站问一次。
- **无构建、无依赖。** 命令行是单个 Node.js 文件；扩展直接加载即可。

## 开始使用

1. **设置第一台设备**，这一步会创建你的账号。
   - 命令行：`dropit pair`
   - Obsidian：设置 → dropit → 第一次用 dropit？ → 创建新账号
   - 浏览器扩展：点图标 → 表单下面的小字链接「创建新账号」
2. **在这台设备上生成配对码**，5 分钟内有效。
   - 命令行：`dropit code`
   - Obsidian：设置 → dropit → 配对码 → 生成
3. **在其他设备上用配对码加入。**
   - 命令行：`dropit pair <配对码>`
   - Obsidian：设置 → dropit → 配对码 → 填入 → 加入
   - 浏览器扩展：点图标 → 输入配对码 → 加入
   - iOS：运行「dropit 配对」快捷指令
4. **投一条试试** —— `dropit send "你好"`、在网页上右键、或者在手机上分享 —— 看它送达。

配对码 6 位，容错：大小写、空格、连字符都无所谓，`0`/`O`、`1`/`I`/`l` 打混了也能认。

## 安全与隐私

- token **只存在你自己的设备上** —— 命令行：`~/.config/dropit/config.json`（权限 `0600`）；扩展：浏览器的本地扩展存储；Obsidian：插件目录里的 `data.json`。
- token 分两种权限：`full`（投、收、配对新设备）和 `ingest_only`（只投）。浏览器扩展和 iPhone 快捷指令用配对码加入时拿的是 `ingest_only`。
- 设备丢了？在任意一台 `full` 设备上吊销它：先 `dropit devices`，再 `dropit revoke <device_id>`。

## 问题和建议

[提一个 issue](https://github.com/smart-kits/dropit-client/issues)。每个客户端里也有入口：浏览器扩展在弹窗底部，
Web 收件箱在页面底部，命令行在 `dropit help` 的最后一行，iOS 快捷指令在首页它那一项里。issue 是公开的，不要贴 token（`dk_` 开头）。

## 参与贡献

- 第一次提交前先打开提交前密钥扫描，保证 token 永远进不了仓库：

  ```bash
  brew install gitleaks          # 其他平台见 https://github.com/gitleaks/gitleaks
  git config core.hooksPath .githooks
  ```

- push 之前跑测试：`node test/i18n.test.mjs`（零依赖）。
- 所有界面文案都在中英两张表里，新增文案两边都要加，否则测试会失败。
- 提交信息用英文。
- 文档成对维护：`README.md`（英文）和 `README.zh-CN.md`（简体中文），改一份请同步另一份。

> 界面语言自动跟随：默认英文；系统、浏览器或 Obsidian 设为中文时显示简体中文。命令行看 `LANG`，也可以用 `DROPIT_LANG=zh` 或 `DROPIT_LANG=en` 强制指定。

## 许可

[MIT](./LICENSE)
