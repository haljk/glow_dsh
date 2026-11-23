# Image service reference

The contract this plugin is built against, written up from live probing rather
than from the service's own documentation. Where the two disagreed, the
behaviour below is what the service actually did.

The service is an OpenAI-style wrapper around **MiniMax-H3-Image**, a
guidance-distilled model (six steps, no CFG branch). One set of weights serves
both text-to-image and image editing: send reference images and it becomes an
editor.

There are now **two** services on the same host: the model itself on `:9741`
(blocking) and `image-job-queue` on `:9742` (asynchronous). This plugin talks to
the queue by default and keeps `:9741` as the rollback path.

## Endpoints (model, :9741)

| Method | Path | Body | Purpose |
| --- | --- | --- | --- |
| GET | `/health` | — | Status, weights, engine, size grid, concurrency state |
| GET | `/v1/models` | — | Single model id |
| POST | `/v1/images/generations` | JSON | Text to image |
| POST | `/v1/images/edits` | `multipart/form-data` | Edit / compose with 1–16 reference images |
| GET | `/images/{name}.png` | — | Fetch a result when `response_format=url` |

## What you can actually control

| Field | Real? | Notes |
| --- | --- | --- |
| `prompt` | yes | Chinese or English; must be non-empty (422 otherwise) |
| `size` | yes | `WxH`, both edges multiples of 32, 256–2048 |
| `n` | yes, clamped | Values above 2 are silently reduced to 2; seeds increment by one per image |
| `seed` | yes | Same prompt + same seed + same size reproduces the **byte-identical** image |
| `response_format` | yes | `url` or `b64_json` only |
| reference images | yes | 1–16, addressed in the prompt as `<Picture 1>`, `<Picture 2>`, … |
| `steps` | **no** | Fixed at 6; any other value returns 400 |
| `guidance` | **no** | Fixed at 1.0; any other value returns 400 |
| `negative_prompt` | **no** | Accepted and ignored — there is no negative branch |
| `quality`, `style`, any unknown field | **no** | Accepted and ignored |

`size` is forgiving in one specific way worth knowing: a non-multiple such as
`1000x1000` is **snapped** to `992x992` and the response carries
`"adjusted": true`. Out-of-range edges are rejected outright with a 400.

Because `negative_prompt` does nothing, anything the user wants excluded has to
be rewritten as a positive visual statement ("背景干净、纯白无缝背景、主体四周留白")
rather than passed as a negative.

## Editing with reference images

`/v1/images/edits` is `multipart` with `prompt`, one or more `image` parts, and
optionally `size`, `seed`, `response_format`. Reference images are numbered by
submit order, and the prompt refers to them by that number. Sending a
`<Picture n>` that has no matching image is not an error — the model simply has
nothing to point at, which wastes a five-minute run.

## Measured behaviour

Timings below come from an Apple-silicon machine with 64 GB unified memory, over
a private network. Treat them as an order of magnitude, not a promise.

| Job | Size | Wall time |
| --- | --- | --- |
| text-to-image | 256² | ~32 s |
| text-to-image | 512² | ~32 s |
| text-to-image | 992² | ~59 s |
| text-to-image | 1024² | ~72 s |
| text-to-image | 2048² | > 3 min |
| edit, 1 reference | 256² | ~156 s |
| edit, 1 reference | 1024² | ~220 s |
| edit, 1 reference | 1376×768 | ~227 s |
| edit, 2 references | 256² / 512² / 1024² | ~300–380 s |

Two things follow from this table:

- **An edit's cost is dominated by how many references it must read, not by canvas
  size.** Raising resolution from 256² to 1024² added roughly a minute; adding one
  more reference added roughly 150 s at every size. That is why this plugin lets
  you keep a large canvas while capping the reference count.
- **Small canvases are not a speed shortcut for edits — they destroy the picture.**
  At 256² a reference-image edit collapses into blurry colour blocks. The model was
  trained near 1024², so anything well under that is a preview, not a result.

## Concurrency and long waits

The model runs one generation at a time. A second concurrent request receives
**409** immediately, with no `Retry-After` header. That is no longer this
plugin's problem: the queue owns the single worker, and the plugin only ever
submits short requests. It still keeps the blocking path for rollback, where the
409 back-off still applies.

