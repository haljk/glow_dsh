/**
 * Structural contract for the frame-wide job notice.
 *
 * The behaviour lives in `check-notice-run.mjs`, which actually runs the client
 * half. This file only covers what running it cannot show: that the styles are
 * installed for the plugin's lifetime rather than riding on the panel, that the
 * layer stays click-through, and that every dictionary key the component asks for
 * exists. Anything that duplicates the behaviour check was removed on purpose —
 * a second, weaker copy of the same claim only teaches people to ignore failures.
 *
 *   node tools/check-notice.mjs
 *
 * Exits non-zero when a contract is broken.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

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
  console.error(`FAIL ${name}${detail ? ` — ${detail}` : ''}`)
}

function slice(marker, length) {
  const at = source.indexOf(marker)
  return at < 0 ? '' : source.slice(at, at + length)
}

const panelCss = slice('const CSS = `', 40000).split('const NOTICE_CSS')[0]
const noticeCss = slice('const NOTICE_CSS = `', 5000)
const apply = slice('apply(ctx)', 3000)
const dictionary = slice('ctx.locale.register(NS', 1200)

// ------------------------------------------------------------------ 样式归属

check('通知样式是独立的一份', noticeCss.length > 0)
check('面板样式表里没有通知的类', !/\.gl-(toast|notices|sr)/.test(panelCss), 'panel CSS must not carry notice rules')
check('通知样式由 apply 挂到文档上', /document\.createElement\('style'\)/.test(apply) && /document\.head\.appendChild\(style\)/.test(apply))
check('插件卸载时把这份样式摘掉', /return \(\) => style\.remove\(\)/.test(apply))

// ---------------------------------------------------------------------- 布局

check('通知层本身点击穿透（双类名压过宿主 .overlayLayer > *）',
  /\.gl-notices\.gl-notices \{[^}]*pointer-events: none/.test(noticeCss))
check('提示本体重新接收点击', /\.gl-toast \{[^}]*pointer-events: auto/.test(noticeCss))
check('播报区在视觉上真的隐藏了', /\.gl-sr \{[^}]*(clip-path|clip):/.test(noticeCss))
check('提示不铺满、有最大宽度', /max-width:\s*min\(\d+px, calc\(100vw - \d+px\)\)/.test(noticeCss))
check('位置在右下角，且抬高到输入框之上',
  /inset-block-end:\s*\d+px/.test(noticeCss) && /inset-inline-end:\s*\d+px/.test(noticeCss))
check('没有写死颜色（只用宿主 token）', !/#[0-9a-fA-F]{3,8}\b|rgba?\(/.test(noticeCss))
check('动画有 prefers-reduced-motion 降级', /@media \(prefers-reduced-motion: reduce\) \{\s*\.gl-toast \{ animation: none; \}/.test(noticeCss))
check('标题与正文分成两行，方便一眼抓住', /\.gl-toast-title \{[^}]*font-weight/.test(noticeCss) && /\.gl-toast-text \{[^}]*font-size/.test(noticeCss))

// ---------------------------------------------------------------- 注册与文案

check('注册在框架的窗口级插槽上', /ctx\.slots\.inject\(\s*'shell\.overlay'/.test(source))
check('条目用自己的 id、声明 locale 与排序', /id:\s*'glow\.notices'/.test(apply) && /locale:\s*NS/.test(apply) && /order:\s*\d+/.test(apply))
check('点「查看」用 layout 服务回到面板，不改 URL', /ctx\.layout\.selectPanel\(PANEL_ID\)/.test(source))
check('焦点有落点：面板根可被聚焦', /'data-glow-root': ''/.test(source) && /tabIndex: -1/.test(source))

const used = [...source.matchAll(/t\('(notice\.[a-z]+)'\)/g)].map((m) => m[1])
const declared = [...dictionary.matchAll(/'(notice\.[a-z]+)':/g)].map((m) => m[1])
const missing = [...new Set(used)].filter((key) => !declared.includes(key))
check('每个用到的文案键都有字典条目', missing.length === 0, missing.join(','))
const unused = declared.filter((key) => !used.includes(key))
check('没有留下没人用的文案键', unused.length === 0, unused.join(','))

if (failed > 0) {
  console.error(`\n${failed} 项不通过，${passed} 项通过`)
  process.exit(1)
}
console.log(`任务通知结构检查通过：${passed} 项断言全部成立`)
