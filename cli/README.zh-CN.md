# dropit for 终端

[English](./README.md) · **简体中文**

**剪贴板、一个文件、脚本跑完的结果 —— 一行命令，就到手机和其他每台设备上。
再加一个会自己长出内容的文件夹：别处投来的东西，都落在这里。**

支持 macOS 和 Linux。单个 Node.js 文件，没有依赖。

![dropit pair 一行加入；dropit send 发剪贴板、文件、构建完成的提醒；dropit watch 把手机分享来的东西落成文件。](../media/cli-tour.webp)

<sub>画面内容：Lonely Planet 页面链接 · NASA 照片（公共领域）。</sub>

## 一行就够

| 你想… | 运行 | 你会看到 |
|---|---|---|
| 把刚复制的东西发到手机 | `pbpaste \| dropit send` | `✓ 已投递 #41  → 全部 · 14:19` |
| 把一个文件发到手机 | `dropit send -f ~/Desktop/report.pdf` | `✓ 已投递 #42  → 全部 · 14:19` |
| 给自己记一句 | `dropit send "下午三点给银行打电话"` | `✓ 已投递 #43  → 全部 · 14:19` |
| 长任务跑完时知道一声 | `make release && dropit send "发版打包好了 ✅"` | 跑完那一刻，这句话就到了 |
| 把命令输出放到能看的地方 | `git log -5 --oneline \| dropit send` | 这五行，作为一条 |
| 别处投的东西都变成这里的文件 | `dropit watch ~/dropit` | `↓ 2026-10-04-44.md`、`↓ 2026-10-04-45-IMG_2041.jpeg` …… |

几个词会合成一条：`dropit send 六点 见` 投出去的是「六点 见」。
整条只是一个 `http(s)://` 地址时按链接投，其他按文字投。

## 投递

```bash
dropit send "一段文字"            # 文字，或者一个链接
some-command | dropit send        # 管道里流过来的内容，作为一条
dropit send < notes.txt           # 一个文本文件的内容
dropit send -f photo.jpg          # 一个文件：图片、视频、PDF，什么都行
```

- **一次投好几个文件：** `dropit send -f *.png` —— 最多 10 个，作为一批投出、一起到。
  超过 10 个就一个都不发；有文件找不到，也会在发之前就报出来。每个文件一行，最后一行说明投给了谁。
- **一批里有失败就停在那：** 已经发出的保持已发，没发出去的文件会列出来，还有只把它们重发给同样设备的命令，仍归在原来那一批里、各在原来的位置（`dropit send -f b.jpg d.jpg --to '我的 Pixel' --batch <编号> --of 5 --at 2,4`）；收的那一端仍把它们显示成一批。退出码 `1`。
- **只投给部分设备：** `dropit send --to "我的 Pixel" "三点见"`，或 `dropit send -f a.jpg b.jpg --to "我的 Pixel,家里的 Linux"`。
  设备名或编号（`dropit devices` 两样都显示），用逗号隔开；`--to all` 是所有设备。名字里的大小写和空格不影响匹配。
  名字里带逗号、或者叫「all」的设备，只能用它的编号指定。
  两台设备重名时什么都不会发：报错里列出它们的编号，用其中一个。`--to` 只管这一次，回执里也会这么写（`→ 我的 Pixel · 只这一次，默认不变`）。
  只能投递、不能接收的 token 读不了设备列表，所以设备名用不了（`--to all` 可以）。
- **这台电脑的默认：** `dropit target` 显示它；`dropit target "我的 Pixel" "家里的 Linux"` 设置它（`dropit target all` 或 `dropit target clear` 回到所有设备）。
  它只存在这台电脑的配置文件里，别处没有，所以重新配对或重装之后又回到「全部」。默认「全部」时投递不带设备列表，之后加入的设备也会收到；选了其中几台时会写明是哪几台，之后加入的设备不会自动收到。
  默认里的设备被移除后会自动去掉，并提示一次。
