/**
 * Glow（阁楼） — Host half.
 *
 * One implementation, two roles: the browser half calls these operations over
 * `webServer` routes (`/api/glow/...`), so the workbench owns no
 * duplicated logic in the Client.
 *
 * Generations run as background jobs, never as one long HTTP request. A 2K
 * edit takes minutes; if the wait lived in the response, switching panels,
 * reloading the page, or any request timeout in between would throw the result
 * away. The job queue is also the only place that serialises calls, because
 * the remote service accepts exactly one generation at a time.
 *
 * What this file accepts is bounded by what the remote service actually
 * enforces — see docs/DETAIL.md for the probe results.
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { mkdir, readdir, readFile, stat, unlink, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import http from 'node:http'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'

/** Services this plugin needs; without them it stays inactive instead of throwing. */
export const inject = ['webServer']

/**
 * Credentials-free defaults that are safe to publish. The real service
 * addresses live in the user's own settings file, edited from the panel.
 */
const DEFAULTS = {
  imageBaseUrl: 'http://127.0.0.1:9741',
  llmBaseUrl: 'http://127.0.0.1:9739',
  llmModel: 'default_model',
  outputDir: '',
  routePrefix: '/api/glow',
  imageTimeoutMs: 900000,
  llmTimeoutMs: 180000,
}

/** User settings live outside the repository, so they are never committed. */
const SETTINGS_DIR = path.join(os.homedir(), '.glow')
const SETTINGS_FILE = path.join(SETTINGS_DIR, 'settings.json')

/** Fields the panel may change; `routePrefix` is fixed at load time. */
const EDITABLE_FIELDS = ['imageBaseUrl', 'llmBaseUrl', 'llmModel', 'outputDir', 'imageTimeoutMs', 'llmTimeoutMs']

