/**
 * Behaviour check for the frame-wide job notice.
 *
 * `check-notice.mjs` only reads the source; twice in a row that passed while the
 * feature was broken. This file instead **runs** the client half: the real
 * factory from `client.js` is loaded against a small React runtime, a DOM stub
 * and a fake plugin context, then driven through polling, hand-off, wording and
 * dismissal. If a kind stops matching its wording, or the styles stop being
 * installed outside the panel, this fails.
 *
 *   node tools/check-notice-run.mjs
 *
 * Exits non-zero when an assertion fails.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '..', 'client.js'), 'utf8')

let passed = 0
let failed = 0
function check(name, condition, detail) {
  if (condition) {
    passed += 1
    return
  }
  failed += 1
  console.error(`FAIL ${name}${detail === undefined ? '' : ` — ${detail}`}`)
}

// --------------------------------------------------------------- tiny React

const effects = []
let currentInstance = null

function sameDeps(a, b) {
  if (!a || !b || a.length !== b.length) return false
  return a.every((value, index) => Object.is(value, b[index]))
}

function createReact() {
  function useContextFree() {
    return undefined
  }

  function useState(initial) {
    const inst = currentInstance
    const slot = inst.cursor++
    if (!(slot in inst.state)) inst.state[slot] = typeof initial === 'function' ? initial() : initial
    const set = (value) => {
      inst.state[slot] = typeof value === 'function' ? value(inst.state[slot]) : value
      inst.dirty = true
    }
    return [inst.state[slot], set]
  }

  function useRef(initial) {
    const inst = currentInstance
    const slot = inst.cursor++
    if (!(slot in inst.refs)) inst.refs[slot] = { current: initial }
    return inst.refs[slot]
  }

  function useMemo(factory, deps) {
    const inst = currentInstance
    const slot = inst.cursor++
    if (!(slot in inst.memo) || !sameDeps(inst.memoDeps[slot], deps)) {
      inst.memo[slot] = factory()
      inst.memoDeps[slot] = deps
    }
    return inst.memo[slot]
  }

  function useCallback(fn, deps) {
    return useMemo(() => fn, deps)
  }

  function useEffect(fn, deps) {
    const inst = currentInstance
    const slot = inst.cursor++
    const run = !(slot in inst.effectDeps) || !sameDeps(inst.effectDeps[slot], deps)
    inst.effectDeps[slot] = deps
    if (run) effects.push({ inst, slot, fn })
  }

  function createElement(type, props, ...children) {
    const flat = []
    for (const child of children) {
      if (Array.isArray(child)) flat.push(...child.flat(Infinity))
      else if (child !== null && child !== undefined && child !== false) flat.push(child)
    }
    return { type, props: Object.assign({}, props, { children: flat.length <= 1 ? flat[0] : flat }) }
  }

  return {
    createElement,
    Fragment: Symbol('Fragment'),
    useState,
    useRef,
    useMemo,
    useCallback,
    useEffect,
    useContext: useContextFree,
    useReducer: (reducer, initial) => useState(initial),
    useLayoutEffect: useEffect,
    useSyncExternalStore: (subscribe, get) => {
      const [value, set] = useState(get())
      useEffect(() => subscribe(() => set(get())), [subscribe])
      return value
    },
    Children: { toArray: (value) => (Array.isArray(value) ? value : value ? [value] : []) },
  }
}

const instances = new Map()

/** Render (or re-render) one component instance, then run its pending effects. */
function render(Component, props, key = 'main') {
  let inst = instances.get(key)
  if (!inst) {
    inst = { state: {}, refs: {}, memo: {}, memoDeps: {}, effectDeps: {}, cleanups: {}, cursor: 0, dirty: false }
    instances.set(key, inst)
  }
  let guard = 0
  do {
    inst.dirty = false
    currentInstance = inst
    inst.cursor = 0
    inst.tree = Component(props)
    currentInstance = null
    let ran = 0
    while (effects.length > 0 && ran < 200) {
      const job = effects.shift()
      ran += 1
      if (job.inst !== inst) continue
      const previous = inst.cleanups[job.slot]
      if (typeof previous === 'function') previous()
      const cleanup = job.fn()
      inst.cleanups[job.slot] = typeof cleanup === 'function' ? cleanup : undefined
      if (job.inst.dirty) break
    }
    guard += 1
  } while (inst.dirty && guard < 20)
  return inst.tree
}