- **大文件边读边传**，不会先整个读进内存。单个文件多大由你的套餐决定。
- **手滑投了两次？** 一分钟内紧挨着投两次同样的东西，只存一份，提示 `已投过了（#41）`。
- **写脚本可以放心依赖它：** 成功退出码是 `0`；任何失败都把原因打到 stderr，退出码 `1`。

## 会自己长出内容的文件夹

```bash
dropit watch ~/dropit            # 文件夹会被记住：下次直接 `dropit watch`
```

它先把没开着的这段时间里到的内容全部补齐，然后保持连接，新内容一到就写下来：

| 别处投来的 | 落在这里是 |
|---|---|
| 文字、链接 | `2026-10-04-44.md` |
| 文件 | `2026-10-04-45-IMG_2041.jpeg`，原样保存 |

笔记长这样 —— 浏览器扩展投来的网页保留标题和简介，选中的文字保留出处：

```markdown
---
dropit_seq: 44
kind: url
source: chrome-extension
created: 2026-10-04T07:30:00.000Z
---

[How Airmail Worked](https://example.com/airmail)

> A short history of the red-and-blue envelope.
```

把它指向 Obsidian 或 Logseq 的某个文件夹，每一条就都成了那里的一篇笔记。

- **不漏一条，也不重复。** 文件安全写好之后，进度才往前走；文件夹里已经有的文件从不覆盖。
- **文件没下载下来**，原位置会留一篇说明，写清原因和重新拉取的命令：`dropit watch --from 45`。
- **重新拉取：** `dropit watch --days 3`（最近三天）或 `--from 40`（从第 40 条起）。
  新加入的设备只收加入之后投的内容；想要以前的，就这样拉回来 —— 能拉回多久，取决于你的套餐保留多久。
- 这台机器自己投的也会收下来，所以这个文件夹是一份完整的记录。
- 文件名里的日期是 UTC 日期。

### 实时，以及不实时的时候

`watch` 的实时连接需要 Node.js 22 以上（更早的版本补齐一次就停）。

| 它说 | 意思是 |
|---|---|
| `实时推送已连接` | 实时 —— 几秒内就到 |
| `实时推送已连接 · 体验还剩 3 天` | 新账号有一段实时推送体验期 |
| `……实时推送体验已结束……` | 补齐后退出；再运行一次（或者定时运行）就会补齐。这一行还会说你用它做了什么（用了几天、投了多少条、几台设备在收） |
| `实时推送体验还剩 3 天……` / `……不到 1 天就结束了……` | 体验快结束时，`watch` 启动时打印一次：你已经享受了什么、之后有什么变化 |
| `……今天的实时名额满了……` | 原地等着，UTC 零点过后自己重连 |
| `实时推送暂停：这个账号已经有 12 条实时连接……` | 这个账号同时在接收的机器太多了（另一个 `watch`，或多台机器上的 Obsidian）。大约 15 分钟后自己再试，连上时补齐 |
| `watch 已停止：设备已被移除……` | 这台机器已从账号里移除。再试也没用，所以 `watch` 停下（launchd / systemd 也不会把它拉起来）。用 `dropit pair <配对码>` 重新配对，再启动 `watch` |
| `这台机器加入了另一个账号……` | `watch` 运行时你执行了 `dropit pair <配对码>`。不用做什么：之后收的是那个账号的内容 |

每次重新连上 —— 睡眠唤醒、换网络、服务暂时不可用之后 —— 都会先补齐这期间到的内容。
服务不可用时最多每分钟问一次；服务说了什么时候再来，就等到那时。

### 让它一直开着

**macOS** —— 一个登录时启动 `watch` 的启动项。存为 `~/Library/LaunchAgents/xyz.smart-kits.dropit.plist`，
把里面三个路径换成你自己的（`command -v dropit`、`dirname "$(command -v node)"`、你的用户目录）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>xyz.smart-kits.dropit</string>
  <key>ProgramArguments</key>
  <array><string>/Users/you/.local/bin/dropit</string><string>watch</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <!-- 崩了就重启；正常退出（实时体验期结束，或这台机器被移除）不重启 -->
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>StandardOutPath</key><string>/Users/you/Library/Logs/dropit.log</string>
  <key>StandardErrorPath</key><string>/Users/you/Library/Logs/dropit.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/xyz.smart-kits.dropit.plist      # 现在启动，以后每次登录都启动
