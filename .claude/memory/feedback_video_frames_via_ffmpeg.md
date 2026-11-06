---
name: feedback-video-frames-via-ffmpeg
description: "When the user shares a video file (.webm, .mp4, .mov), extract frames with ffmpeg instead of giving up on binary Read"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b5dd50a9-c97e-43b5-82bb-0f8d05c4061d
---

When the user shares a video file (e.g. `.webm`, `.mp4`, `.mov` screen recording) to demonstrate a bug, do NOT bail out because Read can't handle binary files. Use `ffmpeg` to extract frames and then Read those frames as images.

**Why:** The user explicitly told me to do this after I refused to look at a `.webm` screen recording. Videos are a primary way they share UI bugs.

**How to apply:**
- Extract frames at a reasonable rate (e.g. 1–2 fps) to a temp dir:
  `ffmpeg -i /path/to/video.webm -vf fps=2 /tmp/frame_%03d.png`
- For short clips, extracting every frame is fine; for long ones, sample (e.g. `fps=1`) or pick specific timestamps with `-ss`.
- Then use the Read tool on the resulting PNG/JPG files to actually see what's happening.
- Clean up the temp frames when done.