function normalizeServiceUrl(value, fallback) {
  const raw = String(value === undefined || value === null ? '' : value).trim()
  if (!raw) return fallback
  let url
  try {
    url = new URL(raw)
  } catch {
    throw new HttpError(400, `服务地址不合法：${raw}`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new HttpError(400, `服务地址必须是 http 或 https：${raw}`)
  }
  return `${url.origin}${url.pathname.replace(/\/+$/, '')}`
}

/** Merge one settings object over a base, validating every editable field. */
function resolveSettings(input, base = DEFAULTS) {
  const source = input && typeof input === 'object' ? input : {}
  const resolved = { ...base }
  resolved.imageBaseUrl = normalizeServiceUrl(source.imageBaseUrl, base.imageBaseUrl)
  resolved.llmBaseUrl = normalizeServiceUrl(source.llmBaseUrl, base.llmBaseUrl)
  const model = String(source.llmModel === undefined ? base.llmModel : source.llmModel).trim()
  resolved.llmModel = model || base.llmModel
  if (source.outputDir !== undefined) resolved.outputDir = String(source.outputDir || '').trim()
  resolved.imageTimeoutMs = clampInt(source.imageTimeoutMs, 30000, 3600000, base.imageTimeoutMs)
  resolved.llmTimeoutMs = clampInt(source.llmTimeoutMs, 10000, 600000, base.llmTimeoutMs)
  return resolved
}

/** Bounds the image service enforces; verified by probe, not assumed. */
const IMAGE_LIMITS = {
  minEdge: 256,
  maxEdge: 2048,
  maxEdgeWith4k: 4096,
  multipleOf: 32,
  maxBatch: 2,
  maxReferences: 16,
  steps: 6,
  guidance: 1,
}

const MAX_BODY_BYTES = 96 * 1024 * 1024
const MEDIA_TYPES = { '.png': 'image/png', '.json': 'application/json; charset=utf-8' }
const JOB_HISTORY_LIMIT = 40

/** Reusable prompts the user keeps around; a small cap keeps the list scannable. */
const FAVORITES_FILE = 'favorites.json'
const MAX_FAVORITES = 5

/**
 * How one reference image is meant to be used. `label` is what the user reads;
 * the composed prompt still uses the `<Picture n>` token the service requires.
 */
const ROLES = [
  { id: 'subject', label: '主体（要保留的人或物）' },
  { id: 'background', label: '背景' },
  { id: 'outfit', label: '服装' },
  { id: 'style', label: '色调与画风' },
  { id: 'scene', label: '构图与机位' },
  { id: 'detail', label: '局部细节' },
]

const ROLE_SENTENCE = {
  subject: (n) => `以 <Picture ${n}> 为主体，完整保留其身份、面部特征与五官比例、肤色、发型、表情、姿态与镜头角度`,
  background: (n) => `把背景替换为 <Picture ${n}> 中的场景`,
  outfit: (n) => `把人物服装替换为 <Picture ${n}> 中的服装，保留版型、材质与褶皱`,
  style: (n) => `画面色调、光线质感与整体画风参考 <Picture ${n}>`,
  scene: (n) => `构图与机位参考 <Picture ${n}>`,
  detail: (n) => `局部细节参考 <Picture ${n}>`,
}

/** Every edit keeps the untouched parts locked down; this is the core of the retouch presets. */
const PRESERVE_TAIL =
  '除上述明确指定的改动之外，其他一切保持不变：人物身份与面部特征、肤色与体型、表情与视线、发型、姿态与手部、服装其余部分、镜头角度与焦段、光照方向与强度、画面清晰度与噪点、整体色调；不增加也不删除任何物体，不改变画面构图与整体风格'

/**
 * Scenario presets. A non-technical user cannot reliably hand-write the
 * "change only this, keep everything else" instruction that photo retouching
 * needs, so each preset carries the exact wording plus its own caveat.
 */
const PRESETS = [
  {
    id: 'bg-replace',
    label: '换背景',
    summary: '保留人物，只换后面的场景',
    scene: 'retouch',
    // The new background may come from a second photo *or* from the user's own
    // words ("换到海边日落"), so only the subject is truly required.
    needs: ['subject'],
    optional: ['background'],
    instruction: '把人物从原背景中干净地分离出来，放到新的背景里',
    caution: '复杂发丝边缘可能出现毛边；用 1024² 及以上出图更稳。可以再选一张图当新背景，也可以直接用一句话描述新背景。',
  },
  {
    id: 'outfit-replace',
    label: '换衣服',
    summary: '保留人物、表情与背景，只换身上的衣服',
    scene: 'retouch',
    needs: ['subject'],
    optional: ['outfit'],
    instruction: '把人物身上的服装替换掉，其余部分不动',
    caution: '描述衣服时写清颜色、版型、材质，比说"好看一点"可靠得多。',
  },
  {
    id: 'hair-change',
    label: '换发型',
    summary: '只改头发，脸和其余部分不动',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '改变人物的发型与发色，脸部与身体其余部分完全不动',
    caution: '发型大幅改变（如长发变短发）时，模型可能连带微调脸型。',
  },
  {
    id: 'expression',
    label: '微调表情',
    summary: '只改表情，五官与身份不动',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '只调整人物的表情，五官形状、身份特征与姿态保持不变',
    caution: '表情改动越大，越可能被误读成换脸，建议幅度小一些。',
  },
  {
    id: 'light-change',
    label: '换光线或时段',
    summary: '白天改黄昏、室内改窗外光等，构图不变',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '只改变整体光线方向、色温与时段氛围，构图与所有物体位置不变',
    caution: '阴影方向要跟着新光源走，否则会显得假。',
  },
  {
    id: 'clean-up',
    label: '去掉路人或杂物',
    summary: '抹掉画面里多余的人或东西',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '移除画面中多余的路人、杂物与干扰元素，用合理的背景自然补上',
    caution: '被移走物体后面原本被挡住的内容是模型猜的，复杂处可能不自然。',
  },
  {
    id: 'cutout-white',
    label: '抠图换纯色底',
    summary: '去掉背景，换成纯白或指定底色，适合做图库与商品图',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '把背景完整移除，换成干净的纯色背景',
    caution: '半透明物体（玻璃、纱）与细发丝是难点，建议出图后放大检查边缘。',
  },
  {
    id: 'restore',
    label: '老照片修复与变清晰',
    summary: '去噪点、补细节、整体变清晰，不改变长相',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '修复照片的老化痕迹：去除噪点与划痕，提升清晰度与细节，但人物长相、神态与年代感必须保留',
    caution: '过度修复会让脸变得"塑料感"，建议一次只做一档。',
  },
  {
    id: 'id-photo',
    label: '证件照换底色',
    summary: '换成证件照标准底色，保持正面与五官端正',
    scene: 'retouch',
    needs: ['subject'],
    instruction: '处理成证件照样式：纯色背景、正面端正、光线均匀、五官清晰无遮挡',
    caution: '只做视觉处理，不等同于任何官方证件照规格，正式用途请按具体要求另拍。',
  },
]

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function defaultOutputDir() {
  return path.join(os.homedir(), 'Pictures', 'glow')
}

class HttpError extends Error {
  constructor(status, message, detail) {
    super(message)
    this.status = status
    this.detail = detail
  }
}

/** Service error bodies are `{"detail":...}` or `{"error":{...}}`; surface either. */
function describeServiceError(text) {
  try {
    const parsed = JSON.parse(text)
    if (typeof parsed.detail === 'string') return parsed.detail
    if (Array.isArray(parsed.detail)) {
      return parsed.detail.map((d) => `${(d.loc || []).join('.')}: ${d.msg}`).join('; ')
    }
    if (parsed.error) return parsed.error.message || JSON.stringify(parsed.error)
    return JSON.stringify(parsed).slice(0, 400)
  } catch {
    return String(text || '').slice(0, 400)
  }
}

function clampInt(value, min, max, fallback) {
  // Absent query parameters arrive as null and Number(null) is 0, which would
  // silently clamp to `min` instead of using the default.
  if (value === null || value === undefined || value === '') return fallback
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

function parsePositiveInt(value, fallback) {
  if (value === null || value === undefined || value === '') return fallback
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  const i = Math.trunc(n)
  return i >= 0 ? i : fallback
}

/** Snap an arbitrary edge to the service's grid and range. */
function snapEdge(value, maxEdge) {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return undefined
  const snapped = Math.round(n / IMAGE_LIMITS.multipleOf) * IMAGE_LIMITS.multipleOf
  if (snapped < IMAGE_LIMITS.minEdge) return IMAGE_LIMITS.minEdge
  if (snapped > maxEdge) return maxEdge
  return snapped
}

function normalizeSize(input, allow4k = false) {
  const maxEdge = allow4k ? IMAGE_LIMITS.maxEdgeWith4k : IMAGE_LIMITS.maxEdge
  const raw = String(input || '').trim()
  if (!raw) return { size: '1024x1024', width: 1024, height: 1024, adjusted: false }
  const match = /^(\d+)\s*[x×*]\s*(\d+)$/i.exec(raw)
  if (!match) {
    throw new HttpError(400, `尺寸格式应为 宽x高，例如 1024x1024（收到 "${raw}"）`)
  }
  const width = snapEdge(match[1], maxEdge)
  const height = snapEdge(match[2], maxEdge)
  const adjusted = String(width) !== match[1] || String(height) !== match[2]
  return { size: `${width}x${height}`, width, height, adjusted }
}

function stamp() {
  const d = new Date()
  const pad = (v, n = 2) => String(v).padStart(n, '0')
  return (
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  )
}

/** Reject anything that is not a bare basename of a file we serve. */
function safeEntryName(name) {
  const base = path.basename(String(name || ''))
  if (base !== name) return null
  if (!/^[A-Za-z0-9._-]+\.(png|json)$/.test(base)) return null
  return base
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        reject(new HttpError(413, '请求体过大（参考图合计上限 96 MB）'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

async function readJsonBody(req) {
  const raw = await readBody(req)
  if (raw.length === 0) return {}
  try {
    return JSON.parse(raw.toString('utf8'))
  } catch {
    throw new HttpError(400, '请求体不是合法 JSON')
  }
}

function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
  })
  res.end(body)
}

function decodeDataUrl(dataUrl) {
  const match = /^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=\s]+)$/.exec(String(dataUrl || ''))
  if (!match) throw new HttpError(400, '参考图必须是 PNG/JPEG/WebP 的 base64 data URL')
  return { mediaType: match[1], bytes: Buffer.from(match[2], 'base64') }
}

/**
 * A generation can keep the server silent for several minutes. Node's built-in
 * `fetch` (undici) applies its own 300 s response-header timeout that no
 * AbortSignal can extend, which is exactly long enough to kill a reference-image
 * edit. `node:http` has no such default, so every long call goes through here
 * and its only limit is the explicit deadline below.
 */
