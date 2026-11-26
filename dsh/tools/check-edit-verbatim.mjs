/**
 * End-to-end check for the "send my own instruction verbatim" switch.
 *
 * Runs against a fake image service and a throwaway HOME, so it never reads the
 * user's real settings and never submits a job to a real model host. It drives
 * the plugin through its own HTTP routes — the only way to prove the switch
 * reaches the multipart body rather than just changing what a preview shows —
 * and it does that for **both** submit paths, because a field that is carried by
 * one and dropped by the other is exactly the bug this file was written after.
 *
 *   node tools/check-edit-verbatim.mjs
 *
 * Exits non-zero when any assertion fails.
 */

import http from 'node:http'
import os from 'node:os'
import fs from 'node:fs'
import path from 'node:path'

// The plugin reads ~/.glow/settings.json at activation, and that file wins over
// the config passed to apply(). The fake HOME has to exist *before* the module
// is imported, which is why this import is dynamic.
const home = fs.mkdtempSync(path.join(os.tmpdir(), 'glow-home-'))
process.env.USERPROFILE = home
process.env.HOME = home

const { apply } = await import(new URL('../index.js', import.meta.url).href)

/** A 1x1 PNG: the plugin only needs bytes it can decode as an image. */
const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
const PNG_BYTES = Buffer.from(PNG.split(',')[1], 'base64')

const made = []
let captured = []
let serviceUrl = ''

// ------------------------------------------------------------------ 假服务
// One server answers both contracts: the async queue (`/v1/images/jobs/...`)
// and the blocking service (`/v1/images/edits`). Each POST is recorded so the
// prompt that actually went out can be compared with what the user typed.
const service = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', () => {
    const text = Buffer.concat(chunks).toString('utf8')
    const url = req.url || ''

    if (req.method === 'GET' && /\/result\.png$/.test(url)) {
      res.writeHead(200, { 'content-type': 'image/png', 'content-length': PNG_BYTES.length })
      res.end(PNG_BYTES)
      return
    }
    if (req.method === 'GET' && /\/v1\/images\/jobs\//.test(url)) {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(
        JSON.stringify({
          id: 'fake',
          status: 'succeeded',
          seconds: 12,
          data: [{ url: `${serviceUrl}/result.png` }],
        }),
      )
      return
    }
    if (req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, allow_4k: false }))
      return
    }

    // A submit. The queue takes multipart, the blocking service takes either
    // multipart (edits) or JSON (generations); only the prompt matters here.
    let prompt = null
    const field = /name="prompt"\r\n\r\n([\s\S]*?)\r\n--/.exec(text)
    if (field) {
      prompt = field[1]
    } else {
      try {
        prompt = JSON.parse(text).prompt ?? null
      } catch {
        prompt = null
      }
    }
    captured.push({ url, prompt })

    res.writeHead(200, { 'content-type': 'application/json' })
    if (/\/v1\/images\/edits$/.test(url)) {
      res.end(JSON.stringify({ data: [{ b64_json: PNG.split(',')[1] }], seconds: 9 }))
      return
    }
    res.end(JSON.stringify({ id: 'fake-' + captured.length, status: 'queued' }))
  })
})
await new Promise((resolve) => service.listen(0, '127.0.0.1', resolve))
serviceUrl = `http://127.0.0.1:${service.address().port}`

// --------------------------------------------------------------- 启动一个实例

/** Boot the plugin on a throwaway host context and return its base URL. */
async function startApp(mode) {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'glow-out-'))
  made.push(out)
  let handler = null
  const ctx = {
    effect(fn) {
      const cleanup = fn()
      return typeof cleanup === 'function' ? cleanup : () => {}
    },
    webServer: {
      register(entry) {
        handler = entry.handler
        return () => {}
      },
    },
    logger: () => () => {},
  }
  apply(ctx, {
    imageApiMode: mode,
    imageBaseUrl: serviceUrl,
    imageSyncBaseUrl: serviceUrl,
    llmBaseUrl: serviceUrl,
    llmModel: 'default_model',
    outputDir: out,
    imageTimeoutMs: 1800000,
    llmTimeoutMs: 180000,
    keepReferenceCopies: true,
  })
  if (!handler) throw new Error('插件没有注册路由，无法验证')

  const server = http.createServer((req, res) => handler(req, res))
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  made.push(server)
  return { base: `http://127.0.0.1:${server.address().port}/api/glow`, out }
}

