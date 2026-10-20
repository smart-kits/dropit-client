# 让 agent 读视频，以及更多

> **可选，只给想要的人。** 默认的 AI 处理不需要这些。适合这种情况：你分享了一条视频链接（抖音、TikTok、B 站、YouTube……），
> 想让 agent 根据视频里**说了什么**来处理，而不只是看标题。
> 这里的东西都不属于 dropit：是搭你自己工具的办法，dropit 只在下面列出的测试范围内验证过。
>
> 第 3 步里的权限设置是 **Claude Code 的**。如果你选的是别的 agent，请在它自己的设置里给它同样的权限：脚本本身和 agent 无关，任何能运行命令的 agent 都能用。

## 为什么默认做不到

默认情况下，agent 只能拿到链接的页面，别的什么都没有，而大多数视频网站对不是浏览器的访问只返回空页面。它能读到的只有分享文字里的标题和作者。
要拿到**说了什么**，得有东西把声音下载下来、转成文字。那是你自己的工具，由 agent 来运行，而且要允许 agent 运行它。

## 要搭什么

1. **三样工具**（装在这台电脑上，都不是 dropit 的）：
   - `ffmpeg` 和 `uv`（你可能已经有了）：`brew install ffmpeg uv`
   - `whisper-cpp`，在你自己的电脑上把语音转成文字：`brew install whisper-cpp`
   - 一个语音模型。`ggml-small-q5_1.bin`（181 MiB）是这份指南测试用的：
     ```bash
     mkdir -p ~/.local/share/video-text
     curl -L -o ~/.local/share/video-text/ggml-small-q5_1.bin \
       https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
     ```
     小一点的 `ggml-base-q5_1.bin`（57 MiB）只有三分之一大，但中文错得多（测试片段里把「个股」写成「各古」、把「轻仓」写成「晚仓」）。
     最大的几个（466 MiB 起）没有需要，也没试。
2. **一个小脚本 `video-text`。** 存成 `~/.local/bin/video-text`，再 `chmod +x`。它只接受一个链接、只接受名单里的网站；网站自带字幕就用字幕，
   没有就下载最小的音频、转成文字，用完把产生的东西全部删掉。名单和模型路径在脚本开头，可以改。
   ```bash
   #!/usr/bin/env bash
   # video-text <link>
   # Prints what is said in a video as text: the site's own subtitles when there are any, otherwise a local transcript.
   # Needs: uvx (uv), ffmpeg, whisper-cli (brew install whisper-cpp) and one whisper.cpp model file.
   set -euo pipefail
   # An app started from the Dock or the Start menu does not have your shell's PATH: add the usual places
   export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

   fail() { echo "video-text: $*" >&2; exit 1; }

   url="${1:-}"
   [ -n "$url" ] || fail "give one link"

   # The link is data from outside: only these sites, https only, and no shell ever sees it as code
   [[ "$url" =~ ^https://([A-Za-z0-9.-]+)(/|\?|$) ]] || fail "not an https link"
   host="${BASH_REMATCH[1]}"
   case "$host" in
     douyin.com|*.douyin.com|iesdouyin.com|*.iesdouyin.com|tiktok.com|*.tiktok.com|\
     bilibili.com|*.bilibili.com|b23.tv|xiaohongshu.com|*.xiaohongshu.com|xhslink.com|*.xhslink.com|\
     x.com|*.x.com|twitter.com|*.twitter.com|youtube.com|*.youtube.com|youtu.be|v.qq.com) ;;
     *) fail "this site is not on the list: $host" ;;
   esac

   model="${VIDEO_TEXT_MODEL:-$HOME/.local/share/video-text/ggml-small-q5_1.bin}"
   lang="${VIDEO_TEXT_LANG:-auto}"
   prompt="${VIDEO_TEXT_PROMPT:-以下是普通话的句子，使用简体中文。}"
   # Douyin is not in the released yt-dlp yet: a pinned, unmerged fix (reviewed: one file, talks only to douyin.com)
   douyin_fix="${VIDEO_TEXT_DOUYIN_YTDLP:-https://github.com/viking1304/yt-dlp/archive/edf5ca18f756b1a21f56c87aaf1aef7ef8d3520e.zip}"

   case "$host" in
     *douyin.com) ytdlp=(uvx --from "$douyin_fix" yt-dlp) ;;
     *)           ytdlp=(uvx yt-dlp) ;;
   esac
   common=(--no-warnings --no-playlist --max-filesize 800M)

   work="$(mktemp -d "${TMPDIR:-/tmp}/video-text.XXXXXX")"
   trap 'rm -rf "$work"' EXIT

   info="$("${ytdlp[@]}" "${common[@]}" --skip-download -j "$url" 2>"$work/err")" || fail "could not read this video: $(grep -i -E "error" "$work/err" | tail -n 1)"
   meta="$(printf '%s' "$info" | python3 -I -c '
   import sys, json
   d = json.loads(sys.stdin.read().strip().splitlines()[-1])
   m, s = divmod(int(d.get("duration") or 0), 60)
   print("title: %s\nauthor: %s\nlength: %d:%02d" % (d.get("title"), d.get("uploader"), m, s))
   ')"

   # 1. The site's own subtitles, when there are any
   "${ytdlp[@]}" "${common[@]}" --skip-download --write-subs --write-auto-subs --sub-langs 'zh.*,en.*' --convert-subs srt -o "$work/sub" "$url" >/dev/null 2>&1 || true
   srt="$(ls "$work"/sub*.srt 2>/dev/null | head -n 1 || true)"
   if [ -n "$srt" ]; then
     echo "$meta"; echo "source: subtitles"; echo
     grep -v -E '^[0-9]+$|-->|^$' "$srt"
     exit 0
   fi

   # 2. Otherwise the smallest audio there is, turned into text here
   [ -f "$model" ] || fail "no model file at $model"
   "${ytdlp[@]}" "${common[@]}" -f 'ba/b' -S '+size' -o "$work/media.%(ext)s" "$url" >/dev/null 2>"$work/err" \
     || fail "could not download: $(grep -i -E "error" "$work/err" | tail -n 1)"
   media="$(ls "$work"/media.* | head -n 1)"
   ffmpeg -loglevel error -y -i "$media" -vn -ac 1 -ar 16000 "$work/a.wav" || fail "could not read the audio"
   echo "$meta"; echo "source: local transcript (may contain mistakes, names and numbers especially)"; echo
   whisper-cli -m "$model" -l "$lang" --prompt "$prompt" -f "$work/a.wav" -nt -np 2>/dev/null
   ```
