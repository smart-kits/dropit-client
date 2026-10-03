# dropit 命令行

[English](./README.md) · **简体中文**

命令行上的 dropit —— 既能**投**，也能**收**。
Raycast、Alfred、macOS 服务菜单也都基于它：入口很多，逻辑只有一份。

- 单文件、零依赖
- Node.js ≥ 18（`watch` 的实时推送需要 Node.js ≥ 22）
- macOS 与 Linux
- 英文或简体中文 —— 跟随 `LANG`；可用 `DROPIT_LANG=zh` / `DROPIT_LANG=en` 强制指定

## 安装

```bash
git clone https://github.com/smart-kits/dropit-client.git
cd dropit-client/cli
ln -s "$(pwd)/dropit" /usr/local/bin/dropit     # 需要的话前面加 sudo，或者链到 ~/.local/bin
```

## 第一次使用

```bash
dropit pair            # 第一台设备：创建账号
dropit code            # 生成配对码给下一台设备（5 分钟内有效）
dropit pair K7M2QX     # 在另一台设备上：用配对码加入
```

设置（包括 token）保存在 `~/.config/dropit/config.json`，权限 `0600`。

## 命令

| 命令 | 作用 |
|---|---|
| `dropit pair [配对码]` | 不带配对码：创建账号；带配对码：加入已有账号 |
| `dropit code` | 生成配对码给新设备用（5 分钟内有效） |
| `dropit send <文本或链接>` | 投文本，链接会自动识别。也可以从管道读：`pbpaste \| dropit send` |
| `dropit send -f <文件>` | 投文件 —— 图片、PDF、视频等。流式上传，大文件也不占内存 |
| `dropit watch [目录]` | 实时收到本地文件夹（默认 `~/dropit`） |
| `dropit watch --from <序号>` · `--days <天数>` | …并从第 `<序号>` 条起、或最近 `<天数>` 天重新拉取 |
| `dropit me` | 查看套餐、设备数和已用空间 |
| `dropit devices` | 列出已配对的设备 |
| `dropit revoke <device_id>` | 吊销一台设备，立即释放名额 |

## 用 `watch` 接收

`dropit watch` 启动时先把还没收到的内容全部补齐，然后保持连接，新内容一到就写盘。

| 内容 | 保存为 |
|---|---|
| 文本或链接 | `YYYY-MM-DD-<seq>.md`，带 front matter（`dropit_seq`、`kind`、`source`、`created`） |
| 文件 | `YYYY-MM-DD-<seq>-<原文件名>` |

`.md` 的正文是 Markdown：带网页标题的链接写成 `[标题](网址)`，下面用引用块写出网页简介；从网页上投来的内容，末尾加一行 `— [网页标题](网址)`（没有标题时用网站域名）。文件按原样保存，不记来源网页。

- **一条都不漏。** 逐条推进进度，只有写盘成功才推进。`watch` 中途被打断，下次启动会从断点接着收；已存在的文件不会被覆盖。
- **一个文件下载失败，不耽误后面的。** 它会留下一个 `YYYY-MM-DD-<seq>.md` 说明原因，接着往下收。`dropit watch --from <seq>` 可以重新下载，下载成功后替换掉那个说明。
- **从指定位置重新拉取。** `--from <序号>` 从那一条重新开始；`--days <天数>` 重新拉取最近几天。文件夹里已有的文件会保留，所以这是把缺的补回来。
- **睡眠唤醒也不掉线。** 每 60 秒发一次心跳，能发现「看起来连着、其实收不到」的死连接（睡眠唤醒后很常见），并以 1 秒到 60 秒的退避重连。
- **没有实时推送时**（Node.js < 22，或当前套餐不含实时 —— 新账号有 10 天体验期），`watch` 补齐一次后退出，并说明原因。再运行一次、或定时运行，就能接着收新内容。

### 开机常驻 `watch`（macOS launchd）

```bash
cat > ~/Library/LaunchAgents/io.github.smart-kits.dropit.plist <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.github.smart-kits.dropit</string>
  <key>ProgramArguments</key><array>
    <string>/usr/local/bin/dropit</string><string>watch</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
PLIST
launchctl load ~/Library/LaunchAgents/io.github.smart-kits.dropit.plist
```

## 接进系统入口

### Raycast

Raycast → Extensions → Script Commands → *Add Directory* → 选 [`raycast/`](./raycast/)。
会多出两个命令：投剪贴板、投文件。

### macOS 服务菜单（任意 App 里选中文字右键）

自动操作（Automator）→ 新建「快速操作」→ 工作流程收到「文本」→ 加「运行 Shell 脚本」，
「传递输入」选**「作为自变量」**：

```bash
export PATH=/usr/local/bin:$PATH
dropit send "$1"
```

存成「投进 dropit」。之后在任意 App 里选中文字 → 右键 → 服务 → 投进 dropit。
在「系统设置 → 键盘 → 键盘快捷键 → 服务」里还能给它绑一个全局快捷键。

### Alfred

新建 Workflow → *Hotkey* 触发 → 连一个 *Run Script*（`/bin/bash`）：

```bash
export PATH=/usr/local/bin:$PATH
pbpaste | dropit send
```

## 出错了怎么办

| 提示 | 含义 | 怎么办 |
|---|---|---|
| `token 无效` / `设备已被移除` | token 失效，或这台设备被吊销了 | 重新 `dropit pair <配对码>` |
| `设备数已达上限` | 设备数到上限了 | `dropit devices` 看一下，`dropit revoke <id>` 移除不用的 |
| `投递过于频繁` | 短时间内投得太多 | 稍等再试 |
| `空间已满` | 空间满了 | 等旧内容过期（投递后 1 天，付费版 10 天） |
| `网络不可用（试过 N 个地址）` | 所有服务地址都连不上 | 检查网络 |
| `上传失败 …` | 文件上传失败 —— 会明确报错，不会悄悄丢掉 | 重新 `dropit send -f` |

配置里存的是一组服务地址。第一个连不上就依次试下一个，并记住能用的那个。

## 已知限制

- 没有在 Windows 上测试过。
- `watch` 对磁盘满、目录只读这类本地故障没有特别处理，失败只记日志。

## 许可

[MIT](../LICENSE)