launchctl unload ~/Library/LaunchAgents/xyz.smart-kits.dropit.plist    # 停掉
```

**Linux** —— 一个用户服务，`~/.config/systemd/user/dropit.service`：

```ini
[Unit]
Description=dropit watch

[Service]
ExecStart=%h/.local/bin/dropit watch
Restart=on-failure

[Install]
WantedBy=default.target
```

`systemctl --user enable --now dropit`。找不到 `node` 的话，加一行 `Environment=PATH=…`，写上 `node` 所在的目录。

## 用 AI agent 或你自己的命令处理收到的内容

默认关着。打开之后，`watch` 收到的内容会交给你选的 AI agent 或命令。收到的每条文字或链接会变成**一个文件**：上面引用收到的原样，下面是处理结果。不会往你的笔记或待办文件里加任何东西。

```bash
dropit agents                                  # 这台机器上有 claude、codex、grok、gemini、pi 里的哪些
dropit handlers add                            # 先显示会运行什么、问你一次，然后就启用了
dropit watch ~/dropit                          # 新收到的文字或链接，会变成处理后的样子
```

收到的一条文字 `2026-10-04-44.md`，处理之后：

```markdown
---
dropit_seq: 44
kind: text
source: cli
created: 2026-10-04T07:30:00.000Z
---

> 周五下午 3 点给小王回电话，说发票的事；周一前把签好的合同发给他。

---

