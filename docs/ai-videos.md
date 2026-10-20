# Let the agent read videos, and more

> **Optional, and only for people who want it.** The default AI processing needs none of this. It is for when you share a video
> link (Douyin, TikTok, Bilibili, YouTube…) and want the agent to work from what is *said* in it, not just the title.
> Nothing here is part of dropit: it is a way to set up your own tools, and dropit has not checked it beyond the tests listed below.

## Why the default cannot do it

By default the agent gets a link's page and nothing else, and most video sites send an empty page to anything that is not a
browser. The share text of a video (title, author) is all it can read. To get *what is said*, something has to download the
sound and turn it into text. That is a tool of your own, run by the agent, and the agent needs to be allowed to run it.

## What you set up

1. **The three tools** (on this computer, none of them dropit's):
   - `ffmpeg` and `uv` (you may have them already): `brew install ffmpeg uv`
   - `whisper-cpp`, which turns speech into text on your own machine: `brew install whisper-cpp`
   - one speech model. `ggml-small-q5_1.bin` (181 MiB) is the one this guide was tested with:
     ```bash
     mkdir -p ~/.local/share/video-text
     curl -L -o ~/.local/share/video-text/ggml-small-q5_1.bin \
       https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
     ```
     A smaller one, `ggml-base-q5_1.bin` (57 MiB), is a third of the size but gets many Chinese words wrong (on the test clip it wrote
     「个股」as「各古」and 「轻仓」as「晚仓」). The biggest ones (466 MiB and up) were not needed and not tried.
2. **A small script, `video-text`.** Save it as `~/.local/bin/video-text` and `chmod +x` it. It takes one link, only from the
   sites on its list, uses the site's own subtitles when there are any, and otherwise downloads the smallest audio, turns it into text, and
   deletes everything it made. Change the list or the model path at the top if you like.
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
3. **Let the agent run it, and nothing else.** The run is unattended, so nothing can ask you: anything that needs approval is
   refused. In your Claude Code settings (`permissions.allow`) allow just these two:
   ```text
   Bash(video-text *)
   WebFetch(domain:api.fxtwitter.com)
   ```
   The second one is for posts on x.com, which the agent reads through `api.fxtwitter.com` (no login).
4. **In dropit,** open the rule, tick *Don't limit the agent* (Obsidian) or run `dropit handlers set <rule> mode=open` (terminal),
   and raise the time limit: a 30-minute video needs more than the default 120 seconds (`timeoutSec=1800`).
5. **Tell the agent in the prompt**, in your own words, for example: *"For a link to a video, run `video-text <link>` and work from the
   text it prints. If it says it cannot, say so."*

## What was tested, and what was not

Tested on one machine (Apple Silicon Mac), in October 2026:

| | Result |
|---|---|
| Douyin, a 3-minute video | Worked. The 4 MB download took 3 seconds; turning 191 seconds of speech into text took 5.7 seconds with the 181 MiB model once it was warm (the very first run, with the smaller model, took 15 seconds). Names and numbers had mistakes, and one passage of a few sentences was skipped. Good enough to summarise, not to quote. |
| Bilibili, a 3.5-minute video | Worked (English song). No subtitles without a login, so it used the local transcript. |
| TikTok, one 10-second video | The whole path ran (details, download, audio, transcript). The clip is only music, so what it says was **not** checked. |
| x.com | A text post is read in full through `api.fxtwitter.com`. A post with a video was **not** tried. |
| YouTube, a 19-second video | Worked, the whole path. The text matched what is said (English). |
| Xiaohongshu | **Unknown.** The two links in yt-dlp's own tests now lead to a "page not found" page, so they proved nothing. A live link has not been tried. |
| Tencent Video | **Not tried.** |
| WeChat Channels | **No way found.** The page is empty for programs, and the tools that exist need your account or a root certificate. |

Not measured: a video of 15 to 30 minutes (time and accuracy), and the cost in tokens (a rough guess is 10,000 for a 30-minute
speech, plus what the agent spends around it).

## Things to know

- **Douyin is not in the released yt-dlp yet.** The script uses a fix that has not been merged (one file, it talks only to
  douyin.com; pinned to one commit so it cannot change under you). When it is merged, take the pin out. Sites change often: if one
  stops working, `uvx --refresh yt-dlp --version` updates the tool.
- **The transcript can be wrong.** Names, numbers and technical words especially. The script says so at the top of what it prints.
- **This takes the limits off.** With *Don't limit the agent* on, anyone holding a send-only key for your account can put a link
  in front of an agent that can run commands. That is why step 3 allows one command and the script accepts only a list of sites.
  Remove devices you no longer use.
- Videos are downloaded for a moment, on your own machine, to be turned into text. Check that this is allowed for what you share.
