/**
 * End-to-end checks for the two recovery promises:
 *
 *   1. 取消 too late only ends the wait — the image is still saved.
 *   2. 连不上 the service is not a dead task — it becomes 「结果待取回」 and can be
 *      claimed later by its task id (继续取回).
 *
 * Both are driven through the plugin's own HTTP routes against a fake image
 * service and a throwaway HOME, so nothing here touches the user's real settings
 * or a real model host. The service's failure/success script is switched from
 * inside this file, which is why the poll interval and the unreadable-read budget
 * are overridden through the environment before the plugin is imported.
 *
 *   node tools/check-recovery.mjs
 *
 * Exits non-zero when any assertion fails.
 */

import http from 'node:http'
import os from 'node:os'
import fs from 'node:fs'
import path from 'node:path'

// Fast loop, few failures needed: the timed-out path is 45 reads at 4 s apart by
// default, which is not something a check should sit through.
process.env.GLOW_POLL_MS = '120'
process.env.GLOW_STATUS_FAILURES = '3'

const home = fs.mkdtempSync(path.join(os.tmpdir(), 'glow-recovery-home-'))
process.env.USERPROFILE = home
process.env.HOME = home

const { apply } = await import(new URL('../index.js', import.meta.url).href)

const PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
const PNG_BYTES = Buffer.from(PNG.split(',')[1], 'base64')

let serviceUrl = ''
let captured = []

/**
 * What the fake service does next for a status read:
 *   'ok'      → succeeded with a result URL
 *   'running' → still working
 *   'down'    → transport-level failure (the connection went away)
 */
let statusScript = 'ok'
let cancelAnswers = 'accepted'
let statusReads = 0

const service = http.createServer((req, res) => {
  const chunks = []
  req.on('data', (chunk) => chunks.push(chunk))
  req.on('end', () => {
    const url = req.url || ''
    const text = Buffer.concat(chunks).toString('utf8')

    if (req.method === 'GET' && /\/result\.png$/.test(url)) {
      res.writeHead(200, { 'content-type': 'image/png', 'content-length': PNG_BYTES.length })
      res.end(PNG_BYTES)
      return
    }

    if (req.method === 'POST' && /\/cancel$/.test(url)) {
      const status = cancelAnswers === 'accepted' ? 200 : 409
      res.writeHead(status, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ id: 'fake', status: status === 200 ? 'canceled' : 'running' }))
      return
    }

    if (req.method === 'GET' && /\/v1\/images\/jobs\//.test(url)) {
      statusReads += 1
      if (statusScript === 'down') {
        // Kill the socket itself: destroying the request object alone leaves the
        // response pending, and the client would just wait for its own timeout.
        if (res.socket) res.socket.destroy()
        else res.destroy()
        return
      }
      res.writeHead(200, { 'content-type': 'application/json' })
      if (statusScript === 'queued') {
        res.end(JSON.stringify({ id: 'fake', status: 'queued', progress: { percent: 0 } }))
        return
      }
      if (statusScript === 'running') {
        res.end(JSON.stringify({ id: 'fake', status: 'running', progress: { percent: 40 } }))
        return
      }
      res.end(
        JSON.stringify({
          id: 'fake',
          status: 'succeeded',
          seconds: 9,
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

    const field = /name="prompt"\r\n\r\n([\s\S]*?)\r\n--/.exec(text)
    captured.push({ url, prompt: field ? field[1] : null })
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id: 'fake-' + captured.length, status: 'queued' }))
  })
})
await new Promise((resolve) => service.listen(0, '127.0.0.1', resolve))
serviceUrl = `http://127.0.0.1:${service.address().port}`

const out = fs.mkdtempSync(path.join(os.tmpdir(), 'glow-recovery-out-'))

/**
 * Boot one plugin instance on its own port. Called twice: the second call is a
 * stand-in for "the process restarted", which is what the startup pass in
 * `resumeUnretrieved` is for. Both share the same HOME and output directory,
 * exactly as a real restart would.
 */
async function boot() {
  let handler = null
  apply(
    {
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
    },
    {
      imageApiMode: 'queue',
      imageBaseUrl: serviceUrl,
      imageSyncBaseUrl: serviceUrl,
      llmBaseUrl: serviceUrl,
      llmModel: 'default_model',
      outputDir: out,
      imageTimeoutMs: 1800000,
      llmTimeoutMs: 180000,
      keepReferenceCopies: true,
    },
  )
  if (!handler) throw new Error('插件没有注册路由，无法验证')
  const server = http.createServer((req, res) => handler(req, res))
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return { base: `http://127.0.0.1:${server.address().port}/api/glow`, server }
}