这是一条待办。1. 周五下午 3 点给小王回电话，说发票的事。2. 周一前把签好的合同发给他。
```

原文就在引用里，所以不会丢；agent 失败的话，文件保持收到时的样子。收到的**文件**（图片、PDF、语音……）总是保留，结果是它旁边的一个文件：`2026-10-04-45-行程.pdf` → `2026-10-04-45-行程.自动处理.md`。想把收到的文字也另存一份，加 `--keep-source`（或 `dropit handlers set auto keepSource=true`），结果就会是单独的 `<文件名>.<规则名>.md`。

- **一条规则，四类内容。** `--only ocr,audio,link,sum` 选它处理什么：`ocr` 图片和 PDF（把文字抽出来）、`audio` 语音和视频（转写）、`link` 链接（读正文）、`sum` 文字（整理并总结）。默认四类都要。
  勾选会写好提示词；要改，用 `dropit handlers edit auto`（用 `$EDITOR`）或 `set auto prompt=…`。你自己写过的提示词，改选类型时不会被重写。工具不替你设任何限制：`minChars=`、`kinds=`、`timeoutSec=` 等都由你自己定。
- **每条内容调用一次，由 agent 自己判断。** 它会被告知收到了什么、每一类该怎么处理。dropit 只给它这一类需要的东西（见下）。
- **一起投来的，一起处理。** 作为一批投来的内容（最多 16 个：手机上一段话加几张图，或 `dropit send -f`）只交给规则**一次**，等它们到齐。没到齐的话，最后一个到了 90 秒后、最迟 5 分钟后，就用已到的去处理。结果是旁边的一个文件，`2026-10-04-44–47.自动处理.md`：先是收到的全部（按投出的顺序，文字照原样、文件是链接），再是答复。收到的文字和文件不会被改动或替换。只有规则勾选的类型才会交给 agent。规则的「文字最长」（`maxChars`）只管文字：一批里的文字比它长、而这批有文件时，文字不交给 agent（agent 会被告知），文件照常处理；「最短」（`minChars`）只用于单独一条文字。只有文字的一批被限额挡下时会提示一句，不再无声跳过。已处理完的那一批后面才到的内容不会单独运行；`watch` 重启后不会接着等没等完的那一批；`watch` 自己追平后退出（没有实时推送）时，会先把还在等的批次处理掉。`--exec` 不受影响：它还是每条各收到一次。
- **你自己的命令。** `dropit handlers add -- my-script --flag` 运行你自己的程序，内容走它的标准输入。
  也可以用 `--agent codex`、`grok`、`gemini` 或 `pi` 选一个：它会从它自己的帮助里建议的参数开始，当作你自己的命令，要改参数用 `set auto argv='["…"]'`。
- **手动：** `dropit run auto notes.txt`，或 `pbpaste | dropit run auto -`。结果就落在那个文件旁边；你指定的文件从不被改动。
- **先看清再信它：** `dropit handlers test auto` 用示例内容显示确切的命令、工作目录、环境变量和完整输入。不加 `--run` 并确认，就什么都不运行。
- **暂停或跳过：** `dropit handlers pause`，或 `dropit watch --no-handlers`。失败只在 stderr 输出一行，接收照常。重新拉取到的文字不会被处理两次。
- **积压：** 一次到了很多条（离开一阵后拉取）时，只有最新的 **3** 条交给规则或 `--exec`，其余照常写入，并用一行说明漏了几条。用 `dropit handlers recent <n>`（0 = 全部）或 `dropit watch --recent <n>` 修改。
- **模型：** Claude 用 `dropit handlers set auto model=sonnet`，等于传 `--model sonnet`；留空用 Claude 自己的默认。其他 agent 或你自己的命令，把模型参数写进命令参数（`set auto argv='["…","--model","…"]'`）。

**agent 被允许用什么**

dropit 按收到的内容分别决定：

| 收到的是 | Claude Code 得到 |
|---|---|
| 文字 | 没有任何工具：读到什么就用文字回答 |
| 链接 | 一个网站：链接指向的那个（跳转到别处不做检查） |
| 图片、视频、文件 | 空文件夹里那一个文件的副本，只读，别的都不行 |

目前只验证过 Claude Code（2.1.301）。它读不了音频和视频；要处理它们，用能读的命令（比如你自己包一层语音转文字工具的脚本），读不了时结果里会直接说。Codex、Grok、Gemini、Pi 能被找到、也能选，但 dropit 没有验证过它们的权限能被限制到什么程度，所以这些的限制要你自己来设。

**请记住**

- **收到的内容是不可信输入。** 持有你账号「只投递」密钥的人，也能把文字送到 agent 面前。所以文字不给任何工具，内容也只会走 agent 的标准输入，从不放进命令行。
- **第一次启用时会告诉你：** 所有符合规则的内容，都会交给你选的 agent；dropit 不经手、不存。
- 规则存在 `~/.config/dropit/agents.json`，和存放密钥的文件分开。这个文件读不了的时候，dropit 从不删除或覆盖它，只会告诉你，然后不去动它。

### 不限制 agent

默认情况下，Claude Code 只拿到每条内容所需要的东西。如果你想要一个能做更多事的 agent（打开要登录的页面、用密钥读不公开的链接、调用技能或 MCP 服务、处理视频），把规则改成**不限制**：

```bash
dropit handlers set auto mode=open
```

这时 dropit 什么都不加、什么都不拿走：它启动的是 `claude -p --no-session-persistence`。Claude Code 按**你自己的设置**运行（技能、MCP 服务、hooks、指令、允许和拒绝规则），并带着**你的全部环境变量**，所以你导出的密钥它看得见。内容的文件照旧复制进工作文件夹。`dropit handlers set auto mode=auto` 可以改回去。

- **这是对安全影响最大的设置。** 收到的内容仍然不可信，持有你账号「只投递」密钥的人，可以把内容送到 agent 面前。不限制之后，这些内容能间接指挥一个拥有你自己全部权限的 agent，而且没有人在场确认。不再用的设备请移除，Claude Code 设置里的权限也请按这件事需要的程度收紧。
- **权限归你。** 运行是无人值守的，没有谁能被询问：需要批准的操作，除非你的 Claude Code 设置已经允许，否则会被拒绝。要让它做某件事，去那里允许。
- **要加限制：** 在 Claude Code 设置里写允许和拒绝规则，或者自己写命令，把能用的东西点名，例如
  `dropit handlers add -- claude -p --permission-mode dontAsk --allowedTools WebFetch --disallowedTools Bash`
  （见 `claude --help`：`--allowedTools`、`--disallowedTools`、`--permission-mode`、`--add-dir`）。你自己的命令只拿到 `PATH`、`HOME`、`USER`，它要用的密钥请放进你的脚本或钥匙串。
- **技能、工具、MCP 服务：** 在 Claude Code 里自己配置；不限制时它们会被加载，你的提示词里可以点名让它用（`dropit handlers edit auto`）。`dropit handlers test auto` 会显示命令，以及它会拿到的环境变量的名字（不显示值）。

**读视频里说了什么**（抖音、TikTok、B 站……）有一份分步的[指南](../docs/ai-videos.zh-CN.md)：要装的工具、一个小脚本、要放行什么，以及测过什么、没测什么。

有人会这样搭。下面都是你自己的搭法，不是 dropit 的功能，dropit 也没有验证过：

- *视频、音频：* 把你自己的脚本（比如下载器加语音转文字工具）当作命令：`dropit handlers add -- ./my-video.sh`。
- *要登录的页面：* 一个驱动浏览器、带自己保存的登录状态的 MCP 服务，在提示词里让它去用。
- *不公开的链接：* 密钥放在你的脚本或钥匙串里，由命令带上。

### 把每条内容交给你自己的命令

```bash
dropit watch ~/dropit --exec ./on-receive.sh --some-flag
```

`--exec` 后面的所有参数都是命令和它的参数，不经过 shell。每条新内容在文件写好之后只交一次，以**一行 JSON 走标准输入**；不重试，也不拖住下一条：

```json
{"v":1,"seq":44,"kind":"url","source":"chrome-extension","created":"2026-10-04T07:30:00.000Z","batch":null,
 "text":"https://example.com/airmail","meta":{"title":"How Airmail Worked"},"note":null,"files":[]}