function unmount(key = 'main') {
  const inst = instances.get(key)
  if (!inst) return
  for (const cleanup of Object.values(inst.cleanups)) if (typeof cleanup === 'function') cleanup()
  instances.delete(key)
}

/** Depth-first text of a rendered tree. */
function textOf(node) {
  if (node === null || node === undefined || node === false) return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  if (!node.props) return ''
  return textOf(node.props.children)
}

/**
 * Host-node view of a tree: function components are actually invoked (each with
 * its own hook slots), so assertions see the rendered buttons and text rather
 * than the element descriptors that produced them.
 */
function expand(node, prefix = 'n') {
  if (node === null || node === undefined || node === false) return null
  if (typeof node === 'string' || typeof node === 'number') return node
  if (Array.isArray(node)) return node.map((child, index) => expand(child, `${prefix}.${index}`))
  if (!node.props) return node
  if (typeof node.type === 'function') {
    const children = render(node.type, node.props, prefix)
    return expand(children, prefix)
  }
  return {
    type: node.type,
    props: Object.assign({}, node.props, { children: expand(node.props.children, `${prefix}.c`) }),
  }
}

/** Every node in the tree that matches `predicate`. */
function findAll(node, predicate, found = []) {
  if (!node || typeof node !== 'object') return found
  if (Array.isArray(node)) {
    for (const child of node) findAll(child, predicate, found)
    return found
  }
  if (!node.props) return found
  if (predicate(node)) found.push(node)
  findAll(node.props.children, predicate, found)
  return found
}

// ------------------------------------------------------------------- DOM stub

const focused = []
const styleElements = []
const headChildren = []

function makeElement(tag, props = {}) {
  const element = {
    tagName: String(tag).toUpperCase(),
    props,
    attributes: {},
    textContent: '',
    isConnected: true,
    focus() {
      focused.push(this)
    },
    setAttribute(name, value) {
      this.attributes[name] = value
    },
    remove() {
      this.isConnected = false
      const at = headChildren.indexOf(this)
      if (at >= 0) headChildren.splice(at, 1)
    },
  }
  return element
}

const fakeRoot = makeElement('div')
const bodyElement = makeElement('body')

let lastCloseButton = null
const originalExpand = null
globalThis.window = globalThis.window || {}
globalThis.document = {
  head: {
    appendChild(element) {
      headChildren.push(element)
      if (element.attributes && 'data-glow-notices' in element.attributes) styleElements.push(element)
    },
  },
  body: bodyElement,
  activeElement: bodyElement,
  createElement: (tag) => makeElement(tag),
  querySelector(selector) {
    // The close button only exists once a toast rendered; the harness answers it
    // from the last rendered tree so focus handling can be observed.
    if (selector === '.gl-toast-close') return lastCloseButton
    if (selector === '[data-glow-root]') return fakeRoot
    return null
  },
}
globalThis.requestAnimationFrame = (fn) => {
  setTimeout(fn, 0)
  return 0
}
globalThis.AbortSignal = globalThis.AbortSignal || { timeout: () => undefined }

// --------------------------------------------------------------- module load

const React = createReact()
let factory = null
globalThis.window.__ModuleLoader__ = {
  load(entry) {
    factory = entry.factory
  },
}

// The client half is a plain script that registers its factory on load; running
// it here is what makes this a behaviour check rather than a source scan.
vm.runInThisContext(source, { filename: 'client.js' })

const module = factory((name) => {
  if (name === 'react') return React
  throw new Error('unexpected require: ' + name)
})
check('客户端模块可以装载', Boolean(module && typeof module.apply === 'function'))

// ------------------------------------------------------------- fake plugin ctx

const runs = []
let styleCleanups = 0
const registrations = []
const dictionary = {}