const app = await boot()
const base = app.base

async function call(pathname, body) {
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
    /* reported through the raw text */
  }
  return { status: res.status, json, text }
}

/** Same request against another instance, for the restart case. */
async function callOn(origin, pathname, body) {
  const res = await fetch(origin + pathname, {
    method: body ? 'POST' : 'GET',
    headers: { 'content-type': 'application/json', 'x-glow': '1' },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* reported through the raw text */
  }
  return { status: res.status, json, text }
}

async function waitFor(predicate, ms = 15000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    const value = await predicate()
    if (value) return value
    await new Promise((resolve) => setTimeout(resolve, 60))
  }
  return null
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

const refs = [{ role: 'subject', dataUrl: PNG }]

async function findJob(id) {
  const res = await call('/jobs')
  const all = [].concat(res.json?.active || [], res.json?.recent || [])
  return all.find((job) => job.id === id) || null
}

async function galleryCount() {
  const res = await call('/history?limit=50&archived=0')
  return (res.json && res.json.items ? res.json.items : []).length
}

// ============================================================ 1) 取消太晚

statusScript = 'running'
let response = await call('/edit', { refs, note: '换个背景', size: '1024x1024' })
check('编辑任务被受理', response.status === 200, response.text)
const lateJob = response.json?.job?.id
check('拿到任务号', Boolean(lateJob), response.text)

// Wait until it is actually running on the service before canceling.
const running = await waitFor(async () => {
  const job = await findJob(lateJob)
  return job && job.remoteStatus === 'running' ? job : null
})
check('任务已在服务端开跑', Boolean(running), JSON.stringify(running))

cancelAnswers = 'refused'
response = await call('/jobs/cancel', { id: lateJob })
check('取消已开跑的任务返回 200', response.status === 200, response.text)
check('取消没有当成失败', response.json?.canceled === false, response.text)
check(
  '提示说的是跑完会照常保存',
  String(response.json?.reason || '').includes('照常存进作品'),
  String(response.json?.reason),
)
const afterCancel = await findJob(lateJob)
check('任务没有被判成已放弃/失败', afterCancel && afterCancel.status !== 'failed' && afterCancel.status !== 'abandoned', JSON.stringify(afterCancel && afterCancel.status))
check('界面能知道"只是不等了"', afterCancel && afterCancel.stoppedWaiting === true, JSON.stringify(afterCancel && afterCancel.stoppedWaiting))

// Let the model finish: the image must land in the gallery.
statusScript = 'ok'
const savedAfterCancel = await waitFor(async () => ((await galleryCount()) >= 1 ? true : null))
check('取消之后的图仍然入库了', Boolean(savedAfterCancel), `gallery=${await galleryCount()}`)
const doneJob = await findJob(lateJob)
check('任务最终是已完成', doneJob && doneJob.status === 'done', JSON.stringify(doneJob && doneJob.status))

// ============================================================ 2) 连不上

statusScript = 'down'
statusReads = 0
response = await call('/edit', { refs, note: '换个背景', size: '1024x1024' })
check('第二个任务被受理', response.status === 200, response.text)
const lostJob = response.json?.job?.id

const unreachable = await waitFor(async () => {
  const job = await findJob(lostJob)
  return job && job.status === 'unretrieved' ? job : null
}, 20000)
check('连不上若干次后进入「结果待取回」', Boolean(unreachable), JSON.stringify(unreachable))
check('没有把任务判成失败', unreachable && unreachable.status !== 'failed', String(unreachable && unreachable.status))
check('记录里留着任务号', unreachable && Boolean(unreachable.remoteId), String(unreachable && unreachable.remoteId))
check('界面被标记为可取回', unreachable && unreachable.resumable === true, String(unreachable && unreachable.resumable))

// The service comes back; the result is still there.
statusScript = 'ok'
const before = await galleryCount()
response = await call('/jobs/resume', { id: lostJob })
check('继续取回被受理', response.status === 200, response.text)
check('继续取回后状态回到跟踪中', response.json?.job && response.json.job.status !== 'unretrieved', JSON.stringify(response.json?.job?.status))

const reclaimed = await waitFor(async () => ((await galleryCount()) > before ? true : null), 20000)
check('按任务号把结果取回来了', Boolean(reclaimed), `gallery ${before} → ${await galleryCount()}`)
const finalJob = await findJob(lostJob)
check('取回后是已完成', finalJob && finalJob.status === 'done', JSON.stringify(finalJob && finalJob.status))

// 没有任务号的记录不能假装能取回
response = await call('/jobs/resume', { id: 'job-does-not-exist' })
check('不存在的任务号被拒绝', response.status === 404, response.text)

// ============================================================ 3) 排队中取消

// The service keeps answering "queued", so the cancel really can land before
// the model starts — otherwise this section races its own first status read.
statusScript = 'queued'
response = await call('/edit', { refs, note: '换个背景', size: '1024x1024' })
const queuedJob = response.json?.job?.id
const beforeQueued = await galleryCount()
cancelAnswers = 'accepted'
response = await call('/jobs/cancel', { id: queuedJob })
check('排队中取消是真取消', response.json?.canceled === true, response.text)
const canceledJob = await findJob(queuedJob)
check('排队中取消后状态是已取消', canceledJob && canceledJob.status === 'canceled', JSON.stringify(canceledJob && canceledJob.status))
check('排队中取消不会入库', (await galleryCount()) === beforeQueued, `gallery ${beforeQueued} → ${await galleryCount()}`)

// ================================ 5) 取回不毁原图、也不重复入库

// 取回时如果把任务目录删掉，原图副本会跟着消失，对比视图就废了。
const refAfterResume = await call(`/taskref/${lostJob}/1`)
check('取回之后任务的参考图副本还在', refAfterResume.status === 200, String(refAfterResume.status))

// 已经做完的任务再"取回"一次，会把同一张图再存一遍。
const beforeDup = await galleryCount()
const duplicateTry = await call('/jobs/resume', { id: lateJob })
check('已完成的任务拒绝再取回', duplicateTry.status === 409, duplicateTry.text)
check('拒绝之后没有多出一张图', (await galleryCount()) === beforeDup, `gallery ${beforeDup} → ${await galleryCount()}`)

// 连着点两次：第二次应当说明"已经在跟"，而不是再排一个。
statusScript = 'down'
response = await call('/edit', { refs, note: '换个背景', size: '1024x1024' })
const twiceId = response.json?.job?.id
await waitFor(async () => {
  const job = await findJob(twiceId)
  return job && job.status === 'unretrieved' ? job : null
}, 20000)
const firstTap = await call('/jobs/resume', { id: twiceId })
const secondTap = await call('/jobs/resume', { id: twiceId })
check('连点两次都是 200', firstTap.status === 200 && secondTap.status === 200, `${firstTap.status}/${secondTap.status}`)
check('第二次说明已经在跟了', secondTap.json?.already === true, secondTap.text)
const afterTwice = await call('/jobs')
const liveForTask = (afterTwice.json?.active || []).filter((job) => job.status === 'queued' || job.status === 'running')
check('连点没有排成两个任务', liveForTask.length <= 1, JSON.stringify(liveForTask.map((job) => job.id)))
statusScript = 'ok'

// ============================================== 4) 重启后自动接上

// A task left as 「结果待取回」 is picked up by the next start without anyone
// clicking anything. A second instance on the same HOME and output directory is
// what a restart looks like from the outside.
statusScript = 'down'
response = await call('/edit', { refs, note: '换个背景', size: '1024x1024' })
const parkJob = response.json?.job?.id
const parked = await waitFor(async () => {
  const job = await findJob(parkJob)
  return job && job.status === 'unretrieved' ? job : null
}, 20000)
check('重启前先留下一个待取回的任务', Boolean(parked), JSON.stringify(parked && parked.status))

const beforeRestart = await galleryCount()
statusScript = 'ok'
const app2 = await boot()
const reclaimOnStart = await waitFor(async () => {
  const res = await callOn(app2.base, '/history?limit=50&archived=0')
  const items = (res.json && res.json.items) || []
  return items.length > beforeRestart ? items : null
}, 25000)
check('重启后自己把结果取回来了', Boolean(reclaimOnStart), `gallery ${beforeRestart} → ${await galleryCount()}`)

const afterRestart = await callOn(app2.base, '/jobs')
const all2 = [].concat(afterRestart.json?.active || [], afterRestart.json?.recent || [])
const parkedNow = all2.find((job) => job.id === parkJob)
check('重启后那条任务不再是待取回', parkedNow && parkedNow.status !== 'unretrieved', JSON.stringify(parkedNow && parkedNow.status))
app2.server.close()

// ------------------------------------------------------------------ 收尾

app.server.close()
service.close()
fs.rmSync(out, { recursive: true, force: true })
fs.rmSync(home, { recursive: true, force: true })

if (failed > 0) {
  console.error(`\n${failed} 项不通过，${passed} 项通过`)
  process.exit(1)
}
console.log(`取消与取回检查通过：${passed} 项断言全部成立`)