Long waits are the interesting hazard, because three defaults quietly kill them:

| Hazard | Symptom |
| --- | --- |
| Node's built-in `fetch` (undici) applies a **300 s** response-header timeout that no `AbortSignal` can extend | `TypeError: fetch failed`, typically at 300–306 s — right at the end of a two-reference edit |
| Holding the whole wait inside one HTTP response | Switching views, reloading, or any proxy timeout between client and host discards the work even though the server finished it |
| Keeping the task id only in memory | A restart loses a task that already cost five minutes of GPU time |

The plugin addresses all three: everything image-related goes through
`node:http` with short explicit timeouts, generations are background tasks the
page polls at 2.5 s, and unfinished task ids are written to `~/.glow/jobs.json`
so a restart resumes polling instead of starting over.

## Response shape

```json
{
  "created": 1795104451,
  "data": [
    {
      "url": "http://<service>/images/20261119-221340-53bc63fe.png",
      "revised_prompt": "…",
      "seed": 42,
      "seconds": 73.52,
      "size": "1024x1024"
    }
  ],
  "model": "minimax-h3-image",
  "engine": "h3-image-3-mlx",
  "steps": 6
}
```

`revised_prompt` is what the model says it used. It is worth surfacing: when an
edit looks wrong, the difference between the user's wording and this field tells
you whether the prompt was unclear or the model drifted.

## Authentication

None. The `/health` payload reports `auth: "any-key-accepted"`. The service is
meant for a private network or loopback, and this plugin assumes the same: it
binds no public endpoint and reaches the model service over plain HTTP.

## The queue service (:9742)

Confirmed against the running service on 2026-11-23, not inferred from prose.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/images/jobs` | Submit text-to-image → **202** + task object |
| POST | `/v1/images/jobs/edits` | Submit an edit (`multipart`, same field names) → **202** |
| GET | `/v1/images/jobs/{id}` | Status, progress, result list |
| GET | `/v1/images/jobs/{id}/result.png` | First image (`result-1.png` for the second) |
| POST | `/v1/images/jobs/{id}/cancel` | Cancel **only while queued**; running returns 409 |
| GET | `/v1/images/jobs?status=&limit=` | Recent tasks, for troubleshooting |
| GET | `/health` | Its own status + upstream status + queue depth |

A finished task looks like this (trimmed from a live response):

```json
{
  "id": "job_20261123-101450_52f465f7",
  "object": "image.job",
  "kind": "generation",
  "status": "succeeded",
  "request": { "prompt": "…", "size": "512x512", "seed": 11, "n": 1, "references": 0 },
  "progress": { "phase": "done", "percent": 100, "queue_position": null,
                "elapsed_seconds": 25.59, "eta_seconds": 0 },
  "error": null,
  "http_status": 200,
  "data": [ { "index": 0, "url": "http://<host>:9742/v1/images/jobs/<id>/result.png",
              "size": "512x512", "seed": 11, "seconds": 25.57, "revised_prompt": "…" } ],
  "model": "minimax-h3-image"
}
```

`status` is one of `queued`, `running`, `succeeded`, `failed`, `canceled`. A
failed task carries the upstream text in `error` (for example
`upstream transport error: [Errno 61] Connection refused`) and leaves
`http_status` null; a cancelled one carries neither.

Behaviour worth designing around:

- **Results expire.** `result.png` is kept for `result_retention_days` (7). Store
  the bytes yourself on success; a URL on its own is not a result.
- **The queue serialises.** One worker, FIFO. `progress.queue_position` is 1 for
  the next task, and `eta_seconds` is an estimate (elapsed ÷ historical average),
  capped at 95 % rather than a promise.
- **Backpressure is honest.** Submission beyond `capacity` (64) returns **429**
  with `Retry-After: 30`. Retrying the same body under the same
  `Idempotency-Key` header cannot produce a second image.
- **Poll is cheap; waiting is not the client's job.** Every request returns in
  about a second, so a 4 s poll interval costs nothing and no socket has to stay
  open for the five minutes an edit takes.
- `response_format` is accepted and ignored: the queue always answers with its
  own `result.png` URL.
- `Idempotency-Key` is a real header here (up to 128 chars); reuse it when
  retrying a submit after a network error.