```

`files` 里是收到的文件的绝对路径。一起投来的几样各是一行，`batch` 是 `{"id":…,"index":…,"count":…}`：想把它们合起来，就收集 `id` 相同的行，收够 `count` 行。**每个字段都是不可信输入**：绝不要把它拼进 shell 命令行。

## 用你的 AI agent 处理收到的内容

默认关着。打开之后，`watch` 收到的内容会交给你选的 AI agent，它的回答写在原文旁边。不会往你的笔记或待办文件里加任何东西，原文也从不被改动。

```bash
dropit agents                                  # 这台机器上有哪些 agent
dropit handlers add --preset understand        # 先显示会运行什么、问你一次，然后就启用了
dropit watch ~/dropit                          # 新内容旁边会多一个结果文件
```

结果是一个单独的文件，用原文件名加规则名命名，例如 `2026-10-04-44.自动识别并整理.md`：

```markdown
原文：2026-10-04-44.md

这是一条待办。1. 周五下午 3 点给小王回电话，说发票的事。2. 周一前把签好的合同发给他。
```

- **三个预设：** `understand`（先说这是什么，再给要点和需要你做的事；收到的每条都处理）、`todos` 和 `summary`（文字和链接；只在你要的时候运行）。
  改规则：`dropit handlers set <id> prompt=…`（或 `edit <id>`，用 `$EDITOR`），还有 `minChars=`、`kinds=`、`auto=false`、`timeoutSec=` 等。
  工具不替你设任何限制：规则处理哪些内容，由你自己定。
- **手动：** `dropit run summary notes.txt`，或 `pbpaste | dropit run summary -`。结果就落在那个文件旁边。
- **先看清再信它：** `dropit handlers test <id>` 用示例内容显示确切的命令、工作目录、环境变量和完整输入。不加 `--run` 并确认，就什么都不运行。
- **暂停或跳过：** `dropit handlers pause`，或 `dropit watch --no-handlers`。失败只在 stderr 输出一行，接收照常。
  已经有结果的内容不会再处理一遍。

**agent 被允许做什么**

| 权限 | 含义 | 提供给 |
|---|---|---|
| 只读文字（默认） | 没有任何工具：读到什么就用文字回答 | Claude Code |
| 读文件 | 在一个空文件夹里读这条内容文件的副本，别的都不行 | Claude Code |
| 联网（一个网站） | 打开链接指向的网站，或你用 `webDomain` 指定的那个（链接跳转到别处不做检查） | Claude Code |

目前只验证过 Claude Code（2.1.301）。Codex、Gemini、Ollama 可以当作**自定义命令**来用
（`dropit handlers add --preset summary --agent custom -- <命令> [参数…]`），但 dropit 没有验证过它们的权限能被限制到什么程度，所以这些的限制要你自己来设。

**请记住**

- **收到的内容是不可信输入。** 持有你账号「只投递」密钥的人，也能把文字送到 agent 面前。所以默认不给任何工具，
  内容也只会走 agent 的标准输入，从不放进命令行。
- **第一次启用时会告诉你：** 所有符合规则的内容，都会交给你选的 agent；dropit 不经手、不存。
- 规则存在 `~/.config/dropit/agents.json`，和存放密钥的文件分开。这个文件读不了的时候，dropit 从不删除或覆盖它，只会告诉你，然后不去动它。

### 把每条内容交给你自己的命令

```bash
dropit watch ~/dropit --exec ./on-receive.sh --some-flag
```

`--exec` 后面的所有参数都是命令和它的参数，不经过 shell。每条新内容在文件写好之后只交一次，以**一行 JSON 走标准输入**；不重试，也不拖住下一条：

```json
{"v":1,"seq":44,"kind":"url","source":"chrome-extension","created":"2026-10-04T07:30:00.000Z","batch":null,
 "text":"https://example.com/airmail","meta":{"title":"How Airmail Worked"},"note":null,"files":[]}