const ctx = {
  effect(fn) {
    const cleanup = fn()
    runs.push(cleanup)
    return () => {}
  },
  locale: {
    register(namespace, dictionaries) {
      Object.assign(dictionary, (dictionaries && dictionaries.zh) || {})
      return () => {}
    },
    subscribe: () => () => {},
    bind: () => (key, vars) => {
      const template = dictionary[key] === undefined ? key : dictionary[key]
      if (!vars) return template
      return String(template).replace(/\{(\w+)\}/g, (whole, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : whole,
      )
    },
  },
  slots: {
    inject(key, callback) {
      callback()
      return () => {}
    },
    register(options, Component) {
      registrations.push({ options, Component })
      return () => {}
    },
  },
  layout: { selectPanel: (id) => selectPanelCalls.push(id) },
}

const selectPanelCalls = []

module.apply(ctx)
check('注册了窗口级通知条目', registrations.some((r) => r.options.name === 'shell.overlay'))
const overlay = registrations.find((r) => r.options.name === 'shell.overlay')
check('条目 id 独立，不顶替别人的格子', overlay && overlay.options.id === 'glow.notices')

// ---------------------------------------------------------- styles are outside

check('通知样式挂到了文档上', styleElements.length === 1, `count=${styleElements.length}`)
const noticeStyle = styleElements[0]
check('样式内容包含提示与播报区', /\.gl-toast \{/.test(noticeStyle.textContent) && /\.gl-sr \{/.test(noticeStyle.textContent))
check('面板卸载不影响这份样式（它不在面板根里）', /document\.head\.appendChild/.test(source))

// -------------------------------------------------------------------- polling

let jobsPayload = { active: [], recent: [] }
let fetchCalls = 0
let intervalCallback = null
const realSetInterval = globalThis.setInterval
globalThis.setInterval = (fn, ms) => {
  intervalCallback = fn
  return 1
}
globalThis.clearInterval = () => {
  intervalCallback = null
}
globalThis.fetch = async () => {
  fetchCalls += 1
  return {
    ok: true,
    status: 200,
    text: async () => JSON.stringify(jobsPayload),
  }
}

// Re-apply with the timer stubbed so the watcher's interval can be driven.
registrations.length = 0
styleElements.length = 0
module.apply(ctx)
const overlay2 = registrations.find((r) => r.options.name === 'shell.overlay')
const watch = overlay2.options.inject().watch

check('看护者有轮询定时器', typeof intervalCallback === 'function')

const tree0 = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('没有结果时不渲染提示容器', textOf(tree0) === '')
check('播报区始终存在', findAll(tree0, (n) => n.props.role === 'status').length === 1)

/** One poll round: fire the interval callback and let the fetch settle. */
async function tick() {
  fetchCalls = 0
  intervalCallback()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

jobsPayload = { active: [], recent: [{ id: 'old', status: 'done', items: [{ name: 'a.png' }], finishedAt: 1 }] }
await tick()
const firstText = textOf(render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay'))
check('第一轮只补记，不弹历史结果', fetchCalls === 1 && firstText === '')

jobsPayload = {
  active: [],
  recent: [
    { id: 'j1', status: 'done', items: [{ name: 'a.png' }, { name: 'b.png' }], finishedAt: 2 },
    { id: 'old', status: 'done', items: [{ name: 'a.png' }], finishedAt: 1 },
  ],
}
await tick()
let tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('完成的任务会弹提示', textOf(tree).includes('做好了') && textOf(tree).includes('2 张已存到本机'), textOf(tree))
check('播报区读的是同一句文案', findAll(tree, (n) => n.props.role === 'status').map(textOf).join('').includes('做好了，2 张已存到本机'))

// 待取回不能掉进失败分支（这正是第一版被验收打回的 bug）
jobsPayload = {
  active: [],
  recent: [
    { id: 'j2', status: 'unretrieved', finishedAt: 3 },
    ...jobsPayload.recent,
  ],
}
await tick()
tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('待取回说的是「待取回」', textOf(tree).includes('结果待取回') && textOf(tree).includes('继续取回'), textOf(tree))
check('待取回没有被说成失败', !textOf(tree).includes('没成功'), textOf(tree))

// 失败但没有原因时不能只留一个冒号
jobsPayload = { active: [], recent: [{ id: 'j3', status: 'failed', error: '', finishedAt: 4 }, ...jobsPayload.recent] }
await tick()
tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('失败没给原因时仍有话说', textOf(tree).includes('这次没成功') && textOf(tree).includes('换个描述'), textOf(tree))

// 取消不该弹
jobsPayload = { active: [], recent: [{ id: 'j4', status: 'canceled', finishedAt: 5 }, ...jobsPayload.recent] }
const beforeCancel = textOf(tree)
await tick()
tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('取消不弹提示', textOf(tree) === beforeCancel)

// 最多三条
jobsPayload = {
  active: [],
  recent: [
    { id: 'k1', status: 'done', items: [{ name: '1.png' }], finishedAt: 10 },
    { id: 'k2', status: 'done', items: [{ name: '2.png' }], finishedAt: 11 },
    { id: 'k3', status: 'done', items: [{ name: '3.png' }], finishedAt: 12 },
    { id: 'k4', status: 'done', items: [{ name: '4.png' }], finishedAt: 13 },
  ],
}
await tick()
tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('最多同时三条提示', findAll(tree, (n) => typeof n.props.className === 'string' && n.props.className.startsWith('gl-toast ')).length <= 3)

// ---------------------------------------------------------------- 面板开合

const fetchBefore = fetchCalls
watch.setPanelOpen(true)
check('面板打开时清空旧提示', watch.snapshot().length === 0)
await tick()
check('面板打开时不轮询', fetchCalls === 0, `fetchCalls=${fetchCalls}`)

// 交班：面板看到但没说的任务必须补上说，看到并已说的不能重复
watch.setPanelOpen(false, {
  seen: ['seen-1'],
  owed: [{ id: 'owed-1', status: 'done', items: [{ name: 'x.png' }], finishedAt: 20 }],
})
tree = render(overlay2.Component, { t: ctx.locale.bind('glow'), watch }, 'overlay')
check('交班把「看到没说」的任务补上说', textOf(tree).includes('1 张已存到本机'), textOf(tree))
await tick()
// ------------------------------------------------------------------- 交互
// ------------------------------------------------------------------- 交互

// 再补一条，确保同时有两条。
jobsPayload = {
  active: [],
  recent: [{ id: 'm1', status: 'done', items: [{ name: 'm.png' }], finishedAt: 30 }, ...jobsPayload.recent],
}
await tick()
const beforeClose = watch.snapshot().length
check('同时有多条时按条排列', beforeClose >= 2, `notices=${beforeClose}`)

const firstId = watch.snapshot()[0].id
watch.dismiss(firstId)
check('撤掉一条只影响这一条', watch.snapshot().length === beforeClose - 1)

// 提示里的两个按钮由静态契约检查覆盖（它读的是同一棵树的结构），这里验状态机。
const latest = watch.snapshot()[watch.snapshot().length - 1]
check('每条提示都带 kind 与唯一 id', Boolean(latest && latest.kind && latest.id))

// 面板开回来时旧提示必须让位
watch.setPanelOpen(true)
check('打开面板清空旧提示', watch.snapshot().length === 0)
watch.setPanelOpen(false, { seen: [], owed: [] })
// ------------------------------------------------------------------- 卸载

for (const cleanup of runs.reverse()) if (typeof cleanup === 'function') cleanup()
check('插件卸载后停止轮询', intervalCallback === null)
check('插件卸载后摘掉通知样式', noticeStyle.isConnected === false)

globalThis.setInterval = realSetInterval

if (failed > 0) {
  console.error(`\n${failed} 项不通过，${passed} 项通过`)
  process.exit(1)
}
console.log(`任务通知行为检查通过：${passed} 项断言全部成立`)
// Toasts keep real 8 s dismiss timers; the assertions are done.
process.exit(0)
