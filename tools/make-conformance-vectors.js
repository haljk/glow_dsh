/**
 * Generate conformance vectors for the Android port.
 *
 * Two sources of truth:
 *  1. Instruction composition is recorded from the RUNNING plugin over HTTP, so
 *     the vectors are what the shipped implementation actually produces — not a
 *     re-implementation of what we think it produces.
 *  2. Size fitting is computed with a byte-for-byte copy of client.js `fitSize`,
 *     because that function only exists in the browser half.
 *
 * The Android port must reproduce every `expected` value exactly.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))

const API = 'http://127.0.0.1:8420/api/glow'

function gcd(a, b) {
  return b ? gcd(b, a % b) : a
}
function ratioLabel(w, h) {
  if (!w || !h) return ''
  const d = gcd(w, h)
  let rw = w / d
  let rh = h / d
  if (rw > 40 || rh > 40) {
    rw = Math.round((w / h) * 100) / 100
    rh = 1
  }
  return rw + ':' + rh
}
// === identical to client.js fitSize ===
function fitSize(sourceWidth, sourceHeight, area, multiple, minEdge, maxEdge) {
  if (!sourceWidth || !sourceHeight) return null
  const aspect = sourceWidth / sourceHeight
  const snap = (value) => {
    const rounded = Math.round(value / multiple) * multiple
    return Math.min(maxEdge, Math.max(minEdge, rounded))
  }
  const relativeError = (a, b) => Math.abs(a - b) / b
  const width = snap(Math.sqrt(area * aspect))
  const height = snap(width / aspect)
  const alternative = snap(height * aspect)
  const useAlternative =
    relativeError(alternative / height, aspect) < relativeError(width / height, aspect) - 0.0005
  const finalWidth = useAlternative ? alternative : width
  return { width: finalWidth, height, text: finalWidth + 'x' + height, ratio: ratioLabel(finalWidth, height), pixels: finalWidth * height }
}

async function preview(body) {
  const res = await fetch(`${API}/preview`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-glow': '1' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  return res.json()
}

const ref = (role) => ({ role, dataUrl: 'x' })

const CASES = [
  // No preset: the strictest form.
  { name: 'no-preset-subject-only', body: { note: '把头发染成亚麻色', refs: [ref('subject')] } },
  { name: 'no-preset-no-note', body: { refs: [ref('subject')] } },
  { name: 'no-preset-two-roles', body: { note: '把图1的人放到图2里', refs: [ref('subject'), ref('background')] } },
  { name: 'no-preset-detail-only', body: { note: '参考一下细节', refs: [ref('detail')] } },
  { name: 'no-preset-all-roles', body: { note: '合成', refs: [ref('subject'), ref('background'), ref('outfit'), ref('style'), ref('scene'), ref('detail')] } },

  // One case per preset, plus the optional second reference where it exists.
  { name: 'bg-replace-one-ref', body: { presetId: 'bg-replace', note: '背景换成海边日落', refs: [ref('subject')] } },
  { name: 'bg-replace-two-refs', body: { presetId: 'bg-replace', note: '', refs: [ref('subject'), ref('background')] } },
  { name: 'outfit-replace-one-ref', body: { presetId: 'outfit-replace', note: '换成米色针织衫', refs: [ref('subject')] } },
  { name: 'outfit-replace-two-refs', body: { presetId: 'outfit-replace', note: '', refs: [ref('subject'), ref('outfit')] } },
  { name: 'hair-change', body: { presetId: 'hair-change', note: '剪成短发', refs: [ref('subject')] } },
  { name: 'expression', body: { presetId: 'expression', note: '微笑', refs: [ref('subject')] } },
  { name: 'light-change', body: { presetId: 'light-change', note: '改成黄昏', refs: [ref('subject')] } },
  { name: 'clean-up', body: { presetId: 'clean-up', note: '去掉左边路人', refs: [ref('subject')] } },
  { name: 'cutout-white', body: { presetId: 'cutout-white', note: '纯白背景', refs: [ref('subject')] } },
  { name: 'restore', body: { presetId: 'restore', note: '', refs: [ref('subject')] } },
  { name: 'id-photo', body: { presetId: 'id-photo', note: '蓝底', refs: [ref('subject')] } },

  // Ordering matters: the numbering follows submit order.
  { name: 'order-flipped', body: { presetId: 'bg-replace', note: '', refs: [ref('background'), ref('subject')] } },
]

const SOURCES = [
  ['square-1024', 1024, 1024],
  ['landscape-16x9', 1920, 1080],
  ['landscape-3x2', 6000, 4000],
  ['classic-4x3', 4000, 3000],
  ['portrait-3x4', 3024, 4032],
  ['portrait-9x16', 1080, 1920],
  ['panorama-5x1', 4000, 800],
  ['tall-1x5', 800, 4000],
]

const DENSITIES = [
  ['fast', 640 * 640],
  ['normal', 1024 * 1024],
  ['sharp', 1440 * 1440],
]

async function main() {
  const compose = []
  for (const testCase of CASES) {
    const result = await preview(testCase.body)
    compose.push({
      name: testCase.name,
      input: testCase.body,
      expected: { prompt: result.prompt, presetId: result.presetId, references: result.references, roles: result.roles },
    })
  }

  const fitting = []
  for (const [label, w, h] of SOURCES) {
    for (const [density, area] of DENSITIES) {
      const out = fitSize(w, h, area, 32, 256, 2048)
      fitting.push({
        name: `${label}-${density}`,
        input: { sourceWidth: w, sourceHeight: h, budget: area, multiple: 32, minEdge: 256, maxEdge: 2048 },
        expected: out,
      })
    }
  }

  // A few guard cases that must be rejected, recorded as expectations too.
  const rejects = []
  for (const [name, body] of [
    ['preset-without-agreed-role', { presetId: 'bg-replace', refs: [ref('detail')] }],
    ['no-refs', { note: 'x', refs: [] }],
    ['unknown-preset', { presetId: 'nope', refs: [ref('subject')] }],
  ]) {
    try {
      await preview(body)
      rejects.push({ name, input: body, expected: { rejected: false } })
    } catch (error) {
      rejects.push({ name, input: body, expected: { rejected: true, status: Number(String(error.message).slice(0, 3)) || 0 } })
    }
  }

  // The behaviour of "no preset and no note" is a host-side guard on /edit, not
  // on /preview, so it is documented rather than exercised here.
  const payload = {
    generatedAt: new Date().toISOString(),
    source: 'recorded from the shipped Node implementation',
    note: 'The Android port must reproduce every expected value exactly.',
    compose,
    fitting,
    rejects,
  }

  const outDir = path.join(HERE, '..', 'android', 'app', 'src', 'test', 'resources', 'conformance')
  fs.mkdirSync(outDir, { recursive: true })
  const outFile = path.join(outDir, 'vectors.json')
  fs.writeFileSync(outFile, JSON.stringify(payload, null, 2), 'utf8')
  console.log(`wrote ${compose.length} compose + ${fitting.length} fitting + ${rejects.length} reject vectors`)
  console.log(outFile)
}

main().catch((error) => {
  console.error('FAILED:', error.message)
  process.exit(1)
})