3. **只让 agent 运行它，别的都不放行。** 这条规则是无人值守的，没有谁能被询问：需要批准的操作都会被拒绝。
   在你的 Claude Code 设置里（`permissions.allow`）只放行这两条：
   ```text
   Bash(video-text *)
   WebFetch(domain:api.fxtwitter.com)
   ```
   第二条是给 x.com 的推文用的，agent 通过 `api.fxtwitter.com` 读（不用登录）。
4. **在 dropit 里**，打开规则，勾上*不限制 agent*（Obsidian），或运行 `dropit handlers set <规则> mode=open`（命令行），
   并把时间限制调大：30 分钟的视频需要比默认 120 秒更长（`timeoutSec=1800`）。
5. **在提示词里告诉 agent**，用你自己的话，例如：*「遇到视频链接，运行 `video-text <链接>`，根据它输出的文字来处理。它说读不了就如实说。」*

## 测过什么，没测什么

在一台机器上测的（Apple Silicon 的 Mac），2026 年 10 月：

| | 结果 |
|---|---|
| 抖音，一条 3 分钟的视频 | 可以。4 MB 的下载用了 3 秒；191 秒的语音用 181 MiB 的模型转成文字，热机后用了 5.7 秒（最早的第一次用小模型跑，花了 15 秒）。人名和数字有错，还漏了几句话。够用来做摘要，不能当原话引用。 |
| B 站，一条 3.5 分钟的视频 | 可以（英文歌）。不登录没有字幕，所以用的是本地转写。 |
| TikTok，一条 10 秒的视频 | 整条流程都跑通了（信息、下载、抽音频、转写）。这段只有音乐，所以它说了什么**没有**核对。 |
| x.com | 文字推文通过 `api.fxtwitter.com` 读全文。带视频的推文**没有**试。 |
| YouTube，一条 19 秒的视频 | 可以，整条流程。文字和说的内容一致（英文）。 |
| 小红书 | **不清楚。** yt-dlp 自己测试里的两条链接现在都跳到「页面不见了」，什么也说明不了。还没有试过一条有效的链接。 |
| 腾讯视频 | **没有试。** |
| 微信视频号 | **没找到办法。** 页面对程序是空的，现有的工具要你的账号或要装根证书。 |

没有测的：15 到 30 分钟的视频（耗时和准确度），以及 token 开销（粗略估计 30 分钟的讲话读进去约一万，再加 agent 自己的开销）。

## 要知道的

- **抖音还没进正式版 yt-dlp。** 脚本用的是一个还没合并的修复（只改一个文件，只访问 douyin.com；固定在一个提交上，不会在你不知道时变化）。合并之后把固定去掉。
  网站经常改版：某个站点不能用了，运行 `uvx --refresh yt-dlp --version` 更新工具。
- **转写结果可能有错。** 尤其是人名、数字和专业词。脚本在输出开头会说明这一点。
- **这是把限制拿掉了。** 勾上*不限制 agent* 之后，持有你账号「只投递」密钥的人，可以把一条链接送到一个能运行命令的 agent 面前。
  所以第 3 步只放行一个命令，脚本也只接受名单里的网站。不再用的设备请移除。
- 视频会在你自己的电脑上被短暂下载，用来转成文字。请确认你分享的内容允许这样做。