async function call(base, pathname, body) {
  const res = await fetch(base + pathname, {
    method: body ? 'POST' : 'GET',
    headers: { 'content-type': 'application/json', 'x-glow': '1' },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* a non-JSON answer is reported through the raw text */
  }
  return { status: res.status, json, text }
}

/** Submits happen on the poller's next tick, not inside the request. */
async function waitFor(predicate, ms = 15000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await new Promise((resolve) => setTimeout(resolve, 100))
  }
  return false
}

let failed = 0
let passed = 0
function check(name, condition, detail) {
  if (condition) {
    passed++
    return
  }
  failed++
  console.error(`FAIL ${name}${detail ? ` — ${detail}` : ''}`)
}

const refs = [
  { role: 'subject', dataUrl: PNG },
  { role: 'scene', dataUrl: PNG },
]
const own = '把图1里的人放到图2的沙滩上，保持原来的光线和肤色'
const bound = '把<Picture 1>里的人放到<Picture 2>的沙滩上，保持原来的光线和肤色'

/** Poll the gallery until an entry satisfying `predicate` shows up. */
async function waitForItem(base, predicate, ms = 20000) {
  let items = []
  const ok = await waitFor(async () => {
    const res = await call(base, '/history?limit=50&archived=0')
    items = (res.json && res.json.items) || []
    return items.some(predicate)
  }, ms)
  return { ok, items }
}

// ================================================================ 排队模式

const queueApp = await startApp('queue')

let response = await call(queueApp.base, '/edit', { refs, note: own, verbatim: true, size: '1024x1024' })
check('verbatim 请求被受理', response.status === 200, response.text)
check('队列收到提交', await waitFor(() => captured.length >= 1), `captured=${captured.length}`)
const first = captured.at(-1) || {}
check('提交走 edits 端点', String(first.url).includes('/v1/images/jobs/edits'), String(first.url))
check('prompt 就是用户原文（只换 token）', first.prompt === bound, String(first.prompt))
check('不叠加冻结清单', !String(first.prompt).includes('保持不变'), String(first.prompt))
check('不叠加「不增删物体」', !String(first.prompt).includes('不增加也不删除任何物体'), String(first.prompt))
check('不叠加角色句式', !String(first.prompt).includes('为主体'), String(first.prompt))
check('不叠加预设指令', !String(first.prompt).includes('把人物从原背景中干净地分离出来'), String(first.prompt))

captured = []
response = await call(queueApp.base, '/edit', { refs, note: '把背景换成海边日落', size: '1024x1024' })
check('不带开关的请求被受理', response.status === 200, response.text)
check('不带开关时也提交了', await waitFor(() => captured.length >= 1), `captured=${captured.length}`)
const second = captured.at(-1) || {}
check('不带开关时仍然叠加冻结清单', String(second.prompt).includes('保持不变'), String(second.prompt))
check('不带开关时仍然有角色句', String(second.prompt).includes('为主体'), String(second.prompt))
check('不带开关时描述被包进「另外」', String(second.prompt).includes('另外，把背景换成海边日落'), String(second.prompt))

response = await call(queueApp.base, '/preview', { refs, note: own, verbatim: true })
check('预览接口支持 verbatim', response.status === 200 && response.json.prompt === bound, response.text)
response = await call(queueApp.base, '/preview', { refs, note: '换个背景' })
check('预览接口默认仍然合成', response.status === 200 && String(response.json.prompt).includes('保持不变'), response.text)
response = await call(queueApp.base, '/preview', { refs, note: own, verbatim: 'true' })
check('字符串 "true" 与布尔一致（预览）', response.status === 200 && response.json.prompt === bound, response.text)

