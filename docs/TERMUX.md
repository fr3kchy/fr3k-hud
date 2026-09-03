# Termux bridge (V2)

FR3K HUD treats Termux as a **controlled job executor**, not an unrestricted
remote shell. V2 will add a named-job mapping that prevents arbitrary command
execution while still allowing powerful automation.

## Job model

```json
{
  "job": "git.clone",
  "arguments": {"url": "https://..."}
}
```

The Termux plugin maps `git.clone` to a vetted `git` invocation with safe
defaults. Other jobs:

| Job id | Maps to |
|--------|---------|
| `git.clone` | `git clone <url> <dest>` |
| `git.pull` | `git pull` |
| `ssh.connect` | `ssh <host>` (with managed target) |
| `python.run_profile` | `python <script> --profile <name>` |
| `media.download` | `yt-dlp <url>` |
| `media.convert` | `ffmpeg -i <input> <output>` |
| `network.ping` | `ping -c 4 <host>` |
| `network.trace` | `traceroute <host>` |
| `file.hash` | `sha256sum <file>` |
| `repo.inspect` | `find . -type f \| head -100` |
| `ffmpeg.transcode` | `ffmpeg -i <input> -c:v libx264 -preset fast <output>` |

Each job runs with timeout, stdout/stderr capture, exit code propagation,
cancellation, and a job history log.

## Communication

Termux:API exposes a content provider + intent broadcast. V2 will use the
`com.termux.api.RunCommandService` and `com.termux.api.TextToSpeech` as
appropriate, never `RUN_COMMAND` directly without the user having opted in.

## V1 status

V1 reserves the slot in the capability tree (`termux.job`, `termux.script`,
`termux.ssh`) but does not implement the adapter. The registry hides the
capabilities until the plugin lands.