function httpRequest({ method, url, headers, body, timeoutMs }) {
  return new Promise((resolve, reject) => {
    let target
    try {
      target = new URL(url)
    } catch {
      reject(new HttpError(502, `地址不合法：${url}`))
      return
    }
    const isHttps = target.protocol === 'https:'
    const transport = isHttps ? https : http
    const req = transport.request(
      {
        method,
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || (isHttps ? 443 : 80),
        path: `${target.pathname}${target.search}`,
        headers: { ...headers, ...(body ? { 'content-length': body.length } : {}) },
        // Held open while the server works; the deadline below is the real limit.
        timeout: timeoutMs,
      },
      (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () =>
          resolve({ status: res.statusCode || 0, headers: res.headers, body: Buffer.concat(chunks) }),
        )
        res.on('error', reject)
      },
    )
    req.on('timeout', () => req.destroy(new Error('图像服务长时间没有响应')))
    req.on('error', (error) => reject(error))
    if (body) req.write(body)
    req.end()
  })
}

/** Build a multipart body by hand; FormData is only available through fetch. */
function buildMultipart(fields, files) {
  const boundary = `----mwb${randomUUID().replace(/-/g, '')}`
  const parts = []
  for (const [name, value] of Object.entries(fields)) {
    if (value === undefined || value === null) continue
    parts.push(
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${String(value)}\r\n`,
        'utf8',
      ),
    )
  }
  for (const file of files) {
    parts.push(
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="${file.name}"; filename="${file.filename}"\r\n` +
          `Content-Type: ${file.contentType}\r\n\r\n`,
        'utf8',
      ),
    )
    parts.push(file.bytes)
    parts.push(Buffer.from('\r\n', 'utf8'))
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`, 'utf8'))
  return { body: Buffer.concat(parts), contentType: `multipart/form-data; boundary=${boundary}` }
}

export function apply(ctx, config) {
  const inlineConfig = config && typeof config === 'object' ? config : {}

  /** Persisted, user-editable service settings; never part of the repo. */
  function readStoredSettings() {
    try {
      const parsed = JSON.parse(readFileSync(SETTINGS_FILE, 'utf8'))
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }

  let stored = readStoredSettings()
  let cfg = buildConfig()
  let outputDir = resolveOutputDir()

  function buildConfig() {
    const base = { ...DEFAULTS }
    if (inlineConfig.routePrefix) base.routePrefix = String(inlineConfig.routePrefix)
    // A hand-edited settings file must never brick activation: fall back to the
    // patch config, then to the built-in defaults.
    try {
      const resolved = resolveSettings({ ...inlineConfig, ...stored }, base)
      resolved.routePrefix = base.routePrefix
      return resolved
    } catch {
      try {
        const resolved = resolveSettings(inlineConfig, base)
        resolved.routePrefix = base.routePrefix
        return resolved
      } catch {
        return base
      }
    }
  }

  function resolveOutputDir() {
    return cfg.outputDir ? path.resolve(String(cfg.outputDir)) : defaultOutputDir()
  }

  /** id -> job; finished jobs are pruned lazily so the client can still read them. */
  const jobs = new Map()
  const queue = []
  let running = false
  let lastBusyNote = ''

  // --------------------------------------------------------------- settings

  function publicSettings() {
    return {
      settings: {
        imageBaseUrl: cfg.imageBaseUrl,
        llmBaseUrl: cfg.llmBaseUrl,
        llmModel: cfg.llmModel,
        outputDir: cfg.outputDir,
        imageTimeoutMs: cfg.imageTimeoutMs,
        llmTimeoutMs: cfg.llmTimeoutMs,
      },
      defaults: {
        imageBaseUrl: DEFAULTS.imageBaseUrl,
        llmBaseUrl: DEFAULTS.llmBaseUrl,
        llmModel: DEFAULTS.llmModel,
        imageTimeoutMs: DEFAULTS.imageTimeoutMs,
        llmTimeoutMs: DEFAULTS.llmTimeoutMs,
      },
      editable: EDITABLE_FIELDS,
      stored: { path: SETTINGS_FILE, overrides: stored },
      outputDir,
    }
  }

  /** Persist a partial settings patch, validate it, then apply it live. */
  function saveSettings(patch) {
    const next = { ...stored }
    for (const field of EDITABLE_FIELDS) {
      if (patch && Object.prototype.hasOwnProperty.call(patch, field)) next[field] = patch[field]
    }
    // Validate against the patch config before touching the file.
    resolveSettings({ ...inlineConfig, ...next }, DEFAULTS)
    try {
      mkdirSync(SETTINGS_DIR, { recursive: true })
      writeFileSync(SETTINGS_FILE, JSON.stringify(next, null, 2), 'utf8')
    } catch (error) {
      throw new HttpError(500, `设置写不进去：${error.message}`)
    }
    stored = next
    cfg = buildConfig()
    outputDir = resolveOutputDir()
    return publicSettings()
  }

  // ---------------------------------------------------------------- storage

  async function ensureDir() {
    await mkdir(outputDir, { recursive: true })
    return outputDir
  }

  async function saveItem(item) {
    const dir = await ensureDir()
    const base = `${stamp()}-${item.seed}`
    let name = `${base}.png`
    let suffix = 1
    // Same second + same seed must not overwrite a previous result.
    for (;;) {
      try {
        await stat(path.join(dir, name))
        name = `${base}-${suffix++}.png`
      } catch {
        break
      }
    }
    await writeFile(path.join(dir, name), item.png)

    // Keep a copy of what the user fed in, so "before / after" still works
    // from the gallery after a reload — the page no longer holds those photos.
    const refFiles = []
    if (Array.isArray(item.refImages)) {
      for (let index = 0; index < item.refImages.length; index++) {
        const refName = name.replace(/\.png$/, `.ref-${index + 1}.png`)
        try {
          await writeFile(path.join(dir, refName), item.refImages[index].bytes)
          refFiles.push(refName)
        } catch {
          /* a comparison copy is a convenience; never fail the result for it */
        }
      }
    }

    const meta = {
      name,
      mode: item.mode,
      prompt: item.prompt,
      revisedPrompt: item.revisedPrompt || '',
      // The user's own wording, so "reuse" can refill the note instead of the
      // already-composed instruction (which would be wrapped a second time).
      note: item.note || '',
      seed: item.seed,
      size: item.size,
      seconds: item.seconds ?? null,
      references: item.references ?? 0,
      presetId: item.presetId || '',
      refFiles,
      createdAt: Date.now(),
    }
    await writeFile(path.join(dir, name.replace(/\.png$/, '.json')), JSON.stringify(meta, null, 2), 'utf8')
    return meta
  }

  /**
   * Scan the output directory.
   * @param limit - how many entries to return after filtering.
   * @param archived - '0' active only (default), '1' archived only, 'all'.
   * @returns matching entries plus counts for both groups, so the UI can label
   *          the archive entry without a second scan.
   */
  async function listHistory(limit = 60, archived = '0') {
    const dir = await ensureDir()
    let entries = []
    try {
      entries = await readdir(dir)
    } catch {
      return { items: [], counts: { active: 0, archived: 0 } }
    }
    const active = []
    const hidden = []
    for (const name of entries) {
      if (!name.endsWith('.json')) continue
      if (name === FAVORITES_FILE) continue
      try {
        const text = await readFile(path.join(dir, name), 'utf8')
        const meta = JSON.parse(text)
        if (!meta || typeof meta.name !== 'string' || !meta.name.endsWith('.png')) continue
        if (meta.archived) hidden.push(meta)
        else active.push(meta)
      } catch {
        /* a corrupt sidecar must not hide the rest of the gallery */
      }
    }
    const byNewest = (a, b) => (b.createdAt || 0) - (a.createdAt || 0)
    active.sort(byNewest)
    hidden.sort(byNewest)
    const counts = { active: active.length, archived: hidden.length }
    if (archived === '1') return { items: hidden.slice(0, limit), counts }
    if (archived === 'all') return { items: active.concat(hidden).sort(byNewest).slice(0, limit), counts }
    return { items: active.slice(0, limit), counts }
  }

  // -------------------------------------------------------------- favourites

  function favoritesPath() {
    return path.join(outputDir, FAVORITES_FILE)
  }

  async function loadFavorites() {
    try {
      const raw = await readFile(favoritesPath(), 'utf8')
      const parsed = JSON.parse(raw)
      const list = Array.isArray(parsed?.items) ? parsed.items : []
      return list
        .filter((item) => item && typeof item.text === 'string' && item.text.trim())
        .slice(0, MAX_FAVORITES)
        .map((item) => ({
          id: String(item.id || ''),
          text: String(item.text),
          mode: item.mode === 'image2image' ? 'image2image' : 'text2image',
          createdAt: Number(item.createdAt) || 0,
        }))
    } catch {
      return []
    }
  }

  async function writeFavorites(items) {
    await ensureDir()
    await writeFile(favoritesPath(), JSON.stringify({ version: 1, items }, null, 2), 'utf8')
    return items
  }

  // --------------------------------------------------------------- services

  async function probeImageService(baseUrl = cfg.imageBaseUrl) {
    const res = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(8000) })
    if (!res.ok) throw new HttpError(502, `图像服务 /health 返回 HTTP ${res.status}`)
    const health = await res.json()
    const sizes = health.sizes || {}
    return {
      ok: health.status === 'ok',
      model: health.model,
      engine: health.engine,
      quantization: health.quantization,
      steps: health.steps ?? IMAGE_LIMITS.steps,
      guidance: health.guidance ?? IMAGE_LIMITS.guidance,
      referenceLimit: health.reference_limit ?? IMAGE_LIMITS.maxReferences,
      maxEdge: sizes.max_edge ?? IMAGE_LIMITS.maxEdge,
      multipleOf: sizes.multiple_of ?? IMAGE_LIMITS.multipleOf,
      defaultSize: sizes.default ?? '1024x1024',
      allow4k: Boolean(health.allow_4k),
      busy: Boolean(health.generation_lock_busy),
      requests: health.requests ?? 0,
      errors: health.errors ?? 0,
      busyRejections: health.busy_rejections ?? 0,
      lastSeconds: health.last_generation_seconds ?? null,
    }
  }

  async function probeLlmService(baseUrl = cfg.llmBaseUrl) {
    try {
      const res = await fetch(`${baseUrl}/health`, { signal: AbortSignal.timeout(8000) })
      return { ok: res.ok, status: res.status }
    } catch (error) {
      return { ok: false, status: 0, error: error.message }
    }
  }

  /**
   * POST to the image service over `node:http`. Our own queue already
   * serialises calls, so a 409 here means something else is using the service;
   * back off briefly and retry before surfacing a busy error.
   */
  async function postImageService(pathname, init, timeoutMs) {
    const deadline = Date.now() + timeoutMs
    let attempt = 0
    for (;;) {
      const remaining = deadline - Date.now()
      if (remaining <= 1000) throw new HttpError(504, '等待图像服务超时')
      let res
      try {
        res = await httpRequest({
          method: init.method || 'POST',
          url: `${cfg.imageBaseUrl}${pathname}`,
          headers: init.headers,
          body: init.body,
          timeoutMs: remaining,
        })
      } catch (error) {
        if (error instanceof HttpError) throw error
        throw new HttpError(502, `无法连接图像服务 ${cfg.imageBaseUrl}：${error.message}`)
      }
      const text = res.body.toString('utf8')
      if (res.status >= 200 && res.status < 300) {
        try {
          return JSON.parse(text)
        } catch {
          throw new HttpError(502, '图像服务返回了非 JSON 响应')
        }
      }
      if (res.status === 409 && attempt < 3 && deadline - Date.now() > 120000) {
        attempt += 1
        lastBusyNote = `图像服务被其它任务占用，第 ${attempt} 次退避重试`
        await sleep(20000)
        continue
      }
      if (res.status === 409) {
        throw new HttpError(409, '图像服务正忙（同一时刻只能跑一张），请稍后重试')
      }
      throw new HttpError(res.status === 400 || res.status === 422 ? 400 : 502, describeServiceError(text))
    }
  }

  async function downloadResult(url) {
    const absolute = /^https?:\/\//i.test(url) ? url : new URL(url, cfg.imageBaseUrl).toString()
    let res
    try {
      res = await httpRequest({ method: 'GET', url: absolute, timeoutMs: 180000 })
    } catch (error) {
      throw new HttpError(502, `下载生成结果失败：${error.message}`)
    }
    if (res.status < 200 || res.status >= 300) throw new HttpError(502, `下载生成结果失败：HTTP ${res.status}`)
    return res.body
  }

  async function collectItems(response, request) {
    const list = Array.isArray(response?.data) ? response.data : []
    if (list.length === 0) throw new HttpError(502, '图像服务未返回任何结果')
    const saved = []
    for (const item of list) {
      const png = item.b64_json
        ? Buffer.from(item.b64_json, 'base64')
        : await downloadResult(item.url)
      saved.push(
        await saveItem({
          png,
          mode: request.mode,
          prompt: request.prompt,
          revisedPrompt: item.revised_prompt,
          seed: item.seed,
          size: item.size || request.size,
          seconds: item.seconds,
          references: item.references ?? request.references ?? 0,
          presetId: request.presetId,
          note: request.note,
          refImages: request.refImages,
        }),
      )
    }
    return {
      items: saved.map((meta) => ({
        ...meta,
        fileUrl: `${cfg.routePrefix}/file/${encodeURIComponent(meta.name)}`,
      })),
      savedTo: outputDir,
      engine: response.engine,
      model: response.model,
      steps: response.steps,
    }
  }

  // ------------------------------------------------------------- edit prompts

  function presetById(id) {
    if (!id) return null
    const found = PRESETS.find((preset) => preset.id === id)
    if (!found) throw new HttpError(400, `未知的预设：${id}`)
    return found
  }

  /**
   * Build the instruction sent to the model from friendly inputs: what each
   * reference is for, which preset was picked, and the user's own note.
   */
  function composeEditPrompt(input) {
    const preset = presetById(input.presetId)
    const refs = Array.isArray(input.refs) ? input.refs : []
    const note = String(input.note || '').trim()

    if (refs.length === 0) throw new HttpError(400, '请先添加至少一张参考图')
    if (refs.length > IMAGE_LIMITS.maxReferences) {
      throw new HttpError(400, `参考图最多 ${IMAGE_LIMITS.maxReferences} 张`)
    }
    const roles = refs.map((ref) => (ROLE_SENTENCE[ref.role] ? ref.role : 'detail'))

    if (preset) {
      const missing = preset.needs.filter((role) => !roles.includes(role))
      if (missing.length > 0) {
        const names = missing.map((role) => (ROLES.find((r) => r.id === role) || { label: role }).label)
        throw new HttpError(400, `「${preset.label}」还需要指定：${names.join('、')}`)
      }
    }

    const parts = []
    if (preset) parts.push(preset.instruction)
    roles.forEach((role, index) => {
      parts.push(ROLE_SENTENCE[role](index + 1))
    })
    if (note) parts.push(`另外，${note}`)

    const touchesSubject = roles.includes('subject')
    if (preset || touchesSubject) parts.push(PRESERVE_TAIL)

    return {
      prompt: parts.join('；') + '。',
      presetId: preset ? preset.id : '',
      references: refs.length,
      roles,
    }
  }

  // -------------------------------------------------------------- job queue

  function jobView(job) {
    const now = Date.now()
    const queueIndex = queue.indexOf(job.id)
    return {
      id: job.id,
      kind: job.kind,
      status: job.status,
      label: job.label,
      createdAt: job.createdAt,
      startedAt: job.startedAt || null,
      finishedAt: job.finishedAt || null,
      elapsedSeconds:
        job.status === 'running' && job.startedAt
          ? Math.round((now - job.startedAt) / 1000)
          : job.startedAt && job.finishedAt
            ? Math.round((job.finishedAt - job.startedAt) / 1000)
            : 0,
      waitedSeconds: Math.round((now - job.createdAt) / 1000),
      queuePosition: job.status === 'queued' ? queueIndex + (running ? 2 : 1) : 0,
      request: job.request,
      items: job.items || [],
      error: job.error || '',
      savedTo: job.savedTo || outputDir,
    }
  }

  function pruneJobs() {
    const finished = [...jobs.values()]
      .filter((job) => job.status === 'done' || job.status === 'failed')
      .sort((a, b) => (b.finishedAt || 0) - (a.finishedAt || 0))
    for (const stale of finished.slice(JOB_HISTORY_LIMIT)) jobs.delete(stale.id)
  }

  async function pump() {
    if (running) return
    const id = queue.shift()
    if (id === undefined) return
    const job = jobs.get(id)
    if (!job || !job._work) return
    running = true
    job.status = 'running'
    job.startedAt = Date.now()
    try {
      const result = await job._work()
      job.items = result.items
      job.savedTo = result.savedTo
      job.status = 'done'
    } catch (error) {
      job.status = 'failed'
      job.error = error.message || '生成失败'
    } finally {
      job.finishedAt = Date.now()
      delete job._work
      running = false
      pruneJobs()
      void pump()
    }
  }

  function enqueue(job, work) {
    job._work = work
    jobs.set(job.id, job)
    queue.push(job.id)
    void pump()
    return jobView(job)
  }

  function newJobId() {
    return `job-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  }

  // ---------------------------------------------------------------- dispatch

  async function runGenerate(body) {
    const prompt = String(body?.prompt || '').trim()
    if (!prompt) throw new HttpError(400, '请先写提示词')
    const conservative = normalizeSize(body?.size, false)
    const caps = await probeImageService().catch(() => null)
    const bounds = caps && caps.allow4k ? normalizeSize(body?.size, true) : conservative
    const batch = clampInt(body?.n, 1, IMAGE_LIMITS.maxBatch, 1)
    const seed = parsePositiveInt(body?.seed, undefined)

    const payload = {
      model: 'minimax-h3-image',
      prompt,
      size: bounds.size,
      n: batch,
      response_format: 'url',
    }
    if (seed !== undefined) payload.seed = seed

    const job = {
      id: newJobId(),
      kind: 'text2image',
      status: 'queued',
      label: prompt.slice(0, 40),
      createdAt: Date.now(),
      request: { mode: 'text2image', prompt, size: bounds.size, batch, seed: seed ?? null, presetId: '' },
    }

    return enqueue(job, async () => {
      const response = await postImageService(
        '/v1/images/generations',
        {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: Buffer.from(JSON.stringify(payload), 'utf8'),
        },
        cfg.imageTimeoutMs,
      )
      return collectItems(response, { mode: 'text2image', prompt, size: bounds.size })
    })
  }

  async function runEdit(body) {
    const refs = Array.isArray(body?.refs) ? body.refs : []
    const freeform = String(body?.prompt || '').trim()
    const noteText = String(body?.note || '').trim()

    let composed
    if (freeform && refs.length === 0) throw new HttpError(400, '图生图至少需要 1 张参考图')
    if (freeform && body?.presetId === undefined && !body?.note) {
      // Advanced path: the caller already wrote the whole instruction.
      if (refs.length === 0) throw new HttpError(400, '图生图至少需要 1 张参考图')
      composed = { prompt: freeform, presetId: '', references: refs.length, roles: refs.map((r) => r.role || 'detail') }
    } else {
      if (!body?.presetId && !noteText) {
        // Without either, the composition is "keep everything as it is", which
        // would spend minutes to return the input photo unchanged.
        throw new HttpError(400, '请说明想改什么，或者选择一个处理方式')
      }
      composed = composeEditPrompt({ refs, presetId: body?.presetId, note: body?.note })
    }

    const conservative = normalizeSize(body?.size, false)
    const caps = await probeImageService().catch(() => null)
    const bounds = caps && caps.allow4k ? normalizeSize(body?.size, true) : conservative
    const seed = parsePositiveInt(body?.seed, undefined)

    // Decode once at submit time; the queue must not keep the request object alive.
    const images = refs.map((ref) => decodeDataUrl(ref.dataUrl))
    const preset = presetById(body?.presetId)

    const job = {
      id: newJobId(),
      kind: 'image2image',
      status: 'queued',
      label: (preset ? preset.label + ' · ' : '') + composed.prompt.slice(0, 36),
      createdAt: Date.now(),
      request: {
        mode: 'image2image',
        prompt: composed.prompt,
        note: noteText,
        size: bounds.size,
        batch: 1,
        seed: seed ?? null,
        presetId: composed.presetId,
        references: images.length,
        roles: composed.roles,
      },
    }

    return enqueue(job, async () => {
      const multipart = buildMultipart(
        {
          prompt: composed.prompt,
          size: bounds.size,
          response_format: 'url',
          seed: seed !== undefined ? String(seed) : undefined,
        },
        images.map((decoded, index) => ({
          name: 'image',
          filename: `reference-${index + 1}.${
            decoded.mediaType === 'image/jpeg' ? 'jpg' : decoded.mediaType === 'image/webp' ? 'webp' : 'png'
          }`,
          contentType: decoded.mediaType,
          bytes: decoded.bytes,
        })),
      )
      const response = await postImageService(
        '/v1/images/edits',
        { method: 'POST', headers: { 'content-type': multipart.contentType }, body: multipart.body },
        cfg.imageTimeoutMs,
      )
      return collectItems(response, {
        mode: 'image2image',
        prompt: composed.prompt,
        size: bounds.size,
        references: images.length,
        presetId: composed.presetId,
        note: noteText,
        refImages: images,
      })
    })
  }

  // ----------------------------------------------------------- prompt assist

  const PROMPT_RULES = [
    '你是一位图像生成提示词工程师，服务于 MiniMax H3-Image 模型。',
    '该模型是 6 步引导蒸馏模型：没有 CFG 负向分支，负向提示词会被完全忽略。',
    '硬性规则：',
    '1. 只输出提示词正文，不要解释、不要标题、不要编号、不要用引号把整段包起来。',
    '2. 必须写到可观察的视觉事实：主体数量与材质、环境与背景、光线方向与质感、色调、构图与镜头、画面风格。',
    '3. 禁止空话（"高质量""杰作""8K 超清"），替换为具体描述，例如"柔和的侧逆光在釉面留下高光""浅景深虚化背景"。',
    '4. 所有排除要求必须改写为正向视觉陈述：不说"不要杂乱"，而说"背景干净，纯白色无缝背景，主体四周留白"；不得出现"不要/避免/没有/no/without/avoid"这类否定句式。',
    '5. 需要在画面中出现文字时，用双引号原样写出文字内容，并说明位置与字体风格。',
  ].join('\n')

  const EDIT_RULES = [
    '你是一位图片修饰指令撰写助手，服务于一个图像编辑模型。',
    '用户不是专业人士，会给出模糊的要求；你的任务是把要求写成一条精确、可执行的编辑指令。',
    '硬性规则：',
    '1. 只输出指令正文，不要解释、不要分点、不要用引号包裹。',
    '2. 必须明确指出"只改哪里"，并用一句话锁定"其余全部不变"：人物身份与面部特征、五官比例、肤色、体型、表情、发型、姿态、镜头角度、光照方向、清晰度、构图。',
    '3. 参考图按顺序用 <Picture 1>、<Picture 2> 指代，写清每张图被引用作什么用途。',
    '4. 不得出现否定句式（不要/避免/没有/no/without/avoid）；需要排除的东西改写为正向描述。',
    '5. 不得编造用户没提供的参考图编号，也不得超出用户给定的图片数量。',
    '6. 长度控制在 40–120 字。',
  ].join('\n')

  function buildAssistMessages(input) {
    const mode = String(input?.mode || 'expand')
    const idea = String(input?.idea || '').trim()
    const style = String(input?.style || '').trim()
    const avoid = String(input?.avoid || '').trim()
    const language = String(input?.language || 'zh')
    const count = clampInt(input?.count, 1, 4, 3)
    if (!idea) throw new HttpError(400, '请先写一句画面想法')

    if (mode === 'edit-instruction') {
      const roles = Array.isArray(input?.roles) ? input.roles : []
      const roleLines = roles.map((role, index) => {
        const found = ROLES.find((r) => r.id === role)
        return `图${index + 1}（<Picture ${index + 1}>）用途：${found ? found.label : role}`
      })
      const preset = PRESETS.find((p) => p.id === input?.presetId)
      return [
        { role: 'system', content: EDIT_RULES },
        {
          role: 'user',
          content: [
            preset ? `用户选择的处理类型：${preset.label}（${preset.summary}）` : '用户没有选择预设，按描述判断。',
            roleLines.length ? `参考图共 ${roles.length} 张：\n${roleLines.join('\n')}` : '没有参考图。',
            `用户的原始要求：${idea}`,
          ].join('\n'),
        },
      ]
    }

    const langLine =
      language === 'en'
        ? '用英文输出提示词，单词数 40–120。'
        : language === 'zh'
          ? '用中文输出提示词，长度 80–220 字。'
          : '用中英双语各输出一条，先中文后英文。'

    const context = []
    if (style) context.push(`目标风格：${style}`)
    if (avoid) context.push(`需要规避的元素（必须改写成正向陈述）：${avoid}`)

    const task = {
      expand: '把下面这句想法扩写成一条完整、可直接使用的生图提示词。',
      refine: '在保留原意的前提下重写这条提示词：让它更具体、更可拍，去掉空话与否定句。',
      variations: `基于这句想法，给出 ${count} 条风格差异明显的生图提示词（例如不同的光线、构图、材质、画风）。只输出一个 JSON 数组，元素是字符串，不要输出其他任何内容。`,
    }[mode]
    if (!task) throw new HttpError(400, `未知的提示词模式：${mode}`)

    return [
      { role: 'system', content: PROMPT_RULES },
      {
        role: 'user',
        content: [task, langLine, ...context, '', `想法/原提示词：${idea}`].join('\n'),
      },
    ]
  }

  async function chatCompletion(messages, options = {}) {
    const body = {
      model: cfg.llmModel,
      messages,
      max_tokens: options.maxTokens ?? 1200,
      temperature: options.temperature ?? 0.85,
      chat_template_kwargs: { enable_thinking: false },
    }
    let res
    try {
      res = await fetch(`${cfg.llmBaseUrl}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(cfg.llmTimeoutMs),
      })
    } catch (error) {
      if (error.name === 'TimeoutError') throw new HttpError(504, '本地大模型响应超时')
      throw new HttpError(502, `无法连接本地大模型 ${cfg.llmBaseUrl}：${error.message}`)
    }
    const text = await res.text()
    if (!res.ok) throw new HttpError(502, describeServiceError(text))
    let parsed
    try {
      parsed = JSON.parse(text)
    } catch {
      throw new HttpError(502, '本地大模型返回了非 JSON 响应')
    }
    const content = parsed?.choices?.[0]?.message?.content
    if (!content || !String(content).trim()) {
      // Surface it as something the user can act on, not as a parameter name.
      throw new HttpError(502, '写作助手这次没有写出内容，请再点一次')
    }
    return { content: String(content).trim(), usage: parsed.usage ?? null }
  }

  function stripFence(text) {
    return text.replace(/^\s*```[a-zA-Z]*\s*/m, '').replace(/```\s*$/m, '').trim()
  }

  function parseVariations(text) {
    const cleaned = stripFence(text)
    const start = cleaned.indexOf('[')
    const end = cleaned.lastIndexOf(']')
    if (start !== -1 && end > start) {
      try {
        const parsed = JSON.parse(cleaned.slice(start, end + 1))
        if (Array.isArray(parsed)) {
          const strings = parsed.filter((v) => typeof v === 'string' && v.trim()).map((v) => v.trim())
          if (strings.length > 0) return strings
        }
      } catch {
        /* fall through to line splitting */
      }
    }
    return cleaned
      .split(/\r?\n+/)
      .map((line) => line.replace(/^\s*(?:[-*\d]+[.)]?\s*)/, '').trim())
      .filter((line) => line.length > 8)
  }

  async function assistPrompt(input) {
    const messages = buildAssistMessages(input)
    const { content, usage } = await chatCompletion(messages)
    if (String(input?.mode) === 'variations') {
      // The model tends to over-deliver; honour the requested count exactly.
      const count = clampInt(input?.count, 1, 4, 3)
      return { mode: 'variations', prompts: parseVariations(content).slice(0, count), raw: content, usage }
    }
    if (String(input?.mode) === 'edit-instruction') {
      return { mode: 'edit-instruction', instruction: stripFence(content), raw: content, usage }
    }
    return { mode: String(input?.mode || 'expand'), prompt: stripFence(content), raw: content, usage }
  }

  function previewEditPrompt(input) {
    const composed = composeEditPrompt(input)
    return { prompt: composed.prompt, presetId: composed.presetId, references: composed.references, roles: composed.roles }
  }

  // ------------------------------------------------------------------ routes

  const prefix = String(cfg.routePrefix).replace(/\/+$/, '')

  async function handleFile(req, res, name) {
    const safe = safeEntryName(name)
    if (!safe) throw new HttpError(400, '非法的文件名')
    const target = path.join(outputDir, safe)
    let bytes
    try {
      bytes = await readFile(target)
    } catch {
      throw new HttpError(404, '文件不存在')
    }
    const ext = path.extname(safe).toLowerCase()
    res.writeHead(200, {
      'content-type': MEDIA_TYPES[ext] || 'application/octet-stream',
      'content-length': bytes.length,
      'cache-control': 'public, max-age=31536000, immutable',
    })
    res.end(req.method === 'HEAD' ? undefined : bytes)
  }

  async function route(req, res) {
    const url = new URL(req.url || '/', 'http://127.0.0.1')
    const sub = url.pathname.slice(prefix.length).replace(/^\/+/, '')
    const method = req.method || 'GET'

    if (method === 'OPTIONS') {
      res.writeHead(405, { allow: 'GET, HEAD, POST' })
      res.end()
      return
    }

    if (method === 'GET' || method === 'HEAD') {
      // Image tags cannot carry the POST header, so reads are admitted by
      // browser-declared same-origin provenance instead. This route sits
      // outside the shared /api gate and must not become a read hole.
      const site = req.headers['sec-fetch-site']
      if (site && site !== 'same-origin' && site !== 'none') {
        throw new HttpError(403, '拒绝跨站读取')
      }
      const referer = req.headers.referer
      if (referer) {
        let refererHost = ''
        try {
          refererHost = new URL(referer).host
        } catch {
          throw new HttpError(403, 'Referer 头非法')
        }
        if (refererHost !== req.headers.host) throw new HttpError(403, '拒绝跨站读取')
      }

      if (sub === 'settings') {
        sendJson(res, 200, publicSettings())
        return
      }
      if (sub === 'capabilities') {
        const [image, llm] = await Promise.all([
          probeImageService().catch((e) => ({ ok: false, error: e.message })),
          probeLlmService(),
        ])
        sendJson(res, 200, {
          image,
          llm,
          limits: IMAGE_LIMITS,
          imageTimeoutMs: cfg.imageTimeoutMs,
          outputDir,
          queueLength: queue.length,
          running,
          fixed: { steps: IMAGE_LIMITS.steps, guidance: IMAGE_LIMITS.guidance, negativePrompt: 'ignored' },
          baseUrls: { image: cfg.imageBaseUrl, llm: cfg.llmBaseUrl },
          lastBusyNote,
        })
        return
      }
      if (sub === 'presets') {
        sendJson(res, 200, {
          presets: PRESETS.map(({ id, label, summary, needs, optional, caution, scene }) => ({
            id, label, summary, needs: needs || [], optional: optional || [], caution: caution || '', scene,
          })),
          roles: ROLES,
          limits: IMAGE_LIMITS,
        })
        return
      }
      if (sub === 'history') {
        const limit = clampInt(url.searchParams.get('limit'), 1, 400, 60)
        const archived = ['0', '1', 'all'].includes(String(url.searchParams.get('archived')))
          ? String(url.searchParams.get('archived'))
          : '0'
        const result = await listHistory(limit, archived)
        sendJson(res, 200, {
          items: result.items.map((meta) => ({
            ...meta,
            fileUrl: `${prefix}/file/${encodeURIComponent(meta.name)}`,
          })),
          counts: result.counts,
          savedTo: outputDir,
        })
        return
      }
      if (sub === 'favorites') {
        sendJson(res, 200, { items: await loadFavorites(), limit: MAX_FAVORITES })
        return
      }
      if (sub === 'jobs') {
        const all = [...jobs.values()].sort((a, b) => b.createdAt - a.createdAt)
        const active = all.filter((job) => job.status === 'queued' || job.status === 'running')
        const finished = all.filter((job) => job.status === 'done' || job.status === 'failed')
        sendJson(res, 200, {
          active: active.map(jobView),
          recent: finished.slice(0, 40).map(jobView),
          running,
          queueLength: queue.length,
          lastBusyNote,
        })
        return
      }
      if (sub.startsWith('jobs/')) {
        const id = decodeURIComponent(sub.slice('jobs/'.length))
        const job = jobs.get(id)
        if (!job) throw new HttpError(404, '任务不存在或已被清理')
        sendJson(res, 200, { job: jobView(job), queueLength: queue.length, lastBusyNote })
        return
      }
      if (sub.startsWith('file/')) {
        await handleFile(req, res, decodeURIComponent(sub.slice('file/'.length)))
        return
      }
      throw new HttpError(404, `未知的 GET 端点：${sub || '/'}`)
    }

    if (method === 'POST') {
      if (req.headers['x-glow'] !== '1') {
        throw new HttpError(403, '缺少 x-glow 请求头（跨站请求被拒绝）')
      }
      const origin = req.headers.origin
      if (origin) {
        let originHost = ''
        try {
          originHost = new URL(origin).host
        } catch {
          throw new HttpError(403, 'Origin 头非法')
        }
        if (originHost !== req.headers.host) throw new HttpError(403, '拒绝跨站请求')
      }

      if (sub === 'settings') {
        sendJson(res, 200, saveSettings(await readJsonBody(req)))
        return
      }
      if (sub === 'settings/reset') {
        try {
          mkdirSync(SETTINGS_DIR, { recursive: true })
          writeFileSync(SETTINGS_FILE, JSON.stringify({}, null, 2), 'utf8')
        } catch {
          /* nothing stored is the same outcome */
        }
        stored = {}
        cfg = buildConfig()
        outputDir = resolveOutputDir()
        sendJson(res, 200, publicSettings())
        return
      }
      if (sub === 'settings/test') {
        const body = await readJsonBody(req)
        const imageUrl = normalizeServiceUrl(body?.imageBaseUrl, cfg.imageBaseUrl)
        const llmUrl = normalizeServiceUrl(body?.llmBaseUrl, cfg.llmBaseUrl)
        const [image, llm] = await Promise.all([
          probeImageService(imageUrl).catch((error) => ({ ok: false, error: error.message })),
          probeLlmService(llmUrl),
        ])
        sendJson(res, 200, { image, llm, tested: { imageBaseUrl: imageUrl, llmBaseUrl: llmUrl } })
        return
      }
      if (sub === 'generate') {
        sendJson(res, 200, { job: await runGenerate(await readJsonBody(req)) })
        return
      }
      if (sub === 'edit') {
        sendJson(res, 200, { job: await runEdit(await readJsonBody(req)) })
        return
      }
      if (sub === 'preview') {
        sendJson(res, 200, previewEditPrompt(await readJsonBody(req)))
        return
      }
      if (sub === 'prompt') {
        sendJson(res, 200, await assistPrompt(await readJsonBody(req)))
        return
      }
      if (sub === 'jobs/clear') {
        for (const job of [...jobs.values()]) {
          if (job.status === 'done' || job.status === 'failed') jobs.delete(job.id)
        }
        sendJson(res, 200, { cleared: true })
        return
      }
      if (sub === 'favorites') {
        const body = await readJsonBody(req)
        const text = String(body?.text || '').trim()
        if (!text) throw new HttpError(400, '提示词是空的')
        const mode = body?.mode === 'image2image' ? 'image2image' : 'text2image'
        const items = await loadFavorites()
        if (items.some((item) => item.text === text)) {
          sendJson(res, 200, { items, limit: MAX_FAVORITES, duplicate: true })
          return
        }
        if (items.length >= MAX_FAVORITES) {
          throw new HttpError(409, `最多存 ${MAX_FAVORITES} 条，先删掉一条再存`)
        }
        const next = [
          {
            id: `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`,
            text,
            mode,
            createdAt: Date.now(),
          },
        ].concat(items)
        sendJson(res, 200, { items: await writeFavorites(next), limit: MAX_FAVORITES })
        return
      }
      if (sub === 'favorites/remove') {
        const body = await readJsonBody(req)
        const id = String(body?.id || '')
        const items = await loadFavorites()
        sendJson(res, 200, { items: await writeFavorites(items.filter((item) => item.id !== id)), limit: MAX_FAVORITES })
        return
      }
      if (sub === 'archive') {
        const body = await readJsonBody(req)
        const safe = safeEntryName(body?.name)
        if (!safe || !safe.endsWith('.png')) throw new HttpError(400, '非法的文件名')
        const sidecarPath = path.join(outputDir, safe.replace(/\.png$/, '.json'))
        let meta
        try {
          meta = JSON.parse(await readFile(sidecarPath, 'utf8'))
        } catch {
          throw new HttpError(404, '找不到这张图的记录')
        }
        meta.archived = Boolean(body?.archived)
        meta.archivedAt = meta.archived ? Date.now() : 0
        await writeFile(sidecarPath, JSON.stringify(meta, null, 2), 'utf8')
        sendJson(res, 200, { name: safe, archived: meta.archived })
        return
      }
      if (sub === 'delete') {
        const body = await readJsonBody(req)
        const safe = safeEntryName(body?.name)
        if (!safe) throw new HttpError(400, '非法的文件名')
        const targets = [safe, safe.replace(/\.png$/, '.json')]
        // Comparison copies live beside the result; deleting the result must
        // not leave orphaned reference photos behind.
        try {
          const sidecar = JSON.parse(await readFile(path.join(outputDir, safe.replace(/\.png$/, '.json')), 'utf8'))
          if (Array.isArray(sidecar.refFiles)) {
            for (const ref of sidecar.refFiles) {
              const refSafe = safeEntryName(ref)
              if (refSafe) targets.push(refSafe)
            }
          }
        } catch {
          /* no sidecar: nothing extra to remove */
        }
        const removed = []
        for (const name of targets) {
          try {
            await unlink(path.join(outputDir, name))
            removed.push(name)
          } catch {
            /* already gone */
          }
        }
        sendJson(res, 200, { removed })
        return
      }
      throw new HttpError(404, `未知的 POST 端点：${sub || '/'}`)
    }

    throw new HttpError(405, `不支持的请求方法：${method}`)
  }

  ctx.effect(
    () =>
      ctx.webServer.register({
        kind: 'prefix',
        path: prefix,
        handler: (req, res) => {
          route(req, res).catch((error) => {
            const status = error instanceof HttpError ? error.status : 500
            if (!(error instanceof HttpError)) ctx.logger?.('glow')?.warn?.(error)
            if (res.headersSent) {
              res.destroy()
              return
            }
            try {
              sendJson(res, status, { error: error.message || '内部错误' })
            } catch {
              res.destroy()
            }
          })
        },
      }),
    'glow: http routes',
  )
}
