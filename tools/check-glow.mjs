/**
 * Regression checks for the Glow host half.
 *
 * Everything asserted here is pure: presets, the frozen list, the role
 * sentences, size snapping and settings migration. None of it needs a running
 * plugin context or a live image service, so the whole file is safe to run
 * anywhere Node is installed:
 *
 *   node tools/check-glow.mjs
 *
 * It exits non-zero on the first failed group, so it can gate a commit.
 */

import { __test } from '../index.js'

const {
  PRESETS,
  ROLES,
  PRESERVE_ITEMS,
  buildPreserveClause,
  buildSubjectSentence,
  bindPictureTokens,
  normalizeSize,
  snapEdge,
  resolveSettings,
  migrateSettings,
  composeEditPrompt,
} = __test

let failed = 0
let passed = 0

function ok(name, condition, detail) {
  if (condition) {
    passed++
    return
  }
  failed++
  console.error(`FAIL ${name}${detail ? ` — ${detail}` : ''}`)
}

function equal(name, actual, expected) {
  const same = JSON.stringify(actual) === JSON.stringify(expected)
  ok(name, same, same ? '' : `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
}

// --------------------------------------------------------------- 结构常量

/**
 * These counts are the contract the two clients share: nine presets, six
 * roles, twelve protected aspects. A silent edit here changes what every
 * generated instruction says, which is why it is pinned.
 */
equal('预设是 9 个', PRESETS.length, 9)
equal('角色是 6 种', ROLES.length, 6)
equal('受保护项是 12 个', PRESERVE_ITEMS.length, 12)
ok(
  '预设 id 不重复',
  new Set(PRESETS.map((preset) => preset.id)).size === PRESETS.length,
)
ok(
  '角色 id 不重复',
  new Set(ROLES.map((role) => role.id)).size === ROLES.length,
)
ok(
  '每个预设都声明了 allowed changes',
  PRESETS.every((preset) => Array.isArray(preset.mayChange)),
)
ok(
  '编辑类预设都要求主体参考图',
  PRESETS.filter((preset) => preset.scene === 'retouch').every((preset) =>
    (preset.needs || []).includes('subject'),
  ),
)

// ------------------------------------------------------- 冻结清单由预设派生

/**
 * The frozen list must be the total table minus whatever the preset is allowed
 * to change. A preset that both changes and freezes the light is the classic
 * bug this guards: the instruction contradicts itself and the model obeys
 * whichever half it reads last.
 */
for (const preset of PRESETS) {
  const clause = buildPreserveClause(preset.mayChange)
  const allowed = new Set(preset.mayChange)
  for (const [id, label] of PRESERVE_ITEMS) {
    if (allowed.has(id)) {
      ok(`「${preset.label}」的冻结清单不含自己允许改的「${label}」`, !clause.includes(label), clause)
    } else {
      ok(`「${preset.label}」的冻结清单含「${label}」`, clause.includes(label), clause)
    }
  }
}

ok(
  '换光线的冻结清单不含光照方向与强度',
  !buildPreserveClause(['lighting']).includes('光照方向与强度'),
)
ok(
  '换背景的冻结清单不含背景内容',
  !buildPreserveClause(['background', 'lighting', 'palette']).includes('背景内容'),
)
ok(
  '不增删物体默认保留',
  buildPreserveClause([]).includes('不增加也不删除任何物体'),
)
ok(
  '预设允许增删物体时可以去掉那句',
  !buildPreserveClause(['objects']).includes('不增加也不删除任何物体'),
)

// ------------------------------------------------------------- 主体句与角色

ok(
  '主体句始终以 Picture n 开头',
  buildSubjectSentence(1, []).startsWith('以 <Picture 1> 为主体'),
)
ok(
  '换发型时主体句不再要求保留发型',
  !buildSubjectSentence(1, ['hair']).includes('发型'),
)
ok(
  '不改发型时主体句要求保留发型',
  buildSubjectSentence(1, []).includes('发型'),
)

const composed = composeEditPrompt({
  presetId: 'hair-change',
  note: '剪短一点',
  refs: [{ role: 'subject' }],
})
ok('编辑指令包含主体段', composed.prompt.includes('<Picture 1>'))
ok('编辑指令包含用户描述', composed.prompt.includes('剪短一点'))
ok('编辑指令包含冻结段', composed.prompt.includes('保持不变'))
ok('编辑指令包含不增删物体', composed.prompt.includes('不增加也不删除任何物体'))

const defaulted = composeEditPrompt({
  presetId: 'hair-change',
  note: '',
  refs: [{ role: 'subject' }, {}],
})
equal('第 2 张参考图默认角色是 scene', defaulted.roles[1], 'scene')

// --------------------------------------------------- 直接发送（不套模板）

/**
 * The switch on the panel promises "your words go out as written". These
 * assertions are the promise: if a preset line, a role sentence or the freeze
 * list ever sneaks back in, this group fails.
 */
const own = '把图1里的人放到图2的沙滩上，保持原来的光线和肤色'
const bound = '把<Picture 1>里的人放到<Picture 2>的沙滩上，保持原来的光线和肤色'
const verbatim = composeEditPrompt({
  refs: [{ role: 'subject' }, { role: 'scene' }],
  note: own,
  verbatim: true,
})
equal(
  '直接发送时只把 图N 换成 <Picture n>，其余一字不改',
  verbatim.prompt,
  '把<Picture 1>里的人放到<Picture 2>的沙滩上，保持原来的光线和肤色',
)
ok('直接发送时不带预设指令', !verbatim.prompt.includes('把人物从原背景中干净地分离出来'))
ok('直接发送时不带冻结清单', !verbatim.prompt.includes('保持不变'))
ok('直接发送时不带「不增删物体」', !verbatim.prompt.includes('不增加也不删除任何物体'))
ok('直接发送时不带角色句式', !verbatim.prompt.includes('为主体'))
equal('直接发送时不带预设 id', verbatim.presetId, '')
equal('直接发送时标记为 verbatim', verbatim.verbatim, true)

const verbatimPreset = composeEditPrompt({
  refs: [{ role: 'subject' }],
  presetId: 'hair-change',
  note: '换个更短的刘海',
  verbatim: true,
})
ok(
  '直接发送时即使选了预设也不叠加预设指令',
  !verbatimPreset.prompt.includes('改变人物的发型与发色'),
)
equal(
  '直接发送时不因为预设的 needs 而报错',
  verbatimPreset.prompt,
  '换个更短的刘海',
)

const verbatimBlank = (() => {
  try {
    composeEditPrompt({ refs: [{ role: 'subject' }], note: '   ', verbatim: true })
    return 'no-throw'
  } catch (error) {
    return error.status
  }
})()
equal('直接发送时空指令被拒绝', verbatimBlank, 400)

const verbatimNoRefs = (() => {
  try {
    composeEditPrompt({ refs: [], note: own, verbatim: true })
    return 'no-throw'
  } catch (error) {
    return error.status
  }
})()
equal('直接发送时仍要求至少一张参考图', verbatimNoRefs, 400)

// 编号越界不臆造
equal(
  '指向不存在的图时原样保留',
  bindPictureTokens('图5 和 图1', 2),
  '图5 和 <Picture 1>',
)
equal('「第2张」这种说法也能认', bindPictureTokens('把第2张的背景换掉', 3), '把<Picture 2>的背景换掉')
equal('已经是 token 的不再重复替换', bindPictureTokens('<Picture 3> 里的猫', 3), '<Picture 3> 里的猫')
// 地图1 / 示意图2 are descriptions, not image references.
equal('含「图」的词不被拆开', bindPictureTokens('地图1上的标记去掉', 2), '地图1上的标记去掉')
equal('示意图2 也不被拆开', bindPictureTokens('照示意图2摆', 2), '照示意图2摆')
equal('流程图 / 效果图 也不被拆开', bindPictureTokens('按流程图1和效果图2走', 2), '按流程图1和效果图2走')
equal('户型图 / 路线图 也不被拆开', bindPictureTokens('对照户型图1和路线图2', 2), '对照户型图1和路线图2')
equal('「把图1」这种正常说法照常替换', bindPictureTokens('把图1里的人换个背景', 2), '把<Picture 1>里的人换个背景')

// 字符串 "true" 与布尔 true 同义（/preview 与 /edit 必须一致）
const twoRefs = [{ role: 'subject' }, { role: 'scene' }]
const stringFlag = composeEditPrompt({ refs: twoRefs, note: own, verbatim: 'true' })
equal('字符串 "true" 也走直发', stringFlag.prompt, bound)
ok('字符串 "true" 不带模板', !stringFlag.prompt.includes('保持不变'))

// 参考图里混进空值不应崩
const dirty = composeEditPrompt({ refs: [null, { role: 'subject' }], note: own, verbatim: true })
equal('空参考图被忽略后仍能直发', dirty.references, 1)

// --------------------------------------------------------------- 尺寸归一

const GRID = 32
const MIN_EDGE = 256
const MAX_EDGE = 2048

/** The five ratios and three tiers the panel offers must all be legal. */
const TABLE = [
  ['768x768', '1024x1024', '1536x1536'],
  ['672x896', '896x1216', '1152x1536'],
  ['896x672', '1216x896', '1536x1152'],
  ['576x1024', '864x1536', '1152x2048'],
  ['1024x576', '1536x864', '2048x1152'],
]

for (const row of TABLE) {
  for (const size of row) {
    const result = normalizeSize(size)
    equal(`尺寸表里的 ${size} 不被改写`, result.size, size)
    ok(
      `尺寸表里的 ${size} 是 32 的倍数`,
      result.width % GRID === 0 && result.height % GRID === 0,
      result.size,
    )
    ok(
      `尺寸表里的 ${size} 落在 256–2048`,
      result.width >= MIN_EDGE && result.width <= MAX_EDGE && result.height >= MIN_EDGE && result.height <= MAX_EDGE,
      result.size,
    )
  }
}

const tall = normalizeSize({ width: 3024, height: 4032 })
ok(
  '超限的 3:4 原图按比例缩小而不是压成正方',
  Math.abs(tall.width / tall.height - 3 / 4) < 0.03,
  `${tall.size} 比例 ${(tall.width / tall.height).toFixed(3)}`,
)
equal('超限的 3:4 原图最大边收敛到 2048', Math.max(tall.width, tall.height), 2048)

const wide = normalizeSize({ width: 4032, height: 3024 })
ok(
  '超限的 4:3 原图方向不被颠倒',
  wide.width > wide.height,
  wide.size,
)

equal('空尺寸回落到 1024x1024', normalizeSize('').size, '1024x1024')
equal('非法尺寸抛出可读错误', (() => {
  try {
    normalizeSize('很大')
    return 'no-throw'
  } catch (error) {
    return /宽x高/.test(error.message) ? 'readable' : 'unclear'
  }
})(), 'readable')
equal('吸附走最近的一格', snapEdge(1000, MAX_EDGE), 992)

// ----------------------------------------------------------------- 设置迁移

const migrated = migrateSettings({ imageBaseUrl: 'http://127.0.0.1:9741' })
equal('旧设置迁移到队列端口', migrated.imageBaseUrl, 'http://127.0.0.1:9742')
equal('旧设置迁移后模式是队列', migrated.imageApiMode, 'queue')
equal('旧地址留作回滚地址', migrated.imageSyncBaseUrl, 'http://127.0.0.1:9741')

const kept = migrateSettings({ imageApiMode: 'sync', imageBaseUrl: 'http://192.168.1.9:9741' })
equal('已是新设置时不迁移', kept.imageApiMode, 'sync')

const defaults = resolveSettings({})
equal('保留参考图副本默认开', defaults.keepReferenceCopies, true)
equal('字符串 false 也要关掉', resolveSettings({ keepReferenceCopies: 'false' }).keepReferenceCopies, false)
equal('字符串 true 要打开', resolveSettings({ keepReferenceCopies: 'true' }).keepReferenceCopies, true)
equal('默认模式是队列', defaults.imageApiMode, 'queue')

let rejected = 0
try {
  resolveSettings({ imageBaseUrl: 'ftp://example.com' })
} catch (error) {
  rejected = error.status === 400 ? 400 : 500
}
equal('非 http(s) 地址被拒绝', rejected, 400)

// ---------------------------------------------------------------------- 汇总

if (failed > 0) {
  console.error(`\n${failed} 项不通过，${passed} 项通过`)
  process.exit(1)
}
console.log(`Glow 回归检查通过：${passed} 项断言全部成立`)