```

`files` 里是收到的文件的绝对路径。一起投来的几样各是一行，`batch` 是 `{"id":…,"index":…,"count":…}`：想把它们合起来，就收集 `id` 相同的行，收够 `count` 行。**每个字段都是不可信输入**：绝不要把它拼进 shell 命令行。

## 在 Mac 上随手就投

终端是引擎，下面这几样把它放到一个按键的距离。

| | 得到什么 | 怎么设置 |
|---|---|---|
| **Raycast** | 「Send to dropit」投剪贴板里的文字；「Send File to dropit」按路径投一个文件。结果（`✓ 已投递 #46  → 全部 · 14:19`）直接显示在 Raycast 里 | Raycast → *Extensions → Script Commands → Add Directory* → 选 `cli/raycast/` |
| **在任何 App 里右键选中的文字** | 「服务 → Send to dropit」 | 见下文 |
| **Alfred** | 一个投剪贴板的快捷键 | 见下文 |

**服务菜单**（用「自动操作」）：

1. 打开「自动操作」→ *新建文稿* → **快速操作**。*工作流程收到当前* **文本**，位于 **任何应用程序**。
2. 加一个 **运行 Shell 脚本**，*传递输入*选 **作为自变量**，粘贴下面两行（路径以 `command -v dropit` 的结果为准）：

   ```bash
   export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
   "$HOME/.local/bin/dropit" send "$1"
   ```

3. 存为「Send to dropit」。在任何地方选中文字 → 右键 → *服务* → *Send to dropit*。
   在 *系统设置 → 键盘 → 键盘快捷键 → 服务* 里还能给它配一个快捷键。

这样投出去没有任何提示。想看到结果 —— ✓ 或者失败原因 —— 把第二行换成：

   ```bash
   "$HOME/.local/bin/dropit" send "$1" 2>&1 | xargs -0 osascript -e 'on run a' -e 'display notification (item 1 of a) with title "dropit"' -e 'end run'
   ```

**Alfred：** 建一个 Workflow，**Hotkey** 触发器连到 **Run Script**（`/bin/bash`）：
`export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"; pbpaste | "$HOME/.local/bin/dropit" send`。

在 Linux 上，把 `pbpaste` 换成 `wl-paste` 或 `xclip -o -selection clipboard` 来读剪贴板。

## 一分钟装好

