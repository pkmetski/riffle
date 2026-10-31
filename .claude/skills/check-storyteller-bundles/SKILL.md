---
name: check-storyteller-bundles
description: Use when the user asks about Storyteller readaloud bundle health, the processing queue, which books are broken or incomplete, or wants to enqueue books for re-alignment
---

# Check Storyteller Bundles

Server: `http://media-server:8001` — Auth: `POST /api/token` as multipart `username=test password=test` → `access_token`.

Run everything as a single Python script via Bash to minimise round-trips. Never use HEAD on `/api/books/{id}/synced` — it hangs (lazy bundle generation).

---

## 1. Health check (broken/incomplete bundles)

Fetch `GET /api/books?synced=true`, then for each call `GET /api/v2/books/{id}` AND range-fetch the zip central directory to get actual text file sizes. The manifest SMIL count matches the zip exactly (verified) — use the manifest. But classify using **substantial chapters** only.

**Substantial chapter** = text file in the zip > 10KB. Front matter (copyright, TOC, acknowledgements, cover, title page) is typically < 5KB and never gets SMIL — counting it as "missing" produces false positives.

**Algorithm:**
1. From manifest `readingOrder`, collect text chapters (`type == "application/xhtml+xml"`)
2. From manifest `resources`, collect SMIL hrefs (ends in `.smil`)
3. Range-fetch zip central directory (see zip range-extraction recipe below) to get uncompressed sizes of text files
4. `substantial_chapters` = text chapters whose zip file size > 10KB
5. `covered` = substantial chapters that have a corresponding SMIL (match by base filename without extension)

**Classify by `covered / substantial_chapters`:**
- **UNUSABLE** — 0 SMIL files at all
- **PARTIAL** — `covered < substantial * 0.7`
- **OK** — `covered ≥ substantial * 0.7` (do not report)

Also check `readaloud.status` and `readaloud.queuePosition`:
- QUEUED or PROCESSING → already being fixed
- ALIGNED but PARTIAL/UNUSABLE → needs manual enqueue

Report only broken books: `Book | SMIL | Substantial Ch | Covered | Queue | Verdict | Why`

**Zip range-extraction recipe** (avoids the hanging HEAD/full-download):
```python
# 1. Get file size: range-fetch bytes=0-0, read Content-Range header → total size
# 2. Fetch last 65536 bytes, find b'\x50\x4b\x05\x06' (EOCD signature)
# 3. Parse EOCD: struct.unpack_from('<IHHHHIIH', eocd, 0) → cd_size, cd_offset
# 4. Range-fetch central directory, parse entries:
#    struct.unpack_from('<IHHHHHHIIIHHHHHii', cd, pos) → uncomp_size, fname_len, etc.
# Never use HEAD on /synced — triggers full bundle generation and hangs.
```

**Important caveats:**
- Skip books with `queuePosition > 0` — their bundle may be deleted (restart=full wipes the cache), causing the range-fetch to hang
- The range-fetch times out when the server is under heavy transcription load (CPU-intensive). If most books time out, the server is busy — fall back to manifest-only SMIL/text ratio, or re-run later
- Manifest SMIL count is verified accurate (matches zip exactly) — safe to use as fallback when zip is unavailable

---

## 2. Full queue

Fetch `GET /api/books` (no filter — returns all 347 books including those never aligned). For each, check `readaloud.status`, `queuePosition`, `currentStage`, `restartPending`.

Sort by queue position. Show:
- **PROCESSING** — currently running (one at a time)
- **QUEUED** — waiting, with position
- **STOPPED** — failed, not re-queued
- **ALIGNED** with `queuePosition > 0` — re-queued for improvement

Note: `?synced=true` hides books being aligned for the first time — always use the unfiltered list for the full queue.

---

## 3. Enqueue a book for processing

```
POST /api/v2/books/{id}/process?restart=full
Authorization: Bearer {token}
```

Restart modes:
- `full` — delete all cache, re-transcribe and re-align from scratch (use this by default for broken books)
- `sync` — keep transcriptions, redo only the alignment step (faster, but won't fix transcription errors)
- omit — continue from where it stopped

To **change** restart mode on an already-queued book, cancel first then re-queue:
```
DELETE /api/v2/books/{id}/process   # cancel
POST   /api/v2/books/{id}/process?restart=full  # re-queue
```

The `test` user can call `/process` despite having `bookProcess: false` in its permissions — the endpoint does not enforce this check. `PUT /api/v2/books/{id}` returns 403 for non-admin users.

---

## Key facts

- `GET /api/books?synced=true` calls `getAlignedReadaloudBooks` — only books with a completed alignment. Books being aligned for the first time are invisible here.
- `readaloud.status` is unreliable: books show `ALIGNED` even with 0 SMIL files. Use SMIL count as the real health signal.
- `processing_status` on the list endpoint is always `null` — dead legacy field, ignore it.
- Queue positions are sparse (gaps are normal — cancelled items leave holes).
- Only one book processes at a time (`PROCESSING`); the rest wait as `QUEUED`.