response = await call(queueApp.base, '/edit', { refs, note: '   ', verbatim: true })
check('内容为空时拒绝', response.status === 400, response.text)
response = await call(queueApp.base, '/edit', { refs: [], note: own, verbatim: true })
check('没有参考图时拒绝', response.status === 400, response.text)
response = await call(queueApp.base, '/edit', { refs: [null, ...refs], note: own, verbatim: true })
check('参考图里混了空值也不会 500', response.status === 200, response.text)

captured = []
response = await call(queueApp.base, '/edit', { refs, note: '把图9的背景换掉', verbatim: true })
check('越界编号的请求被受理', response.status === 200, response.text)
check('越界编号也提交了', await waitFor(() => captured.length >= 1), `captured=${captured.length}`)
check('越界编号原样保留', (captured.at(-1) || {}).prompt === '把图9的背景换掉', String((captured.at(-1) || {}).prompt))

captured = []
response = await call(queueApp.base, '/edit', { refs, note: '地图1上的标记去掉', verbatim: true })
check('含「图」的词请求被受理', response.status === 200, response.text)
check('含「图」的词也提交了', await waitFor(() => captured.length >= 1), `captured=${captured.length}`)
check('含「图」的词不被误替换', (captured.at(-1) || {}).prompt === '地图1上的标记去掉', String((captured.at(-1) || {}).prompt))

const queueGallery = await waitForItem(queueApp.base, (item) => item.prompt === bound)
check('直发的结果落进了画廊', queueGallery.ok, JSON.stringify(queueGallery.items.map((i) => i.prompt)))
const verbatimItem = queueGallery.items.find((item) => item.prompt === bound)
check('排队模式落库 verbatim 为 true', verbatimItem && verbatimItem.verbatim === true, String(verbatimItem && verbatimItem.verbatim))

const templated = await waitForItem(queueApp.base, (item) => String(item.prompt).includes('保持不变'))
const templatedItem = templated.items.find((item) => String(item.prompt).includes('保持不变'))
check('合成的结果也落进了画廊', Boolean(templatedItem), JSON.stringify(templated.items.map((i) => i.prompt)))
check('合成的那条 verbatim 不为 true', templatedItem && templatedItem.verbatim !== true, String(templatedItem && templatedItem.verbatim))
if (verbatimItem) {
  const sidecar = path.join(queueApp.out, String(verbatimItem.name).replace(/\.png$/, '.json'))
  let meta = {}
  try {
    meta = JSON.parse(fs.readFileSync(sidecar, 'utf8'))
  } catch {
    /* the assertion below reports it */
  }
  check('旁车文件里存了 verbatim', meta.verbatim === true, String(meta.verbatim))
  check('旁车文件里的 note 是用户原话', meta.note === own, String(meta.note))
}

// ================================================================ 直连模式

captured = []
const syncApp = await startApp('sync')
response = await call(syncApp.base, '/edit', { refs, note: own, verbatim: true, size: '1024x1024' })
check('直连模式受理 verbatim', response.status === 200, response.text)
check('直连模式收到提交', await waitFor(() => captured.length >= 1), `captured=${captured.length}`)
check('直连模式走 edits 直连端点', String((captured.at(-1) || {}).url).includes('/v1/images/edits'), String((captured.at(-1) || {}).url))
check('直连模式 prompt 也是原文', (captured.at(-1) || {}).prompt === bound, String((captured.at(-1) || {}).prompt))

const syncGallery = await waitForItem(syncApp.base, (item) => item.prompt === bound)
check('直连模式结果也落库', syncGallery.ok, JSON.stringify(syncGallery.items.map((i) => i.prompt)))
const syncItem = syncGallery.items.find((item) => item.prompt === bound)
check('直连模式落库 verbatim 为 true', syncItem && syncItem.verbatim === true, String(syncItem && syncItem.verbatim))

// ------------------------------------------------------------------ 收尾

for (const item of made) {
  if (typeof item === 'function') continue
  if (typeof item.close === 'function') item.close()
  else if (typeof item === 'string') fs.rmSync(item, { recursive: true, force: true })
}
service.close()
fs.rmSync(home, { recursive: true, force: true })

if (failed > 0) {
  console.error(`\n${failed} 项不通过，${passed} 项通过`)
  process.exit(1)
}
console.log(`编辑指令直发检查通过：${passed} 项断言全部成立`)