```bash
curl -fsSL https://dropit.smart-kits.xyz/install.sh | sh    # 装进 ~/.local/bin，不用 sudo；需要 Node.js 18+
```

再运行一次就是更新。习惯用 git？`git clone https://github.com/smart-kits/dropit-client.git`，
把 `cli/dropit` 链接到 `PATH` 里的某个目录。

然后加入你的账号：

```bash
dropit pair K7M2QX     # 用一台已经在用的设备上的配对码（网页收件箱的「设备」页；Obsidian 的「设置 → dropit」）
dropit pair            # 只在你的第一台设备上用：创建账号
dropit me              # free · 2/3 台设备 · 1.2/30 MB · 单个文件最大 10 MB · 实时推送体验期：还剩 3 天
```

> 已经加入过的机器上直接运行 `dropit pair`，会停下来说明，不会把你切到一个空的新账号。
> 要加入另一个账号，带上它的配对码；确实要在这里另建一个账号，用 `dropit pair --new`。
> 如果这个网络今天已经建了很多账号，创建时可能要多等几秒（显示「正在准备账号…」），不会再把你打发去浏览器。

## 在这里管理账号

| 命令 | 作用 |
|---|---|
| `dropit code` | 给新设备生成 6 位配对码，5 分钟内有效 —— 在那台设备上输入，或在另一个终端里 `dropit pair <配对码>` |
| `dropit me` | 套餐、用了几台设备、用了多少空间、单个文件最大多少、实时推送开没开；下面一行是已用多少天、已投多少条；付费一年快到期或刚到期时再多一行 |
| `dropit devices` | 账号里的每一台设备：最近什么时候在线、哪台是这台、哪台只能投递，以及它的 ID |
| `dropit target` | 这台电脑上，投递默认发给哪些设备；`dropit target <设备名…\|all>` 设置它（见「投递」） |
| `dropit revoke <device_id>` | 立刻移除一台设备并释放名额 —— 丢了的笔记本、换下来的旧手机 |
| `dropit version` | 当前安装的版本 —— 每个版本在 GitHub 上都有对应的 Release（`cli-<版本号>`） |

配对码很宽容：大小写、空格、短横线，`0`/`O` 或 `1`/`I`/`l` 搞混都没关系。

## 出问题时

| 它说 | 意思是 | 怎么办 |
|---|---|---|
| `还没配对` | 这台机器还没加入 | `dropit pair <配对码>` |
| `token 无效` · `设备已被移除` | 这台机器被从账号里移除了 | 拿一个新配对码，`dropit pair <配对码>` |
| `配对码无效` · `配对码已过期` | 输错了、用过了，或者超过了 5 分钟 | 重新生成一个 |
| `设备数已达上限` | 套餐的设备名额用完了 | `dropit devices`，再 `dropit revoke <id>` 一台不用的 |
| `超过了单条上限 10 MB` | 文件超过了套餐的单个文件上限 | 投小一点的文件，或者投它的链接 |
| `网络不可用（试过 N 个地址）` | 没网，或者代理拦住了 | 检查网络或代理 |
| `Node.js < 22，没有全局 WebSocket` | 实时推送需要 Node.js 22+ | 升级 Node.js，或者定时运行 `watch` |

## 其他

- 设置存在 `~/.config/dropit/config.json`（只有你能读）：这台机器的钥匙、在队列里的进度、`watch` 的文件夹。
  千万别分享或提交它。
- 终端加入后是一台完整权限的设备：能投、能收、能添加设备。
- 输出默认英文，`LANG` 是中文时显示简体中文；用 `DROPIT_LANG=en` 或 `DROPIT_LANG=zh` 强制指定。
- 问题和建议：[提一个 issue](https://github.com/smart-kits/dropit-client/issues)（是公开的 —— 别贴 token，`dk_…`）。


## 其他设备

你的每台设备都有对应的 dropit 客户端，用配对码加入同一个账号。哪些能发、哪些能收、怎么搭配：
[所有客户端](../README.zh-CN.md#你的每台设备上)。或者在任何设备上打开 [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz)，它会推荐一个。

## 许可证

[MIT](../LICENSE)
