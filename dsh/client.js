/* Glow（阁楼） — Client half.
 *
 * Registers a sidebar panel icon and the main-panel page it opens. Every
 * operation goes through the Host routes in index.js; this file owns no
 * service logic.
 *
 * Three scenarios, written for someone who does not want to learn terms:
 *   ① 写一句话出图
 *   ② 拿一张照片精修（换背景 / 换衣服 …，预设负责"只改这里、其余尽量不动"）
 *   ③ 多张图合在一起（每张图指定"用来做什么"）
 *
 * Generations run as Host-side jobs, so leaving this panel, switching tabs, or
 * a reload never loses a result: the page re-reads the job list when it mounts.
 */

window.__ModuleLoader__.load({
  id: '@local/glow',
  factory(require) {
    const React = require('react')
    const h = React.createElement

    const NS = 'glow'
    const PANEL_ID = 'glow'
    const ROUTE = '/api/glow'

    // ------------------------------------------------------------- utilities

    async function call(path, options) {
      const opts = options || {}
      const init = { method: opts.method || 'GET' }
      const headers = {}
      if (opts.body !== undefined) {
        headers['content-type'] = 'application/json'
        init.body = JSON.stringify(opts.body)
      }
      if ((opts.method || 'GET') === 'POST') headers['x-glow'] = '1'
      init.headers = headers
      // A caller with nobody waiting on screen (the job watcher) must not be able
      // to hang for the life of the session on one stuck request.
      if (opts.timeoutMs) init.signal = AbortSignal.timeout(opts.timeoutMs)
      let res
      try {
        res = await fetch(ROUTE + path, init)
      } catch (error) {
        throw new Error('没能连上本地插件服务：' + error.message)
      }
      const text = await res.text()
      let data = {}
      try {
        data = text ? JSON.parse(text) : {}
      } catch {
        data = { error: text }
      }
      if (!res.ok) throw new Error(data.error || 'HTTP ' + res.status)
      return data
    }

    function formatDuration(seconds) {
      const value = Number(seconds)
      if (!Number.isFinite(value) || value <= 0) return ''
      if (value < 60) return value.toFixed(0) + ' 秒'
      const m = Math.floor(value / 60)
      const s = Math.round(value % 60)
      return s ? m + ' 分 ' + s + ' 秒' : m + ' 分钟'
    }

    function parseAspect(size) {
      const match = /^(\d+)\s*[x×]\s*(\d+)$/i.exec(String(size || ''))
      if (!match) return '1 / 1'
      return match[1] + ' / ' + match[2]
    }

    function edgeOf(size) {
      const match = /^(\d+)\s*[x×]\s*(\d+)$/i.exec(String(size || ''))
      return match ? Math.max(Number(match[1]), Number(match[2])) : 1024
    }

    /**
     * Rough wait to show the user, from measured timings. For edits the cost is
     * driven by the number of references, not by the canvas size: 1 reference
     * measured 156 s at 256² and 220 s at 1024², while 2 references took
     * 301–382 s at every size tried. So the edit estimate keys off the count.
     */
    function estimateSeconds(size, referenceCount, batch) {
      const edge = edgeOf(size)
      const images = Math.max(1, Number(batch) || 1)
      if (referenceCount > 0) {
        return Math.round(180 + 150 * (referenceCount - 1))
      }
      const base = edge >= 2048 ? 180 : edge >= 1024 ? 72 : edge >= 768 ? 55 : 32
      return Math.round(base * images)
    }

    /** Turn the model token users should never have to read into their own numbering. */
    function humanisePrompt(text) {
      return String(text || '').replace(/<Picture\s+(\d+)>/gi, '图$1')
    }

    function fileUrl(name) {
      return ROUTE + '/file/' + encodeURIComponent(String(name))
    }

    /**
     * Fixed size table: five ratios × three densities. The old scattered
     * choices (512², 1088×1920 …) are gone on purpose — every size the model
     * actually draws well is in here, and a wrong one is no longer one click away.
     */
    const RATIOS = [
      { id: '1:1', label: '1:1', pixels: { fast: '768x768', normal: '1024x1024', sharp: '1536x1536' } },
      { id: '3:4', label: '3:4', pixels: { fast: '672x896', normal: '896x1216', sharp: '1152x1536' } },
      { id: '4:3', label: '4:3', pixels: { fast: '896x672', normal: '1216x896', sharp: '1536x1152' } },
      { id: '9:16', label: '9:16', pixels: { fast: '576x1024', normal: '864x1536', sharp: '1152x2048' } },
      { id: '16:9', label: '16:9', pixels: { fast: '1024x576', normal: '1536x864', sharp: '2048x1152' } },
    ]

    /** Edits add one pseudo-ratio: keep the source photo's own framing. */
    const SOURCE_RATIO = { id: 'source', label: '跟随原图', pixels: null }

    /**
     * Resolution budgets. The model is trained near 1024², and an edit's waiting
     * time is dominated by how many references it has to read rather than by
     * canvas size — so "推荐" keeps the trained-on pixel budget while inheriting
     * the source photo's aspect ratio.
     */
    const DENSITY = [
      { id: 'fast', label: '快速', note: '省时间，细节少一些', area: 640 * 640 },
      { id: 'normal', label: '推荐', note: '约 1024² 的清晰度', area: 1024 * 1024 },
      { id: 'sharp', label: '高清', note: '更锐利，慢一些', area: 1440 * 1440 },
    ]

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

    /**
     * Fit a source photo's aspect ratio into the service's legal grid
     * (multiple of 32, 256–2048) at a target pixel budget.
     */
    function fitSize(sourceWidth, sourceHeight, area, multiple, minEdge, maxEdge) {
      if (!sourceWidth || !sourceHeight) return null
      const aspect = sourceWidth / sourceHeight
      const snap = (value) => {
        const rounded = Math.round(value / multiple) * multiple
        return Math.min(maxEdge, Math.max(minEdge, rounded))
      }
      const relativeError = (a, b) => Math.abs(a - b) / b
      // Derive from the width first, then try deriving from the height too and
      // keep whichever stays closer to the source framing. A clamped edge (very
      // wide or very tall photos) is where the first guess drifts.
      const width = snap(Math.sqrt(area * aspect))
      const height = snap(width / aspect)
      const alternative = snap(height * aspect)
      const useAlternative =
        relativeError(alternative / height, aspect) < relativeError(width / height, aspect) - 0.0005
      const finalWidth = useAlternative ? alternative : width
      return {
        width: finalWidth,
        height,
        text: finalWidth + 'x' + height,
        ratio: ratioLabel(finalWidth, height),
        pixels: finalWidth * height,
      }
    }

    function readImageSize(dataUrl) {
      return new Promise((resolve) => {
        try {
          const img = new Image()
          img.onload = () => resolve({ w: img.naturalWidth || 0, h: img.naturalHeight || 0 })
          img.onerror = () => resolve({ w: 0, h: 0 })
          img.src = dataUrl
        } catch {
          resolve({ w: 0, h: 0 })
        }
      })
    }

    const STYLE_CHIPS = ['电影感', '日系清新', '产品摄影', '水墨国风', '3D 渲染', '胶片颗粒']
    const LIGHT_CHIPS = ['自然晨光', '柔和棚拍光', '侧逆光', '黄金时刻', '阴天漫射', '夜灯氛围']
    const SHOT_CHIPS = ['特写', '半身', '全身', '俯视', '微距', '广角环境', '浅景深']

    const SHORT_ROLE = {
      subject: '主体',
      background: '背景',
      outfit: '服装',
      style: '色调画风',
      scene: '构图',
      detail: '细节',
    }

    /** What the second photo of a retouch job may be used for. */
    const EXTRA_ROLE_CHOICES = [
      { id: 'background', label: '当作新背景' },
      { id: 'outfit', label: '当作新衣服' },
      { id: 'style', label: '只参考色调' },
      // The default for a second photo: it is usually meant to lend its framing
      // and camera angle, which is the reading that keeps the person intact.
      { id: 'scene', label: '只参考构图与机位' },
      { id: 'detail', label: '只参考局部细节' },
    ]

    const MAX_REF_BYTES = 8 * 1024 * 1024
    const MAX_TOTAL_BYTES = 60 * 1024 * 1024
    // Five is the most anyone will sit through: each extra reference adds
    // roughly 150 s (measured), so a six-reference round is already a quarter of
    // an hour of waiting for one picture.
    const MAX_COMBINE_REFS = 5

    const SCENARIOS = [
      { id: 'text', icon: '✎', title: '写一句话出图', summary: '没有素材，只想画一张图' },
      { id: 'retouch', icon: '◐', title: '拿一张照片精修', summary: '换背景、换衣服、换发型，其余尽量不动' },
      { id: 'combine', icon: '❏', title: '多张图合在一起', summary: '把这张的人放进那张的景，并指定每张图做什么' },
    ]

    /**
     * Four end states, four different truths. "取消" and "放弃" must never be
     * collapsed: the first one never ran, the second one ran and threw the
     * result away because the user asked it to.
     */
    const JOB_STATUS = {
      done: { word: '已完成', tone: 'ok' },
      failed: { word: '没成功', tone: 'bad' },
      canceled: { word: '已取消（模型还没开始）', tone: '' },
      // Still running on the service; this plugin just stopped watching.
      unretrieved: { word: '结果待取回', tone: 'warn' },
      // No longer produced, but records written by older versions still say it.
      abandoned: { word: '已放弃（旧版本的行为）', tone: '' },
    }

    function jobStatusOf(job) {
      return JOB_STATUS[job && job.status] || { word: String((job && job.status) || '进行中'), tone: '' }
    }

    /** `1024x1024` → `1024×1024` for anything the user reads. */
    function prettySize(size) {
      return String(size || '').replace(/\s*[x×*]\s*/i, '×')
    }

    function shortTime(stamp) {
      const value = Number(stamp)
      if (!value) return ''
      try {
        const date = new Date(value)
        const pad = (n) => String(n).padStart(2, '0')
        const today = new Date()
        const sameDay =
          date.getFullYear() === today.getFullYear() &&
          date.getMonth() === today.getMonth() &&
          date.getDate() === today.getDate()
        const clock = pad(date.getHours()) + ':' + pad(date.getMinutes())
        return sameDay ? clock : date.getMonth() + 1 + '月' + date.getDate() + '日 ' + clock
      } catch {
        return ''
      }
    }

    /** Bytes behind a data URL, without decoding it. */
    function dataUrlBytes(dataUrl) {
      const comma = String(dataUrl || '').indexOf(',')
      if (comma < 0) return 0
      const body = dataUrl.slice(comma + 1)
      const pad = body.endsWith('==') ? 2 : body.endsWith('=') ? 1 : 0
      return Math.max(0, Math.floor((body.length * 3) / 4) - pad)
    }

    function readAsDataUrl(blob) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result))
        reader.onerror = () => reject(new Error('这张图读不出来'))
        reader.readAsDataURL(blob)
      })
    }

    function rid() {
      return Math.random().toString(36).slice(2)
    }

    function mb(bytes) {
      return Math.round((Number(bytes) || 0) / (1024 * 1024) * 10) / 10 + ' MB'
    }

    /**
     * Anything pasted in becomes PNG. Browsers hand over whatever format the
     * source page used (webp/avif, often with transparency), and the service
     * wants one predictable thing — so redraw it on a canvas.
     */
    async function bitmapToPng(blob, maxEdge) {
      const bitmap = await createImageBitmap(blob)
      try {
        const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height))
        const width = Math.max(1, Math.round(bitmap.width * scale))
        const height = Math.max(1, Math.round(bitmap.height * scale))
        const canvas = document.createElement('canvas')
        canvas.width = width
        canvas.height = height
        const context = canvas.getContext('2d')
        if (!context) throw new Error('这台设备画不出图来')
        context.drawImage(bitmap, 0, 0, width, height)
        return { dataUrl: canvas.toDataURL('image/png'), w: width, h: height }
      } finally {
        if (bitmap && bitmap.close) bitmap.close()
      }
    }

    // ----------------------------------------------------------------- style

    const CSS = `
/* ---- 设计角色 → 宿主变量（换配色只改这张表，下面的规则里不写色值）
 *  页面底          --dsw-alias-bg-base
 *  卡片            --dsw-alias-bg-layer-1       卡片内嵌块 --dsw-alias-bg-layer-2
 *  主色            --dsw-alias-brand-primary    选中文字 = 主色
 *  选中填充        color-mix(in oklab, 主色 14%, 卡片)
 *  发丝线          --dsw-alias-border-l1
 *  描边/输入框     --dsw-alias-border-l2（比卡片深一档；聚焦才换主色）
 *  正文/说明       --dsw-alias-label-primary / --dsw-alias-label-secondary
 *  错误/成功       --dsw-alias-state-error-primary / --dsw-alias-state-success-primary
 *  忙碌            --dsw-alias-state-warn-primary
 *  卡片阴影        正文色派生：color-mix(in oklab, var(--dsw-alias-label-primary) 8%, transparent)
 *  看图器外壳      唯一例外：始终深色，用系统色 Canvas / CanvasText + color-scheme: dark
 * 圆角：卡片 20 · 场景卡 16 · 缩略图 16 · chip 10 · 主按钮 14 · 弹层 28 · 看图器内图 12
 * 字号：大标题 24 · 标题 17 · 正文 14 · 说明 12（只有这四档）
 * ------------------------------------------------------------------- */
.mw-root { position: relative; display: flex; flex-direction: column; height: 100%; min-height: 0;
  background: var(--dsw-alias-bg-base); color: var(--dsw-alias-label-primary);
  font-size: 14px; line-height: 1.6;
  --mw-fill: color-mix(in oklab, var(--dsw-alias-brand-primary) 14%, var(--dsw-alias-bg-layer-1));
  --mw-hair: var(--dsw-alias-border-l1);
  --mw-edge: var(--dsw-alias-border-l2);
  --mw-shadow: color-mix(in oklab, var(--dsw-alias-label-primary) 8%, transparent); }
.mw-root :is(button, a, input, select, textarea):focus-visible {
  outline: 2px solid var(--dsw-alias-brand-primary); outline-offset: 2px; }

/* ---- 顶栏：标题 + 任务胶囊 + 连接胶囊 + 齿轮 */
.mw-head { display: flex; align-items: center; gap: 8px; padding: 12px 20px;
  border-bottom: 1px solid var(--mw-hair); flex: none; }
.mw-title { font-size: 17px; font-weight: 600; }
.mw-headright { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.mw-pill { display: inline-flex; align-items: center; gap: 6px; font: inherit; font-size: 12px;
  min-height: 28px; padding: 2px 11px; border-radius: 999px; cursor: pointer;
  color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-bg-layer-1);
  border: 1px solid var(--mw-hair); white-space: nowrap; }
.mw-pill:hover { color: var(--dsw-alias-label-primary); border-color: var(--mw-edge); }
.mw-dot { width: 7px; height: 7px; border-radius: 50%; flex: none;
  background: var(--dsw-alias-label-secondary); }
.mw-dot.ok { background: var(--dsw-alias-state-success-primary); }
.mw-dot.busy { background: var(--dsw-alias-state-warn-primary); }
.mw-dot.bad { background: var(--dsw-alias-state-error-primary); }
.mw-gear { font: inherit; font-size: 14px; cursor: pointer; min-height: 28px; min-width: 28px;
  display: inline-flex; align-items: center; justify-content: center; padding: 2px 8px;
  border-radius: 10px; border: 1px solid var(--mw-hair);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }

/* ---- 主体两栏 */
.mw-body { display: flex; flex: 1 1 auto; min-height: 0; }
.mw-controls { width: 404px; flex: none; overflow-y: auto; display: flex;
  flex-direction: column; border-right: 1px solid var(--mw-hair); }
.mw-fields { flex: 1 1 auto; padding: 16px 20px 20px; }
.mw-footer { flex: none; padding: 10px 20px 16px; border-top: 1px solid var(--mw-hair);
  background: var(--dsw-alias-bg-base); }
.mw-right { position: relative; flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; }
.mw-results { flex: 1 1 auto; overflow-y: auto; padding: 16px 20px 72px; min-width: 0; }

/* ---- 场景卡：选中＝填充＋右上 ✓，不加描边（描边会和输入框聚焦态撞车） */
.mw-scenarios { display: grid; grid-template-columns: 1fr; gap: 8px; margin-bottom: 18px; }
.mw-scenario { position: relative; display: flex; gap: 10px; align-items: flex-start;
  text-align: left; font: inherit; cursor: pointer; padding: 11px 13px; border-radius: 16px;
  border: 1px solid var(--mw-hair); background: var(--dsw-alias-bg-layer-1);
  color: var(--dsw-alias-label-primary); }
.mw-scenario:hover { border-color: var(--mw-edge); }
.mw-scenario.on { border-color: transparent; background: var(--mw-fill); }
.mw-sicon { font-size: 17px; line-height: 1.2; flex: none; color: var(--dsw-alias-label-secondary); }
.mw-scenario.on .mw-sicon { color: var(--dsw-alias-brand-primary); }
.mw-stitle { font-size: 17px; font-weight: 600; }
.mw-ssum { font-size: 12px; color: var(--dsw-alias-label-secondary); }
.mw-tick { position: absolute; top: 8px; right: 10px; font-size: 12px; font-weight: 700;
  color: var(--dsw-alias-brand-primary); }

/* ---- 表单 */
.mw-step { display: flex; align-items: baseline; gap: 6px; font-size: 14px; font-weight: 600;
  margin: 20px 0 8px; }
.mw-step:first-child { margin-top: 0; }
.mw-field { margin-bottom: 14px; }
.mw-label { display: flex; align-items: center; gap: 6px; font-size: 12px;
  color: var(--dsw-alias-label-secondary); margin-bottom: 6px; font-weight: 500; }
.mw-hint { font-size: 12px; color: var(--dsw-alias-label-secondary); margin-top: 4px; }
/* A real checkbox, not a styled div: keyboard, screen readers and the host's
   own form styles all keep working, and accent-color makes it match the brand. */
.mw-switch { display: flex; align-items: center; gap: 8px; min-height: 28px;
  font-size: 12px; color: var(--dsw-alias-label-secondary); cursor: pointer; margin-bottom: 6px; }
.mw-switch input { width: 16px; height: 16px; margin: 0; flex: none; cursor: pointer;
  accent-color: var(--dsw-alias-brand-primary); }
.mw-switch input:disabled { cursor: default; }
.mw-warn { font-size: 12px; color: var(--dsw-alias-state-warn-label); margin-top: 4px; }
.mw-err { font-size: 12px; color: var(--dsw-alias-state-error-primary);
  background: color-mix(in oklab, var(--dsw-alias-state-error-primary) 10%, var(--dsw-alias-bg-layer-1));
  border: 1px solid var(--dsw-alias-state-error-primary);
  border-radius: 10px; padding: 8px 10px; margin-bottom: 12px; white-space: pre-wrap; }
.mw-in, .mw-ta, .mw-sel { width: 100%; box-sizing: border-box; font: inherit; font-size: 14px;
  color: var(--dsw-alias-label-primary); background: var(--dsw-alias-bg-layer-1);
  border: 1px solid var(--mw-edge); border-radius: 10px; padding: 8px 10px; }
.mw-ta { resize: vertical; min-height: 84px; line-height: 1.6; }
.mw-in:focus, .mw-ta:focus, .mw-sel:focus { border-color: var(--dsw-alias-brand-primary); }
.mw-in:disabled, .mw-ta:disabled, .mw-sel:disabled { color: var(--dsw-alias-label-secondary);
  background: var(--dsw-alias-bg-layer-2); cursor: not-allowed; }
.mw-row { display: flex; gap: 8px; align-items: center; }
.mw-row > * { min-width: 0; }
.mw-btn { font: inherit; font-size: 14px; cursor: pointer; border-radius: 10px;
  padding: 7px 13px; border: 1px solid var(--mw-hair); min-height: 28px;
  /* inline-flex, not the default inline-block: an <a class="mw-btn"> would
     otherwise sit its label on the first baseline and look off-centre next to
     the real buttons beside it. */
  display: inline-flex; align-items: center; justify-content: center;
  box-sizing: border-box; line-height: 1.25; text-decoration: none;
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); white-space: nowrap; }
.mw-btn:hover:not(:disabled) { border-color: var(--mw-edge); }
/* 禁用态不整体降透明度：文字降一档色、底色降一档，保持可读 */
.mw-btn:disabled { cursor: not-allowed; color: var(--dsw-alias-label-secondary);
  background: var(--dsw-alias-bg-layer-2); border-color: var(--mw-hair); }
.mw-btn.primary { background: var(--dsw-alias-brand-primary);
  border-color: var(--dsw-alias-brand-primary); color: var(--dsw-alias-label-primary-foreground);
  font-weight: 600; width: 100%; padding: 11px; font-size: 14px; border-radius: 14px; }
.mw-btn.primary:disabled { background: var(--dsw-alias-bg-layer-2); border-color: var(--mw-hair);
  color: var(--dsw-alias-label-secondary); }
.mw-btn.tiny { padding: 4px 9px; font-size: 12px; border-radius: 10px;
  min-height: 28px; min-width: 28px; }
.mw-btn.danger { color: var(--dsw-alias-state-error-primary);
  border-color: var(--dsw-alias-state-error-primary); }
.mw-btn.dashed { border-style: dashed; width: 100%; padding: 16px; font-size: 14px;
  color: var(--dsw-alias-label-secondary); }
.mw-chips { display: flex; flex-wrap: wrap; gap: 6px; }
/* 选项 chip 的选中态：1.5px 主色描边 + 填充 + ✓ */
.mw-chip { font: inherit; font-size: 12px; cursor: pointer; border-radius: 10px;
  min-height: 28px; padding: 3px 10px; border: 1px solid var(--mw-hair);
  background: transparent; color: var(--dsw-alias-label-secondary); }
.mw-chip:hover:not(:disabled) { color: var(--dsw-alias-label-primary); border-color: var(--mw-edge); }
.mw-chip.on { border: 1.5px solid var(--dsw-alias-brand-primary); background: var(--mw-fill);
  color: var(--dsw-alias-brand-primary); font-weight: 600; }
.mw-presets { display: flex; flex-wrap: wrap; gap: 6px; }
.mw-preset { position: relative; font: inherit; font-size: 14px; cursor: pointer;
  border-radius: 16px; padding: 8px 12px; border: 1px solid var(--mw-hair);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }
.mw-preset:hover { border-color: var(--mw-edge); }
.mw-preset.on { border-color: transparent; background: var(--mw-fill);
  color: var(--dsw-alias-brand-primary); font-weight: 600; padding-right: 26px; }
.mw-preset .mw-tick { top: 2px; right: 6px; }
.mw-note { border: 1px solid var(--mw-hair); border-radius: 12px;
  padding: 9px 11px; background: var(--dsw-alias-bg-layer-2); font-size: 12px;
  color: var(--dsw-alias-label-secondary); margin-top: 8px; }
.mw-note b { color: var(--dsw-alias-label-primary); }

/* ---- 参考图 */
.mw-refs { display: flex; flex-wrap: wrap; gap: 12px; }
.mw-ref { width: 132px; }
.mw-ref .thumb { position: relative; }
.mw-ref img { width: 132px; height: 132px; object-fit: cover; border-radius: 16px;
  border: 1px solid var(--mw-hair); display: block; background: var(--dsw-alias-bg-layer-2); }
.mw-refno { position: absolute; top: 6px; left: 6px; background: var(--dsw-alias-bg-overlay);
  color: var(--dsw-alias-label-primary); border: 1px solid var(--mw-edge);
  font-size: 12px; line-height: 1.2; padding: 2px 6px; border-radius: 10px; }
.mw-refops { display: flex; gap: 4px; margin-top: 6px; justify-content: center; }
.mw-refrole { width: 100%; margin-top: 6px; font-size: 12px; padding: 6px;
  border-radius: 10px; border: 1px solid var(--mw-edge);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }
.mw-addref { width: 132px; height: 132px; padding: 0; font-size: 24px; border-radius: 16px; }
.mw-refadd { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.mw-refadd .mw-btn { flex: 1 1 0; min-width: 120px; }

/* ---- 尺寸摘要（一行） */
.mw-sizeline { display: flex; align-items: center; gap: 8px; width: 100%;
  box-sizing: border-box; font: inherit; font-size: 14px; text-align: left; cursor: pointer;
  padding: 9px 12px; border-radius: 12px; border: 1px solid var(--mw-edge);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }
.mw-sizeline:hover { border-color: var(--dsw-alias-brand-primary); }
.mw-sizeline .fill { flex: 1 1 auto; min-width: 0; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.mw-sizeline .px { color: var(--dsw-alias-label-secondary); font-variant-numeric: tabular-nums;
  white-space: nowrap; }

/* ---- 结果卡（浅色：无描边 + 轻微阴影；深色：1px 发丝线） */
.mw-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 12px; }
.mw-card { border: 1px solid transparent; border-radius: 20px; overflow: hidden;
  background: var(--dsw-alias-bg-layer-1); display: flex; flex-direction: column;
  box-shadow: 0 2px 10px var(--mw-shadow); }
/* 深色下阴影看不见，改用 1px 发丝线；边框一直占位，切换主题不跳版 */
@media (prefers-color-scheme: dark) {
  .mw-card { box-shadow: none; border-color: var(--mw-hair); }
}
.mw-thumbimg { width: 100%; display: block; background: var(--dsw-alias-bg-layer-2); }
.mw-cardbody { padding: 9px 11px 11px; display: flex; flex-direction: column; gap: 6px; }
.mw-meta { font-size: 12px; color: var(--dsw-alias-label-secondary);
  display: flex; flex-wrap: wrap; gap: 8px; }
.mw-cardact { display: flex; gap: 6px; flex-wrap: wrap; }
.mw-imgfallback { display: flex; align-items: center; justify-content: center;
  min-height: 96px; padding: 12px; font-size: 12px; text-align: center;
  color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-bg-layer-2); }
.mw-thumbbtn { display: block; width: 100%; padding: 0; margin: 0; border: none;
  background: none; cursor: zoom-in; }
.mw-sec { font-size: 12px; font-weight: 600; color: var(--dsw-alias-label-secondary);
  margin: 22px 0 10px; display: flex; align-items: center; gap: 8px; }
.mw-sec:first-child { margin-top: 0; }
.mw-empty { color: var(--dsw-alias-label-secondary); font-size: 14px; padding: 40px 20px;
  text-align: center; border: 1px dashed var(--mw-hair); border-radius: 20px; }

/* ---- 任务：进行中 + 最近记录 */
.mw-job { display: flex; align-items: flex-start; gap: 10px; font-size: 14px;
  background: var(--dsw-alias-bg-layer-1); border: 1px solid var(--mw-hair);
  border-radius: 16px; padding: 10px 13px; margin-bottom: 8px; }
.mw-job .grow { flex: 1 1 auto; min-width: 0; }
.mw-jobnote { font-size: 12px; color: var(--dsw-alias-label-secondary); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; margin-top: 2px; }
.mw-progress { display: block; width: 100%; height: 6px; margin-top: 6px;
  accent-color: var(--dsw-alias-brand-primary); }
.mw-jobsrow { display: flex; align-items: center; gap: 8px; font-size: 12px;
  color: var(--dsw-alias-label-secondary); padding: 7px 0;
  border-bottom: 1px solid var(--mw-hair); }
.mw-jobsrow:last-child { border-bottom: none; }
.mw-jobsrow .grow { flex: 1 1 auto; min-width: 0; }
.mw-stat { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
  background: var(--dsw-alias-bg-layer-2); color: var(--dsw-alias-label-secondary);
  white-space: nowrap; }
.mw-stat.ok { color: var(--dsw-alias-state-success-primary); }
.mw-stat.bad { color: var(--dsw-alias-state-error-primary); }
/* 待取回：不是失败也不是成功，用警告色免得被当成"已经没了" */
.mw-stat.warn { color: var(--dsw-alias-state-warn-primary);
  background: color-mix(in oklab, var(--dsw-alias-state-warn-primary) 14%, var(--dsw-alias-bg-layer-1)); }
.mw-spin { width: 12px; height: 12px; border-radius: 50%; flex: none;
  border: 2px solid var(--mw-edge);
  border-top-color: var(--dsw-alias-brand-primary); animation: mw-spin .8s linear infinite; }
@keyframes mw-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .mw-spin { animation: none; } }

/* ---- 杂项 */
.mw-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.mw-path { font-size: 12px; color: var(--dsw-alias-label-secondary); word-break: break-all; }
.mw-disclosure { font: inherit; font-size: 12px; cursor: pointer; background: none;
  border: none; padding: 2px 0; min-height: 28px; color: var(--dsw-alias-label-secondary);
  text-decoration: underline; }
.mw-code { font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-word;
  background: var(--dsw-alias-bg-layer-2); border-radius: 12px; padding: 8px 10px;
  margin-top: 6px; color: var(--dsw-alias-label-secondary); }
.mw-fav { display: flex; flex-direction: column; gap: 6px; }
.mw-favrow { display: flex; align-items: flex-start; gap: 6px; }
.mw-favtext { flex: 1 1 auto; font: inherit; font-size: 12px; line-height: 1.5; text-align: left;
  color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-bg-layer-2);
  border: 1px solid transparent; border-radius: 10px; padding: 6px 8px; cursor: pointer;
  max-height: 48px; overflow: hidden; }
.mw-favtext:hover { border-color: var(--mw-edge); color: var(--dsw-alias-label-primary); }

/* ---- 提示条 */
.mw-toasts { position: absolute; left: 20px; right: 20px; bottom: 16px; display: flex;
  flex-direction: column; gap: 8px; pointer-events: none; z-index: 6; }
.mw-toast { pointer-events: auto; display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 12px; border-radius: 16px; font-size: 12px;
  background: var(--dsw-alias-bg-layer-1); border: 1px solid var(--mw-edge);
  box-shadow: 0 10px 28px var(--mw-shadow); }
.mw-toast.bad { border-color: var(--dsw-alias-state-error-primary);
  background: color-mix(in oklab, var(--dsw-alias-state-error-primary) 10%, var(--dsw-alias-bg-layer-1)); }
.mw-toast .grow { flex: 1 1 auto; min-width: 0; word-break: break-word; }

/* ---- 原生 <dialog>：焦点、Esc、背景交给浏览器；只在 [open] 时改 display */
.mw-dlg { border: none; padding: 0; margin: auto; width: 100%;
  max-width: min(560px, calc(100vw - 32px)); max-height: min(84vh, 880px);
  border-radius: 28px; background: var(--dsw-alias-bg-layer-1);
  color: var(--dsw-alias-label-primary); font-size: 14px;
  box-shadow: 0 24px 60px color-mix(in oklab, var(--dsw-alias-label-primary) 28%, transparent); }
.mw-dlg[open] { display: flex; flex-direction: column; }
.mw-dlg::backdrop { background: color-mix(in oklab, Canvas 72%, transparent);
  overflow: hidden; overscroll-behavior: contain; }
.mw-dlg:modal { overscroll-behavior: contain; }
.mw-dlghead { flex: none; display: flex; align-items: center; gap: 10px; padding: 16px 20px 8px; }
.mw-dlgtitle { font-size: 24px; font-weight: 600; margin: 0; }
.mw-dlgbody { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 4px 20px 16px; }
.mw-dlgfoot { flex: none; display: flex; gap: 8px; padding: 12px 20px 18px;
  border-top: 1px solid var(--mw-hair); }
.mw-dlgfoot .mw-btn { flex: 1 1 auto; }
/* 底部弹层（尺寸选择器） */
.mw-sheet { position: fixed; inset: auto 0 0 0; margin: 0; width: 100%; max-width: none;
  max-height: 82vh; border: none; padding: 0; border-radius: 28px 28px 0 0;
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary);
  font-size: 14px;
  box-shadow: 0 -18px 48px color-mix(in oklab, var(--dsw-alias-label-primary) 24%, transparent); }
.mw-sheet[open] { display: flex; flex-direction: column; }
.mw-sheet:modal { overscroll-behavior: contain; }
.mw-sheetgrab { flex: none; width: 40px; height: 4px; border-radius: 999px;
  margin: 10px auto 0; background: var(--mw-edge); }

/* ---- 看图器：唯一固定深色的地方。系统色，不跟随宿主主题，不写色值 */
.mw-viewer { position: fixed; inset: 0; width: 100%; height: 100%; max-width: none;
  max-height: none; margin: 0; padding: 0; border: none; color-scheme: dark;
  background: Canvas; color: CanvasText; font-size: 14px; }
.mw-viewer[open] { display: flex; flex-direction: column; }
.mw-viewer::backdrop { background: Canvas; overflow: hidden; overscroll-behavior: contain; }
.mw-viewer:modal { overscroll-behavior: contain; }
.mw-viewerbody { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }
.mw-stage { position: relative; flex: 1 1 auto; min-height: 0; overflow: hidden;
  display: flex; align-items: center; justify-content: center; padding: 12px 16px;
  touch-action: none; cursor: grab; }
.mw-stage.dragging { cursor: grabbing; }
.mw-stage.actual { overflow: auto; }
.mw-zoom { width: 100%; height: 100%; display: flex; align-items: center;
  justify-content: center; transform-origin: center center; }
.mw-zoom > * { max-width: 100%; max-height: 100%; }
.mw-viewerimg { max-width: 100%; max-height: 100%; object-fit: contain; display: block;
  border-radius: 12px; user-select: none; -webkit-user-drag: none; }
.mw-viewerimg.actual { max-width: none; max-height: none; border-radius: 0; }
.mw-stage .mw-imgfallback { background: color-mix(in oklab, CanvasText 8%, Canvas);
  color: GrayText; border-radius: 12px; min-height: 160px; min-width: 240px; }
.mw-cmprow { flex: 1 1 auto; min-height: 0; display: flex; gap: 12px; padding: 12px 16px;
  overflow: hidden; }
.mw-cmpcol { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; }
.mw-cmpcol .mw-stage { width: 100%; padding: 0; }
.mw-cmpcap { flex: none; font-size: 12px; color: GrayText; padding-top: 6px; text-align: center; }
/* 滑动对比：两层必须落在**同一个矩形**里，否则两张图各算各的比例，
   一分就错位。矩形由结果图的尺寸在 JS 里算好（见 wipeBox），两层都用
   cover 填满它，比例不同时被裁的是参考图，不是对齐。 */
.mw-wipebox { position: relative; width: 100%; height: 100%; }
.mw-wipebox img { position: absolute; inset: 0; width: 100%; height: 100%;
  object-fit: cover; user-select: none; -webkit-user-drag: none; border-radius: 12px; }
/* A failed photo renders a message node, which must stay inside the rectangle
   instead of pushing the box taller. */
.mw-wipebox .mw-imgfallback { position: absolute; inset: 0; min-width: 0; min-height: 0;
  font-size: 12px; text-align: center; }
.mw-wipeover { position: absolute; inset: 0; display: block; overflow: hidden; }
/* 分界线画在照片上（不是图片下面的一条滑块） */
.mw-divider { position: absolute; top: 0; bottom: 0; width: 2px; margin-left: -1px; padding: 0;
  border: none; background: color-mix(in oklab, CanvasText 92%, Canvas); cursor: ew-resize;
  /* 白线压在白背景上会消失，所以加一圈深色描边，任何底色上都看得见 */
  box-shadow: 0 0 0 1px color-mix(in oklab, Canvas 70%, transparent); }
.mw-divider::after { content: ""; position: absolute; top: 50%; left: 50%; width: 26px;
  height: 26px; margin: -13px 0 0 -13px; border-radius: 50%; border: 2px solid Canvas;
  background: color-mix(in oklab, CanvasText 92%, Canvas); }
.mw-divider:focus-visible { outline: 2px solid CanvasText; outline-offset: 3px; }
/* 标签放在照片下沿、贴左右轨，绝不压在照片上 */
.mw-captionrow { flex: none; display: flex; align-items: baseline; gap: 12px;
  padding: 6px 16px 0; font-size: 12px; color: GrayText; }
.mw-captionrow > :first-child { flex: 1 1 0; min-width: 0; }
.mw-captionrow > :last-child { flex: 1 1 0; min-width: 0; text-align: right; }
.mw-viewerbar { flex: none; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 10px 16px 14px;
  border-top: 1px solid color-mix(in oklab, CanvasText 18%, Canvas);
  background: Canvas; -webkit-app-region: no-drag; }
.mw-vbtn { font: inherit; font-size: 12px; cursor: pointer; min-height: 28px; min-width: 28px;
  padding: 4px 10px; border-radius: 10px; white-space: nowrap; background: ButtonFace;
  color: ButtonText; border: 1px solid color-mix(in oklab, CanvasText 30%, Canvas); }
.mw-vbtn:hover { border-color: color-mix(in oklab, CanvasText 60%, Canvas); }
.mw-vbtn:disabled { color: GrayText;
  border-color: color-mix(in oklab, CanvasText 15%, Canvas); }
.mw-viewer :is(button, a):focus-visible { outline: 2px solid CanvasText; outline-offset: 2px; }
.mw-zoomgroup { display: inline-flex; align-items: center; gap: 2px; }
.mw-zoomgroup .mw-vbtn { min-width: 28px; padding: 4px 6px; }
/* 四个字的模式按钮需要比「+/−」宽一点，否则挤在一起 */
.mw-comparegroup .mw-vbtn { padding: 4px 11px; }
/* 当前所在的对比方式：填充 + 文案加深，而不是只换个浅灰 */
.mw-vbtn.on { background: color-mix(in oklab, CanvasText 16%, Canvas);
  border-color: color-mix(in oklab, CanvasText 55%, Canvas); font-weight: 600; }
.mw-zoomval { font-size: 12px; color: GrayText; min-width: 44px; text-align: center;
  font-variant-numeric: tabular-nums; }
.mw-menu { position: fixed; inset: auto 16px 62px auto; margin: 0; padding: 6px;
  border-radius: 14px; background: Canvas; color: CanvasText;
  border: 1px solid color-mix(in oklab, CanvasText 24%, Canvas);
  box-shadow: 0 12px 32px color-mix(in oklab, CanvasText 30%, transparent); }
.mw-menu::backdrop { background: transparent; }
.mw-menu > button, .mw-menu > a { display: block; width: 100%; box-sizing: border-box;
  text-align: left; font: inherit; font-size: 12px; min-height: 28px; padding: 6px 10px;
  border-radius: 10px; background: transparent; color: inherit; border: none;
  text-decoration: none; cursor: pointer; white-space: nowrap; }
.mw-menu > :is(button, a):hover { background: color-mix(in oklab, CanvasText 12%, Canvas); }
.mw-viewerhint { font-size: 12px; color: GrayText; margin-left: auto; }

@media (max-width: 900px) {
  .mw-body { flex-direction: column; overflow-y: auto; }
  .mw-controls { width: 100%; border-right: none; }
  .mw-right { min-height: 70vh; }
}
`

    /**
     * Styles for the frame-wide notice, kept **out** of the panel's stylesheet.
     *
     * The panel renders `CSS` from inside its own root, so those rules leave the
     * document with the panel — and the notice exists precisely when the panel is
     * gone. Without these rules the toast renders as loose text, and the hidden
     * live region becomes visible text that also swallows clicks across the top
     * of the window. `apply` installs this once for the plugin's lifetime.
     */
    const NOTICE_CSS = `
/* 外层铺满整框但不吃点击：只有提示本身接住指针，绝不挡住下面的界面。 */
.gl-notices.gl-notices { position: absolute; inset: 0; pointer-events: none; z-index: 1; }
        /* 右下角、抬高到输入框之上：那里不压发送按钮，也比左上更容易被余光看到。 */
.gl-toasts { position: absolute; inset-block-end: 96px; inset-inline-end: 24px;
  display: flex; flex-direction: column; align-items: flex-end; gap: 10px;
  max-width: min(400px, calc(100vw - 48px)); pointer-events: none; }
.gl-toast { pointer-events: auto; display: flex; align-items: flex-start; gap: 10px;
  padding: 14px 12px 14px 14px; border-radius: 16px; line-height: 1.5;
  min-width: 300px;
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary);
  border: 1px solid color-mix(in oklab, var(--dsw-alias-label-primary) 14%, transparent);
  box-shadow: 0 10px 36px color-mix(in oklab, CanvasText 22%, transparent);
  animation: gl-toast-in .2s ease-out; }
.gl-toast.ok { border-inline-start: 4px solid var(--dsw-alias-state-success-primary); }
.gl-toast.unretrieved { border-inline-start: 4px solid var(--dsw-alias-state-warn-primary); }
.gl-toast.failed { border-inline-start: 4px solid var(--dsw-alias-state-error-primary); }
.gl-toast-mark { flex: none; width: 9px; height: 9px; border-radius: 50%; margin-top: 7px;
  background: var(--dsw-alias-label-secondary); }
.gl-toast.ok .gl-toast-mark { background: var(--dsw-alias-state-success-primary); }
.gl-toast.unretrieved .gl-toast-mark { background: var(--dsw-alias-state-warn-primary); }
.gl-toast.failed .gl-toast-mark { background: var(--dsw-alias-state-error-primary); }
.gl-toast-body { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.gl-toast-title { font-size: 15px; font-weight: 600; word-break: break-word; }
.gl-toast-text { font-size: 13.5px; color: var(--dsw-alias-label-secondary); word-break: break-word; }
.gl-toast-open { flex: none; font: inherit; font-size: 14px; font-weight: 600; cursor: pointer;
  background: none; border: none; padding: 4px 8px; border-radius: 10px; align-self: center;
  color: var(--dsw-alias-brand-primary); text-decoration: underline; }
.gl-toast-close { flex: none; font: inherit; font-size: 16px; line-height: 1; cursor: pointer;
  background: none; border: none; padding: 4px 6px; min-width: 32px; min-height: 32px;
  border-radius: 10px; color: var(--dsw-alias-label-secondary); align-self: flex-start; }
.gl-toast-open:hover, .gl-toast-close:hover { background: var(--dsw-alias-bg-layer-2); }
.gl-toast-open:focus-visible, .gl-toast-close:focus-visible { outline: 2px solid var(--dsw-alias-brand-primary); outline-offset: 1px; }
@keyframes gl-toast-in { from { opacity: 0; transform: translateY(10px) scale(.98); } to { opacity: 1; transform: none; } }
@media (prefers-reduced-motion: reduce) { .gl-toast { animation: none; } }
/* 只在读屏器里存在的播报区，自始至终挂在页面上 */
.gl-sr { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0;
  overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
`

    // ------------------------------------------------------------ components

    function PanelIcon(props) {
      const size = (props && props.size) || 24
      const active = Boolean(props && props.active)
      const stroke = active ? 'var(--dsw-alias-brand-primary)' : 'currentColor'
      return h(
        'svg',
        {
          width: size, height: size, viewBox: '0 0 24 24', fill: 'none',
          'aria-hidden': true, style: { display: 'block' },
        },
        h('rect', { x: 2.75, y: 4.25, width: 18.5, height: 15.5, rx: 3, stroke, strokeWidth: 1.6 }),
        h('circle', { cx: 8.4, cy: 9.6, r: 1.55, fill: stroke }),
        h('path', {
          d: 'M4.2 17.3l4.6-4.8c.6-.62 1.6-.62 2.2 0l2.15 2.24 2.05-1.9c.62-.57 1.58-.55 2.17.05l2.45 2.5',
          stroke, strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round',
        }),
        h('path', {
          d: 'M17.4 3.1l.62 1.62 1.62.62-1.62.62-.62 1.62-.62-1.62-1.62-.62 1.62-.62z',
          fill: stroke,
        }),
      )
    }

    function Chips(props) {
      if (!props.items || props.items.length === 0) return null
      return h(
        'div',
        { className: 'mw-chips' },
        props.items.map((item) =>
          h(
            'button',
            {
              key: item, type: 'button', className: 'mw-chip', disabled: props.disabled,
              'aria-pressed': false,
              onClick: () => props.onPick(item),
            },
            item,
          ),
        ),
      )
    }

    /**
     * Every layer in this file is a native <dialog>. The browser already gives
     * us the top layer, focus containment, Esc/back-gesture dismissal and focus
     * return — hand-rolling role="dialog"/aria-modal/focus traps only adds bugs.
     */
    function Modal(props) {
      const ref = React.useRef(null)
      React.useEffect(() => {
        const el = ref.current
        if (!el) return
        if (props.open) {
          // showModal(), never show(), never the open attribute.
          if (!el.open) el.showModal()
          if (props.initialFocus && props.initialFocus.current) props.initialFocus.current.focus()
        } else if (el.open) {
          el.close()
        }
      }, [props.open, props.initialFocus])
      React.useEffect(() => {
        const el = ref.current
        if (!el) return undefined
        const onClose = () => {
          // Esc and the back gesture set no return value; reset it so a stale
          // "delete" cannot leak into the next opening.
          const value = el.returnValue
          el.returnValue = ''
          if (props.onClose) props.onClose(value)
        }
        el.addEventListener('close', onClose)
        return () => el.removeEventListener('close', onClose)
      }, [props.onClose])
      return h(
        'dialog',
        {
          ref,
          className: props.className || '',
          'aria-labelledby': props.labelledBy || undefined,
          'aria-label': props.labelledBy ? undefined : props.label,
        },
        props.children,
      )
    }

    /** An image that says something when it fails, instead of showing a blank box. */
    function Img(props) {
      const [failed, setFailed] = React.useState(false)
      const rest = {}
      for (const key of Object.keys(props)) {
        if (key === 'fallback' || key === 'children') continue
        rest[key] = props[key]
      }
      const fallback = props.fallback || '这张图没能显示出来'
      const style = props.style
      React.useEffect(() => {
        setFailed(false)
      }, [props.src])
      if (failed) {
        return h('div', { className: 'mw-imgfallback ' + (props.className || ''), style }, fallback)
      }
      return h('img', { ...rest, alt: props.alt || '', onError: () => setFailed(true) })
    }

    /** Top-bar capsule: dot + label, always a real button so it can be clicked. */
    function Pill(props) {
      return h(
        'button',
        {
          type: 'button', className: 'mw-pill', title: props.title || '',
          onClick: props.onClick,
        },
        props.tone === null ? null : h('span', { className: 'mw-dot ' + (props.tone || '') }),
        props.label,
      )
    }

    /**
     * One group of single-choice chips. `aria-pressed` carries the state, so
     * "选中" is never expressed by colour alone.
     */
    function ChoiceGroup(props) {
      return h(
        'div',
        { className: 'mw-chips', role: 'group', 'aria-label': props.label },
        props.items.map((item) =>
          h(
            'button',
            {
              key: item.id, type: 'button', className: 'mw-chip' + (props.value === item.id ? ' on' : ''),
              'aria-pressed': props.value === item.id,
              disabled: props.disabled,
              title: item.note || item.label,
              onClick: () => props.onPick(item.id),
            },
            props.value === item.id ? '✓ ' + item.label : item.label,
          ),
        ),
      )
    }

    function useT(ctx) {
      const translate = ctx.locale.bind(NS)
      const [, force] = React.useReducer((x) => x + 1, 0)
      React.useEffect(() => ctx.locale.subscribe(force), [ctx])
      return translate
    }

    // ---------------------------------------------------------------- workbench

    function createWorkbench(ctx, watch) {
      return function Workbench() {
        const t = useT(ctx)

        // While this panel is mounted its own poller owns the job list; the
        // frame-wide watcher stands down so nothing is announced twice. On the
        // way out it hands over two things: what it has looked at, and which
        // jobs it saw but never got to say out loud — its own round runs every
        // 2.5 s, and a job that settled inside that last slice would otherwise
        // be announced by neither side.
        const mountedAt = React.useRef(Date.now())
        const announced = React.useRef(new Set())
        const recentRef = React.useRef([])
        React.useEffect(() => {
          watch.setPanelOpen(true)
          return () => {
            const owed = recentRef.current.filter(
              (job) => Number(job.finishedAt) >= mountedAt.current && !announced.current.has(job.id),
            )
            watch.setPanelOpen(false, { seen: consumed.current, owed })
          }
        }, [])

        const [scenario, setScenario] = React.useState('text')
        const [caps, setCaps] = React.useState(null)
        const [capsLoading, setCapsLoading] = React.useState(true)
        const [capsError, setCapsError] = React.useState('')
        const [presets, setPresets] = React.useState([])
        const [presetsError, setPresetsError] = React.useState('')
        const [roles, setRoles] = React.useState([])

        // Three scenarios, three drafts. Switching scenario must never eat what
        // was already typed in the other one.
        const [drafts, setDrafts] = React.useState({ text: '', retouch: '', combine: '' })
        const [refs, setRefs] = React.useState([])
        const [presetId, setPresetId] = React.useState('')
        const [avoid, setAvoid] = React.useState('')

        const [ratio, setRatio] = React.useState('1:1')
        const [customSize, setCustomSize] = React.useState('')
        const [density, setDensity] = React.useState('normal')
        const [sizeOpen, setSizeOpen] = React.useState(false)
        const [sizeAdvanced, setSizeAdvanced] = React.useState(false)
        const [batch, setBatch] = React.useState(1)
        const [seedMode, setSeedMode] = React.useState('random')
        const [seedValue, setSeedValue] = React.useState('')
        const [advanced, setAdvanced] = React.useState(false)

        const [activeJobs, setActiveJobs] = React.useState([])
        const [recentJobs, setRecentJobs] = React.useState([])
        const [results, setResults] = React.useState([])
        const [history, setHistory] = React.useState([])
        const [error, setError] = React.useState('')
        // One toast at a time: failures stay until dismissed, notices leave on
        // their own after three seconds.
        const [toast, setToast] = React.useState(null)
        const toastTimer = React.useRef(0)
        const [pollError, setPollError] = React.useState('')
        const [previewText, setPreviewText] = React.useState('')
        const [previewBusy, setPreviewBusy] = React.useState(false)
        const [submitting, setSubmitting] = React.useState(false)
        const [cancelBusy, setCancelBusy] = React.useState('')
        const [confirmDelete, setConfirmDelete] = React.useState(null)
        const [pasteHint, setPasteHint] = React.useState('')
        // Ship the user's own wording as the whole instruction: no preset line,
        // no role sentence, no frozen list. Off by default, because the composed
        // instruction is what keeps a non-technical user out of trouble.
        const [verbatim, setVerbatim] = React.useState(false)
        const lastSubmit = React.useRef(null)

        const [assistBusy, setAssistBusy] = React.useState(false)
        const [assistOut, setAssistOut] = React.useState(null)
        const [assistError, setAssistError] = React.useState('')

        // Full-screen viewer: { list, index }. The list is captured at open
        // time so arrow navigation stays predictable while a new job lands.
        const [viewer, setViewer] = React.useState(null)
        const [viewerActual, setViewerActual] = React.useState(false)
        const [viewerMode, setViewerMode] = React.useState('single')
        const [compareLayout, setCompareLayout] = React.useState('side')
        const [cut, setCut] = React.useState(50)
        const [zoom, setZoom] = React.useState(1)
        const [pan, setPan] = React.useState({ x: 0, y: 0 })
        const [dragging, setDragging] = React.useState(false)
        const zoomRef = React.useRef(1)
        const stageRef = React.useRef(null)
        const wipeRef = React.useRef(null)
        const dragRef = React.useRef(null)
        const closeViewerRef = React.useRef(null)
        const deleteCancelRef = React.useRef(null)
        const rootRef = React.useRef(null)
        const armedRef = React.useRef(false)
        // Once the user picks a ratio by hand, switching scenario stops
        // resetting it back to "跟随原图".
        const ratioTouched = React.useRef(false)
        const fileRef = React.useRef(null)
        // Job ids already accounted for in "本次结果", and whether the first
        // /jobs read has happened yet.
        const consumed = React.useRef(new Set())
        const primed = React.useRef(false)

        function notify(text) {
          if (toastTimer.current) clearTimeout(toastTimer.current)
          if (!text) {
            setToast(null)
            return
          }
          setToast({ id: Math.random().toString(36).slice(2), kind: 'info', text })
          toastTimer.current = setTimeout(() => setToast(null), 3000)
        }

        function failToast(text) {
          if (!text) return
          if (toastTimer.current) clearTimeout(toastTimer.current)
          setToast({ id: Math.random().toString(36).slice(2), kind: 'bad', text })
        }

        function resetZoom() {
          zoomRef.current = 1
          setZoom(1)
          setPan({ x: 0, y: 0 })
        }

        /** How many originals this result can show: own copies first, then the task's. */
        function refCountFor(item) {
          if (!item) return 0
          const files = Array.isArray(item.refFiles) ? item.refFiles.filter(Boolean) : []
          if (files.length > 0) return files.length
          const job = recentJobs.find((entry) => entry.id && entry.id === item.queueJobId)
          return job && Array.isArray(job.refUrls) ? job.refUrls.filter(Boolean).length : 0
        }

        function openViewer(list, index) {
          const item = list[index]
          const hasRefs = refCountFor(item) > 0
          setViewer({ list, index })
          setViewerActual(false)
          resetZoom()
          // An edited photo is almost always checked against its source, so
          // open straight into the comparison and let the user switch back.
          setViewerMode(hasRefs ? 'compare' : 'single')
          setCut(50)
        }

        /** Open straight into one of the two comparison layouts. */
        function openCompare(list, index, layout) {
          openViewer(list, index)
          setViewerMode('compare')
          setCompareLayout(layout)
        }

        function stepViewer(delta) {
          setViewer((current) => {
            if (!current || current.list.length === 0) return current
            const next = (current.index + delta + current.list.length) % current.list.length
            return { list: current.list, index: next }
          })
          resetZoom()
        }

        // ---- archive: hidden results, reachable from one entry
        const [archivedOpen, setArchivedOpen] = React.useState(false)
        const [archivedItems, setArchivedItems] = React.useState([])
        const [archivedCount, setArchivedCount] = React.useState(0)
        const [archivedBusy, setArchivedBusy] = React.useState(false)
        const [archivedError, setArchivedError] = React.useState('')

        // ---- saved prompts
        const [favorites, setFavorites] = React.useState([])
        const [favoritesBusy, setFavoritesBusy] = React.useState(false)
        const [favoritesError, setFavoritesError] = React.useState('')

        // ---- model services (stored outside the repository)
        const [settingsOpen, setSettingsOpen] = React.useState(false)
        const [settingsData, setSettingsData] = React.useState(null)
        const [settingsDraft, setSettingsDraft] = React.useState({})
        const [settingsBusy, setSettingsBusy] = React.useState(false)
        const [settingsError, setSettingsError] = React.useState('')
        const [settingsTest, setSettingsTest] = React.useState(null)

        const busy = activeJobs.length > 0 || submitting

        const refreshCaps = React.useCallback(() => {
          setCapsError('')
          setCapsLoading(true)
          return call('/capabilities')
            .then((data) => setCaps(data))
            .catch((err) => setCapsError(err.message))
            .finally(() => setCapsLoading(false))
        }, [])

        const refreshHistory = React.useCallback(() => {
          // The main lists never include archived results.
          return call('/history?limit=60&archived=0')
            .then((data) => {
              setHistory(Array.isArray(data.items) ? data.items : [])
              if (data.counts) setArchivedCount(Number(data.counts.archived) || 0)
            })
            .catch(() => {})
        }, [])

        const refreshArchived = React.useCallback(() => {
          setArchivedBusy(true)
          return call('/history?limit=200&archived=1')
            .then((data) => {
              setArchivedItems(Array.isArray(data.items) ? data.items : [])
              setArchivedError('')
            })
            .catch((err) => setArchivedError('读不到归档：' + err.message))
            .finally(() => setArchivedBusy(false))
        }, [])

        const loadFavorites = React.useCallback(() => {
          return call('/favorites')
            .then((data) => setFavorites(Array.isArray(data.items) ? data.items : []))
            .catch(() => {})
        }, [])

        async function saveFavorite(target) {
          const text = (target === 'text' ? textPrompt : editNote).trim()
          if (!text) {
            setFavoritesError('先把提示词写出来再存')
            return
          }
          setFavoritesBusy(true)
          setFavoritesError('')
          try {
            const data = await call('/favorites', {
              method: 'POST',
              body: { text, mode: target === 'text' ? 'text2image' : 'image2image' },
            })
            setFavorites(Array.isArray(data.items) ? data.items : [])
            notify(data.duplicate ? '这条已经在常用里了' : '已存为常用提示词')
          } catch (err) {
            setFavoritesError(err.message)
          } finally {
            setFavoritesBusy(false)
          }
        }

        async function removeFavorite(id) {
          setFavoritesBusy(true)
          try {
            const data = await call('/favorites/remove', { method: 'POST', body: { id } })
            setFavorites(Array.isArray(data.items) ? data.items : [])
          } catch (err) {
            setFavoritesError(err.message)
          } finally {
            setFavoritesBusy(false)
          }
        }

        function toggleArchive() {
          const next = !archivedOpen
          setArchivedOpen(next)
          if (next) refreshArchived()
        }

        function applySettingsDraft(data) {
          // A partial payload must never half-apply: the dialog reads
          // settingsData.defaults right away.
          if (!data || !data.settings) return
          const settings = data.settings
          setSettingsData(data)
          setSettingsDraft({
            imageApiMode: settings.imageApiMode,
            imageBaseUrl: settings.imageBaseUrl,
            imageSyncBaseUrl: settings.imageSyncBaseUrl,
            llmBaseUrl: settings.llmBaseUrl,
            llmModel: settings.llmModel,
            outputDir: settings.outputDir || '',
            imageTimeoutMs: settings.imageTimeoutMs,
            // Absent means "on": the viewer explains the missing original from this.
            keepReferenceCopies: settings.keepReferenceCopies !== false,
          })
        }

        const loadSettings = React.useCallback(() => {
          return call('/settings')
            .then(applySettingsDraft)
            .catch((err) => setSettingsError('读不到设置：' + err.message))
        }, [])

        async function saveSettingsDraft() {
          setSettingsBusy(true)
          setSettingsError('')
          setSettingsTest(null)
          try {
            applySettingsDraft(await call('/settings', { method: 'POST', body: settingsDraft }))
            notify('模型服务设置已保存并生效')
            refreshCaps()
            refreshHistory()
          } catch (err) {
            setSettingsError(err.message)
          } finally {
            setSettingsBusy(false)
          }
        }

        async function testSettingsDraft() {
          setSettingsBusy(true)
          setSettingsError('')
          setSettingsTest(null)
          try {
            setSettingsTest(
              await call('/settings/test', {
                method: 'POST',
                body: {
                  imageApiMode: settingsDraft.imageApiMode,
                  imageBaseUrl: settingsDraft.imageBaseUrl,
                  imageSyncBaseUrl: settingsDraft.imageSyncBaseUrl,
                  llmBaseUrl: settingsDraft.llmBaseUrl,
                },
              }),
            )
          } catch (err) {
            setSettingsError(err.message)
          } finally {
            setSettingsBusy(false)
          }
        }

        async function resetSettingsDraft() {
          setSettingsBusy(true)
          setSettingsError('')
          setSettingsTest(null)
          try {
            applySettingsDraft(await call('/settings/reset', { method: 'POST', body: {} }))
            notify('已恢复默认地址')
            refreshCaps()
            refreshHistory()
          } catch (err) {
            setSettingsError(err.message)
          } finally {
            setSettingsBusy(false)
          }
        }

        async function setArchived(name, archived) {
          try {
            await call('/archive', { method: 'POST', body: { name, archived } })
            if (archived) {
              setResults((current) => current.filter((item) => item.name !== name))
              setViewer((current) => {
                if (!current) return current
                const list = current.list.filter((item) => item.name !== name)
                return list.length === 0 ? null : { list, index: Math.min(current.index, list.length - 1) }
              })
            }
            refreshHistory()
            if (archivedOpen) refreshArchived()
            notify(archived ? '已收进归档，主界面不再显示' : '已从归档移回主列表')
          } catch (err) {
            setError(err.message)
          }
        }

        const syncJobs = React.useCallback(() => {
          return call('/jobs')
            .then((data) => {
              setPollError('')
              setActiveJobs(Array.isArray(data.active) ? data.active : [])
              const finished = Array.isArray(data.recent) ? data.recent : []
              // Kept for the viewer (a job's refUrls) and for the 最近记录 list.
              setRecentJobs(finished)
              // Read again by the unmount hand-off, which must not see a stale list.
              recentRef.current = finished

              // On the first read, only remember what already happened: those
              // jobs belong to earlier visits and must not overwrite "本次结果".
              if (!primed.current) {
                primed.current = true
                for (const job of finished) consumed.current.add(job.id)
                return
              }

              const newlyDone = []
              let failure = ''
              let unretrieved = null
              for (const job of finished) {
                if (consumed.current.has(job.id)) continue
                consumed.current.add(job.id)
                if (job.status === 'done' && Array.isArray(job.items) && job.items.length > 0) {
                  newlyDone.push(job)
                } else if (job.status === 'unretrieved') {
                  // Not a failure and not silent either: the image is sitting on
                  // the server, and the user is the only one who can decide to go
                  // get it. Staying quiet here is what made it feel like a loss.
                  unretrieved = job
                } else if (job.status === 'failed') {
                  // A canceled job is not a failure — it is what the user asked
                  // for, and it stays quiet here.
                  failure = job.error || '这次没成功，换个描述再试一次'
                }
              }
              // `recent` is newest-first, so the freshest result stays on top.
              if (newlyDone.length > 0) {
                for (const job of newlyDone) announced.current.add(job.id)
                const items = newlyDone.reduce((acc, job) => acc.concat(job.items), [])
                setResults(items)
                if (toastTimer.current) clearTimeout(toastTimer.current)
                setToast({
                  id: Math.random().toString(36).slice(2), kind: 'info',
                  text: '做好了：' + items.length + ' 张，已存到本机',
                })
                toastTimer.current = setTimeout(() => setToast(null), 3000)
                const seedOf = items[0] && items[0].seed
                if (seedOf !== undefined && seedOf !== null) setSeedValue(String(seedOf))
                refreshHistory()
              }
              if (unretrieved) {
                announced.current.add(unretrieved.id)
                // A persistent toast: unlike a failure this one can still be
                // fixed, so it stays until dismissed.
                if (toastTimer.current) clearTimeout(toastTimer.current)
                setToast({
                  id: Math.random().toString(36).slice(2),
                  kind: 'info',
                  text: '有一条结果待取回：任务还在服务端，服务恢复后点「继续取回」',
                })
              }
              if (failure) {
                if (toastTimer.current) clearTimeout(toastTimer.current)
                setToast({ id: Math.random().toString(36).slice(2), kind: 'bad', text: failure })
              }
            })
            .catch((err) => {
              // Never leave the page stuck on a spinner: say what happened and
              // let the user retry, instead of pretending a job is still running.
              setPollError('读不到任务进度：' + err.message)
              setActiveJobs([])
            })
        }, [refreshHistory])

        React.useEffect(() => {
          refreshCaps()
          refreshHistory()
          syncJobs()
          loadFavorites()
          loadSettings()
          call('/presets')
            .then((data) => {
              setPresets(Array.isArray(data.presets) ? data.presets : [])
              setRoles(Array.isArray(data.roles) ? data.roles : [])
              setPresetsError('')
            })
            .catch((err) => setPresetsError('没能读到预设：' + err.message))
        }, [refreshCaps, refreshHistory, syncJobs, loadFavorites, loadSettings])

        React.useEffect(() => {
          const id = setInterval(() => {
            syncJobs()
          }, 2500)
          return () => clearInterval(id)
        }, [syncJobs])

        // ---- clipboard: remember whether the last click landed in this panel,
        // so Ctrl+V is taken over here and nowhere else.
        React.useEffect(() => {
          const onDown = (event) => {
            armedRef.current = Boolean(rootRef.current && rootRef.current.contains(event.target))
          }
          window.addEventListener('pointerdown', onDown, true)
          return () => window.removeEventListener('pointerdown', onDown, true)
        }, [])

        // ---- viewer: wheel zoom has to be a non-passive native listener, or
        // preventDefault() cannot stop the page from scrolling.
        React.useEffect(() => {
          const el = stageRef.current
          if (!el || !viewer) return undefined
          const onWheel = (event) => {
            if (viewerActual) return
            event.preventDefault()
            zoomBy(event.deltaY < 0 ? 1.12 : 1 / 1.12)
          }
          el.addEventListener('wheel', onWheel, { passive: false })
          return () => el.removeEventListener('wheel', onWheel)
        }, [viewer, viewerActual, viewerMode, compareLayout])

        // Arrows page (or nudge the divider in swipe mode); +/- zoom. Esc is left
        // to the native <dialog>, which already handles it.
        React.useEffect(() => {
          if (!viewer) return undefined
          const current = viewer.list[Math.min(viewer.index, viewer.list.length - 1)]
          const onKey = (event) => {
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
              const dir = event.key === 'ArrowRight' ? 1 : -1
              if (viewerMode === 'compare' && compareLayout === 'wipe' && refCountFor(current) > 0) {
                event.preventDefault()
                setCut((value) => Math.min(100, Math.max(0, value + dir * 4)))
              } else if (viewer.list.length > 1) {
                event.preventDefault()
                stepViewer(dir)
              }
              return
            }
            if (event.key === '+' || event.key === '=') {
              event.preventDefault()
              zoomBy(1.25)
            } else if (event.key === '-' || event.key === '_') {
              event.preventDefault()
              zoomBy(0.8)
            }
          }
          window.addEventListener('keydown', onKey)
          return () => window.removeEventListener('keydown', onKey)
        }, [viewer, viewerMode, compareLayout])

        const imageCaps = caps && caps.image ? caps.image : null
        const imageOk = imageCaps ? Boolean(imageCaps.ok) : caps ? false : undefined
        const llmOk = caps && caps.llm ? Boolean(caps.llm.ok) : caps ? false : undefined
        const maxEdge = (imageCaps && imageCaps.maxEdge) || 2048
        const multiple = (imageCaps && imageCaps.multipleOf) || 32
        const timeoutMs = (caps && caps.imageTimeoutMs) || 1800000
        // With the queue, the wait is a server-side task, so a long estimate is
        // no reason to refuse; only the old direct call has a per-request limit.
        const queueMode = Boolean(imageCaps && imageCaps.mode === 'queue')

        const firstRef = refs.length > 0 ? refs[0] : null
        const autoSize = React.useMemo(() => {
          if (!firstRef || !firstRef.w || !firstRef.h) return null
          const budget = (DENSITY.find((item) => item.id === density) || DENSITY[1]).area
          return fitSize(firstRef.w, firstRef.h, budget, multiple, 256, maxEdge)
        }, [firstRef, density, multiple, maxEdge])

        // In reference mode the output framing may follow the source photo; the
        // pixel budget stays a separate, recommended choice.
        const followSource = scenario !== 'text' && ratio === 'source'
        const ratioChoice = RATIOS.find((item) => item.id === ratio) || RATIOS[0]
        const tableSize = ratioChoice.pixels[density] || ratioChoice.pixels.normal
        const effectiveSize = customSize.trim()
          ? customSize.trim()
          : followSource && autoSize
            ? autoSize.text
            : tableSize
        const sizeCheck = React.useMemo(() => {
          const match = /^(\d+)\s*[x×*]\s*(\d+)$/i.exec(effectiveSize)
          if (!match) return { valid: false, msg: '请写成「宽x高」，例如 1024x1024' }
          const w = Number(match[1])
          const hgt = Number(match[2])
          const problems = []
          if (w % multiple !== 0 || hgt % multiple !== 0) problems.push('宽和高要是 ' + multiple + ' 的整数倍')
          if (w < 256 || hgt < 256) problems.push('宽和高不能小于 256')
          if (w > maxEdge || hgt > maxEdge) problems.push('宽和高不能大于 ' + maxEdge)
          const snapped = Math.min(maxEdge, Math.max(256, Math.round(w / multiple) * multiple))
          const snappedH = Math.min(maxEdge, Math.max(256, Math.round(hgt / multiple) * multiple))
          return {
            valid: problems.length === 0,
            msg: problems.join('；'),
            snapTo: problems.length ? snapped + 'x' + snappedH : '',
            pixels: w * hgt,
            minSide: Math.min(w, hgt),
          }
        }, [effectiveSize, multiple, maxEdge])

        const selectedPreset = presets.find((preset) => preset.id === presetId) || null
        const refLimit = scenario === 'retouch' ? 2 : MAX_COMBINE_REFS
        const refCount = scenario === 'text' ? 0 : refs.length
        const expected = estimateSeconds(effectiveSize, refCount, scenario === 'text' ? batch : 1)
        const tooSlow = !queueMode && expected * 1000 > timeoutMs * 0.95

        // One draft per scenario. `draftTarget` is where typing goes right now.
        const draftTarget = scenario === 'text' ? 'text' : scenario
        const textPrompt = drafts.text
        const editNote = drafts[draftTarget] || ''
        const densityChoice = DENSITY.find((item) => item.id === density) || DENSITY[1]

        /** Write into one scenario's draft; the other two are never touched. */
        function writeDraft(target, value) {
          setDrafts((current) => ({
            ...current,
            [target]: typeof value === 'function' ? value(current[target] || '') : value,
          }))
        }

        // One line, two facts, and the pixel size on the right — the full picker
        // only appears when this line is clicked.
        const sizeLine = [
          followSource ? SOURCE_RATIO.label : ratioChoice.label,
          densityChoice.label,
          scenario === 'text' ? batch + ' 张' : '',
        ].filter(Boolean).join(' · ')
        const sizeCap = prettySize(customSize.trim() || effectiveSize)

        const running = activeJobs.length
        const runningPercent = running > 0
          ? Math.round(activeJobs.reduce((sum, job) => sum + (Number(job.percent) || 0), 0) / running)
          : 0
        const taskLabel = running > 0 ? running + ' 个任务 · ' + runningPercent + '%' : '任务'
        const connTone = capsLoading ? '' : imageOk ? 'ok' : 'bad'
        const connLabel = capsLoading ? '检查中' : imageOk ? '已连接' : '未连接'
        const keepRefs = !(settingsData && settingsData.settings &&
          settingsData.settings.keepReferenceCopies === false)
        const jobsAnchor = React.useRef(null)

        function jumpToJobs() {
          const node = jobsAnchor.current
          if (node && node.scrollIntoView) node.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }

        // Ctrl+V is only claimed in the edit scenarios, and only when the
        // clipboard actually holds an image.
        React.useEffect(() => {
          if (scenario === 'text') return undefined
          window.addEventListener('paste', onPaste)
          return () => window.removeEventListener('paste', onPaste)
        }, [scenario, submitting, refLimit, refs])

        function appendToText(text) {
          writeDraft('text', (current) => {
            const base = (current || '').trim()
            return base ? base.replace(/\s+$/, '') + '，' + text : text
          })
        }

        function chooseScenario(id) {
          setScenario(id)
          setError('')
          notify('')
          setAssistOut(null)
          setPreviewText('')
          // Edits follow the source photo until the user says otherwise.
          if (id !== 'text' && !ratioTouched.current) setRatio('source')
          if (id === 'text' && ratio === 'source') setRatio('1:1')
          const limit = id === 'retouch' ? 2 : MAX_COMBINE_REFS
          setRefs((current) => {
            if (current.length <= limit) return current
            notify('这个方式最多用 ' + limit + ' 张图，超出的已先去掉，可以再重新挑')
            return current.slice(0, limit)
          })
        }

        function choosePreset(id) {
          const next = presetId === id ? '' : id
          setPresetId(next)
          setPreviewText('')
          const preset = presets.find((item) => item.id === next)
          if (preset && preset.optional && preset.optional.indexOf('background') >= 0 && refs.length >= 2) {
            setRefs((current) =>
              current.map((ref, index) =>
                index === 1 && ref.role !== 'background' ? { ...ref, role: 'background' } : ref,
              ),
            )
            notify('第 2 张图已设为「当作新背景」，可以在图片下面改')
          }
        }

        function pushRefs(accepted) {
          if (!accepted || accepted.length === 0) return
          setRefs((current) => current.concat(accepted).slice(0, refLimit))
          setPreviewText('')
        }

        async function onPickFiles(event) {
          const files = Array.from(event.target.files || [])
          if (event.target) event.target.value = ''
          if (files.length === 0) return
          const room = refLimit - refs.length
          if (room <= 0) {
            failToast('这个方式最多用 ' + refLimit + ' 张图，先删掉一张再加')
            return
          }
          const accepted = []
          const rejected = []
          let totalBytes = refs.reduce((sum, ref) => sum + (ref.size || 0), 0)
          try {
            for (const file of files.slice(0, room)) {
              if (!/^image\/(png|jpeg|webp)$/.test(file.type)) {
                rejected.push(file.name + '（不是常见图片格式）')
                continue
              }
              if (file.size > MAX_REF_BYTES) {
                rejected.push(file.name + '（超过 8 MB，先压缩一下）')
                continue
              }
              if (totalBytes + file.size > MAX_TOTAL_BYTES) {
                rejected.push(file.name + '（图片总量太大了）')
                continue
              }
              const dataUrl = await readAsDataUrl(file)
              totalBytes += file.size
              const dim = await readImageSize(dataUrl)
              accepted.push({
                id: rid(),
                name: file.name,
                size: file.size,
                dataUrl,
                w: dim.w,
                h: dim.h,
                role: refs.length + accepted.length === 0 ? 'subject' : 'scene',
              })
            }
            if (files.length > room) rejected.push('超出上限的 ' + (files.length - room) + ' 张')
          } catch (err) {
            failToast(err.message)
          }
          if (accepted.length > 0) {
            pushRefs(accepted)
            notify('已加入 ' + accepted.length + ' 张图')
          }
          if (rejected.length > 0) failToast('已跳过：' + rejected.join('、'))
        }

        /**
         * Pasted images take the exact same road as picked files: same limits,
         * same place in the list, same role menu, same numbering. The only
         * difference is that they are redrawn as PNG first, because a webpage
         * hands over webp/avif (often with transparency) and the service wants
         * one predictable format.
         */
        async function addPastedBlobs(blobs) {
          if (scenario === 'text' || submitting) return
          const room = refLimit - refs.length
          if (room <= 0) {
            failToast('这个方式最多用 ' + refLimit + ' 张图，先删掉一张再贴')
            return
          }
          const taken = blobs.slice(0, room)
          const dropped = blobs.length - taken.length
          const accepted = []
          const rejected = []
          let totalBytes = refs.reduce((sum, ref) => sum + (ref.size || 0), 0)
          for (let index = 0; index < taken.length; index++) {
            const blob = taken[index]
            const label = '第 ' + (index + 1) + ' 张'
            if (blob.size > MAX_REF_BYTES) {
              rejected.push(label + '（原图超过 8 MB，先压缩一下）')
              continue
            }
            if (totalBytes + blob.size > MAX_TOTAL_BYTES) {
              rejected.push(label + '（图片总量太大了）')
              continue
            }
            let png
            try {
              png = await bitmapToPng(blob, maxEdge)
            } catch {
              rejected.push(label + '（这个格式转不了，可以先存成 PNG 再选文件）')
              continue
            }
            let bytes = dataUrlBytes(png.dataUrl)
            // A JPEG that was small can become a huge PNG; shrink once instead
            // of letting the submit fail an hour later.
            if (bytes > MAX_REF_BYTES) {
              try {
                png = await bitmapToPng(blob, Math.round(maxEdge / 2))
                bytes = dataUrlBytes(png.dataUrl)
              } catch {
                /* keep the first attempt and let the check below speak */
              }
            }
            if (bytes > MAX_REF_BYTES) {
              rejected.push(label + '（转成 PNG 后有 ' + mb(bytes) + '，太大了）')
              continue
            }
            totalBytes += bytes
            accepted.push({
              id: rid(),
              name: '粘贴的图片 ' + (index + 1),
              size: bytes,
              dataUrl: png.dataUrl,
              w: png.w,
              h: png.h,
              role: refs.length + accepted.length === 0 ? 'subject' : 'scene',
            })
          }
          if (accepted.length > 0) pushRefs(accepted)
          const notes = []
          if (accepted.length > 0) notes.push('贴入 ' + accepted.length + ' 张图')
          if (dropped > 0) notes.push('超出的 ' + dropped + ' 张没进来（最多 ' + refLimit + ' 张）')
          if (rejected.length > 0) notes.push('跳过：' + rejected.join('、'))
          if (notes.length === 0) return
          if (accepted.length > 0 && rejected.length === 0 && dropped === 0) notify(notes.join('；'))
          else failToast(notes.join('；'))
        }

        /**
         * Ctrl+V. It must not steal a text paste: when the clipboard holds no
         * image we simply do nothing and the browser pastes the text normally.
         */
        function onPaste(event) {
          if (scenario === 'text' || submitting) return
          const root = rootRef.current
          const active = typeof document !== 'undefined' ? document.activeElement : null
          const inside = Boolean(
            root && (root.contains(event.target) || (active && root.contains(active))),
          )
          // Outside the panel we only listen right after a click in it, and only
          // when nothing specific has focus — pasting into the chat stays intact.
          const armed = inside || (armedRef.current && (!active || active === document.body))
          if (!armed) return
          const data = event.clipboardData
          if (!data) return
          const items = Array.from(data.items || [])
          const imageItems = items.filter((item) => item.kind === 'file' && /^image\//.test(item.type))
          if (imageItems.length === 0) {
            const text = data.getData ? data.getData('text/plain') || '' : ''
            if (!text && inside) failToast('剪贴板里没有图片，可以先截图或复制一张图再试')
            return
          }
          const blobs = []
          for (const item of imageItems) {
            const file = item.getAsFile()
            if (file) blobs.push(file)
          }
          if (blobs.length === 0) {
            failToast('剪贴板里的图片读不出来，可以改用「选择文件」')
            return
          }
          // Only now do we claim the paste; text never reaches this line.
          event.preventDefault()
          addPastedBlobs(blobs)
        }

        /** Button path: needs a one-off permission, so it can be refused. */
        async function pasteFromClipboard() {
          if (scenario === 'text' || submitting) return
          if (!navigator.clipboard || !navigator.clipboard.read) {
            setPasteHint('这个环境不让读剪贴板，请改用 Ctrl+V 或点「选择文件」。')
            failToast('读不了剪贴板，请改用 Ctrl+V 或点「选择文件」')
            return
          }
          let items
          try {
            items = await navigator.clipboard.read()
          } catch (err) {
            const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError')
            setPasteHint(
              denied
                ? '读剪贴板的权限被拒了，改用 Ctrl+V 直接粘贴，或点「选择文件」。'
                : '读不出剪贴板（' + ((err && err.message) || '原因不明') + '），改用 Ctrl+V 或「选择文件」。',
            )
            failToast('读剪贴板的权限被拒了，改用 Ctrl+V 直接粘贴，或点「选择文件」')
            return
          }
          setPasteHint('')
          const blobs = []
          for (const item of items) {
            const types = item.types || []
            const type = types.find((value) => /^image\//.test(value))
            if (!type) continue
            try {
              blobs.push(await item.getType(type))
            } catch {
              /* one unreadable item must not kill the whole paste */
            }
          }
          if (blobs.length === 0) {
            failToast('剪贴板里没有图片，可以先截图或复制一张图再试')
            return
          }
          await addPastedBlobs(blobs)
        }

        function setRefRole(id, role) {
          setRefs((current) => current.map((ref) => (ref.id === id ? { ...ref, role } : ref)))
          setPreviewText('')
        }

        function removeRef(id) {
          setRefs((current) => current.filter((ref) => ref.id !== id))
          setPreviewText('')
        }

        function moveRef(index, delta) {
          setRefs((current) => {
            const next = current.slice()
            const target = index + delta
            if (target < 0 || target >= next.length) return current
            const tmp = next[index]
            next[index] = next[target]
            next[target] = tmp
            return next
          })
          setPreviewText('')
        }

        function resolveSeed() {
          if (seedMode === 'random') return undefined
          const value = Number(seedValue)
          if (!Number.isFinite(value) || value < 0) return undefined
          return Math.trunc(value)
        }

        async function cancelJob(id) {
          if (cancelBusy) return
          setCancelBusy(id)
          try {
            // A started job answers 200 with canceled:false: the service cannot
            // stop the model, so only our waiting ends. The image is still saved.
            const res = await call('/jobs/cancel', { method: 'POST', body: { id } })
            notify(
              res && res.canceled === false
                ? res.reason || '已经开跑，停不下来；跑完会照常存进作品'
                : '已取消，这个任务不会再有结果',
            )
            syncJobs()
          } catch (err) {
            failToast(err.message)
          } finally {
            setCancelBusy('')
          }
        }

        /** Ask the service again about a task whose result stayed behind. */
        async function resumeJob(id) {
          if (cancelBusy) return
          setCancelBusy(id)
          try {
            await call('/jobs/resume', { method: 'POST', body: { id } })
            notify('正在继续取回，结果跑完就会存进作品')
            syncJobs()
          } catch (err) {
            failToast(err.message)
          } finally {
            setCancelBusy('')
          }
        }

        /** Send one already-built payload. Kept separate so 重试 can reuse it. */
        async function runSubmit(payload) {
          if (submitting || !payload) return
          setSubmitting(true)
          setError('')
          try {
            if (payload.kind === 'text') await call('/generate', { method: 'POST', body: payload.body })
            else await call('/edit', { method: 'POST', body: payload.body })
            notify('已排上队了，做好会显示在右边')
            setPreviewText('')
            syncJobs()
          } catch (err) {
            setError(err.message)
          } finally {
            setSubmitting(false)
          }
        }

        function submit() {
          if (submitting) return
          setError('')
          notify('')
          if (!sizeCheck.valid) {
            setError('画面尺寸不对：' + sizeCheck.msg + (sizeCheck.snapTo ? '，可以改成 ' + sizeCheck.snapTo : ''))
            return
          }
          if (tooSlow) {
            setError('这个组合等太久了，超过系统允许的单次时长。把图片减少一点或选小一号的尺寸。')
            return
          }
          const seed = resolveSeed()
          let payload = null
          if (scenario === 'text') {
            const prompt = textPrompt.trim()
            if (!prompt) {
              setError('先写一句你想画的画面')
              return
            }
            payload = { kind: 'text', body: { prompt, size: effectiveSize, n: batch, seed } }
          } else {
            if (refs.length === 0) {
              setError(scenario === 'retouch' ? '先选一张要修改的照片' : '先添加要合在一起的图片')
              return
            }
            if (!presetId && !editNote.trim()) {
              setError('先说想改什么（比如"背景换成海边日落"），或者选一个处理方式')
              return
            }
            if (verbatim && !editNote.trim()) {
              setError('开着「直接发我写的指令」时，这段就是全部内容，不能空着')
              return
            }
            payload = {
              kind: 'edit',
              body: {
                refs: refs.map((ref) => ({ role: ref.role, dataUrl: ref.dataUrl })),
                presetId: verbatim ? undefined : presetId || undefined,
                note: editNote.trim() || undefined,
                // Told explicitly rather than inferred from a missing presetId:
                // a caller that forgets to strip the preset must not silently
                // get the template anyway.
                verbatim: verbatim || undefined,
                size: effectiveSize,
                seed,
              },
            }
          }
          // Remembered so the failure toast can resubmit the same request.
          lastSubmit.current = payload
          runSubmit(payload)
        }

        function retryLast() {
          if (!lastSubmit.current) return
          notify('')
          runSubmit(lastSubmit.current)
        }

        async function showPreview() {
          if (previewBusy) return
          if (refs.length === 0) {
            setError('先加图片才能预览')
            return
          }
          setPreviewBusy(true)
          try {
            const data = await call('/preview', {
              method: 'POST',
              body: {
                refs: refs.map((ref) => ({ role: ref.role, dataUrl: 'x' })),
                presetId: verbatim ? undefined : presetId || undefined,
                note: editNote.trim() || undefined,
                verbatim: verbatim || undefined,
              },
            })
            setPreviewText(humanisePrompt(data.prompt))
          } catch (err) {
            setError(err.message)
          } finally {
            setPreviewBusy(false)
          }
        }

        async function runAssist() {
          if (assistBusy) return
          setAssistError('')
          setAssistOut(null)
          const isEdit = scenario !== 'text'
          const idea = isEdit ? editNote.trim() : textPrompt.trim()
          if (!idea && !selectedPreset) {
            setAssistError(isEdit ? '先写一句你想怎么改' : '先写一句你想画的画面')
            return
          }
          setAssistBusy(true)
          try {
            if (isEdit) {
              const data = await call('/prompt', {
                method: 'POST',
                body: {
                  mode: 'edit-instruction',
                  idea: idea || (selectedPreset ? selectedPreset.summary : ''),
                  presetId: presetId || undefined,
                  roles: refs.map((ref) => ref.role),
                },
              })
              // Never overwrite what the user typed; offer it for confirmation.
              setAssistOut({ kind: 'edit', text: humanisePrompt(data.instruction) })
            } else {
              const data = await call('/prompt', {
                method: 'POST',
                body: { mode: 'expand', idea, avoid: avoid.trim(), language: 'zh' },
              })
              setAssistOut({ kind: 'text', text: data.prompt })
            }
          } catch (err) {
            setAssistError(err.message)
          } finally {
            setAssistBusy(false)
          }
        }

        function applyAssistText(text) {
          if (!text) return
          writeDraft(draftTarget, text)
          notify('已写入')
        }

        function askDelete(item) {
          setConfirmDelete({
            name: item.name,
            refs: Array.isArray(item.refFiles) ? item.refFiles.filter(Boolean).length : 0,
          })
        }

        async function deleteItem(name) {
          try {
            await call('/delete', { method: 'POST', body: { name } })
            setResults((current) => current.filter((item) => item.name !== name))
            setViewer((current) => {
              if (!current) return current
              const list = current.list.filter((item) => item.name !== name)
              if (list.length === 0) return null
              return { list, index: Math.min(current.index, list.length - 1) }
            })
            notify('已删除')
            refreshHistory()
          } catch (err) {
            failToast('没删掉：' + err.message)
          }
        }

        function reuse(item) {
          if (item.presetId) setPresetId(item.presetId)
          // A result made in the direct-send mode has to come back in it, or its
          // wording would be wrapped in the template it was written without.
          setVerbatim(item.verbatim === true)
          if (item.size) {
            // Fill the same table choice back in when it came from the table;
            // anything else goes to the custom field so the size stays exact.
            let matched = false
            for (const choice of RATIOS) {
              for (const key of Object.keys(choice.pixels)) {
                if (choice.pixels[key] === String(item.size).toLowerCase()) {
                  ratioTouched.current = true
                  setRatio(choice.id)
                  setDensity(key)
                  setCustomSize('')
                  matched = true
                }
              }
            }
            if (!matched) setCustomSize(item.size)
          }
          if (item.seed !== undefined && item.seed !== null) {
            setSeedMode('fixed')
            setSeedValue(String(item.seed))
          }
          if (item.mode === 'image2image') {
            setScenario('retouch')
            if (!ratioTouched.current) setRatio('source')
            // Prefer the user's own wording: the stored prompt already carries
            // the preset instruction and would be wrapped again on resubmit.
            writeDraft('retouch', item.note || '')
            notify(
              item.note
                ? '已把当时的要求、尺寸和编号填回来了；照片需要你重新选一次'
                : '已把尺寸和编号填回来了；当时的要求是预设生成的，照片需要重新选',
            )
          } else {
            setScenario('text')
            setRatio('1:1')
            writeDraft('text', item.prompt || '')
            notify('已把当时的描述、尺寸和编号填回来，通常能出几乎一样的图')
          }
          setAdvanced(true)
        }

        async function useAsReference(item) {
          try {
            const res = await fetch(item.fileUrl)
            if (!res.ok) throw new Error('HTTP ' + res.status)
            const blob = await res.blob()
            const dataUrl = await readAsDataUrl(blob)
            const limit = scenario === 'text' ? MAX_COMBINE_REFS : refLimit
            setRefs((current) =>
              current
                .concat([{
                  id: rid(),
                  name: item.name,
                  size: blob.size,
                  dataUrl,
                  w: Number((/^(\d+)x(\d+)$/.exec(String(item.size || '')) || [])[1]) || 0,
                  h: Number((/^(\d+)x(\d+)$/.exec(String(item.size || '')) || [])[2]) || 0,
                  role: current.length === 0 ? 'subject' : 'scene',
                }])
                .slice(0, limit),
            )
            if (scenario === 'text') {
              setScenario('retouch')
              if (!ratioTouched.current) setRatio('source')
            }
            notify('已作为素材加入，可以继续在这张图上改')
          } catch (err) {
            failToast('加入失败：' + err.message)
          }
        }

        // ------------------------------------------------------------- render

        const head = h(
          'header',
          { className: 'mw-head' },
          h('div', { className: 'mw-title' }, t('title')),
          h(
            'div',
            { className: 'mw-headright' },
            h(Pill, {
              label: taskLabel,
              tone: running > 0 ? 'busy' : null,
              title: running > 0 ? '点一下跳到任务区' : '还没有在跑的任务，点一下看看最近记录',
              onClick: jumpToJobs,
            }),
            h(Pill, {
              label: connLabel,
              tone: connTone,
              title:
                (imageCaps && imageCaps.error) ||
                '出图服务：' + (capsLoading ? '检查中' : imageOk ? '已连接' : '未连接') +
                  '；写作助手：' + (capsLoading ? '检查中' : llmOk ? '已连接' : '未连接') +
                  '。点一下打开设置。',
              onClick: () => {
                setSettingsOpen(true)
                if (!settingsData) loadSettings()
              },
            }),
            h(
              'button',
              {
                type: 'button', className: 'mw-gear', title: '设置',
                'aria-label': '设置', onClick: () => {
                  setSettingsOpen(true)
                  if (!settingsData) loadSettings()
                },
              },
              '⚙',
            ),
          ),
        )

        const scenarioPicker = h(
          'div',
          { className: 'mw-scenarios', role: 'group', 'aria-label': '选一个方式' },
          SCENARIOS.map((item) =>
            h(
              'button',
              {
                key: item.id, type: 'button', role: 'button',
                className: 'mw-scenario' + (scenario === item.id ? ' on' : ''),
                'aria-pressed': scenario === item.id,
                onClick: () => chooseScenario(item.id),
              },
              h('span', { className: 'mw-sicon' }, item.icon),
              h(
                'span',
                null,
                h('div', { className: 'mw-stitle' }, item.title),
                h('div', { className: 'mw-ssum' }, item.summary),
              ),
              scenario === item.id
                ? h('span', { className: 'mw-tick', 'aria-hidden': true }, '✓')
                : null,
            ),
          ),
        )

        const fields = []

        if (capsError) fields.push(h('div', { key: 'ce', className: 'mw-err' }, capsError))
        if (presetsError && scenario !== 'text') {
          fields.push(h('div', { key: 'pe', className: 'mw-err' }, presetsError))
        }

        // ---- scenario ①
        if (scenario === 'text') {
          fields.push(
            h(
              'div',
              { key: 'p', className: 'mw-field' },
              h('div', { className: 'mw-label' }, '你想画什么'),
              h('textarea', {
                className: 'mw-ta', rows: 4, value: textPrompt, disabled: submitting,
                'aria-label': '你想画什么',
                placeholder: '用大白话就行，例如：一只戴墨镜的橘猫坐在窗台上晒太阳，下午的光很暖',
                onChange: (e) => writeDraft('text', e.target.value),
              }),
              h('div', { style: { height: 8 } }),
              h(
                'button',
                { type: 'button', className: 'mw-btn', style: { width: '100%' }, disabled: assistBusy, onClick: runAssist },
                assistBusy ? '正在帮你写…' : '写不出来？帮我写详细一点',
              ),
              assistError ? h('div', { className: 'mw-err', style: { marginTop: 8 } }, assistError) : null,
            ),
          )
          fields.push(
            h(
              'div',
              { key: 'chips', className: 'mw-field' },
              h('div', { className: 'mw-label' }, '加点风格（可选，点一下就加进去）'),
              h(Chips, { items: STYLE_CHIPS, onPick: appendToText }),
              h('div', { style: { height: 6 } }),
              h(Chips, { items: LIGHT_CHIPS, onPick: appendToText }),
              h('div', { style: { height: 6 } }),
              h(Chips, { items: SHOT_CHIPS, onPick: appendToText }),
            ),
          )
        }

        // ---- scenario ② / ③
        if (scenario !== 'text') {
          const isRetouch = scenario === 'retouch'
          fields.push(
            h(
              'div',
              { key: 'refs', className: 'mw-field' },
              h(
                'div',
                { className: 'mw-label' },
                isRetouch ? '要修改的照片' : '要合在一起的图片',
                h('span', { style: { fontWeight: 400 } }, '（' + refs.length + '/' + refLimit + '）'),
              ),
              refs.length === 0
                ? h(
                    'button',
                    { type: 'button', className: 'mw-btn dashed', onClick: () => fileRef.current && fileRef.current.click() },
                    isRetouch ? '点这里选一张照片' : '点这里选图片（可多选）',
                  )
                : h(
                    'div',
                    { className: 'mw-refs' },
                    refs.map((ref, index) =>
                      h(
                        'div',
                        { key: ref.id, className: 'mw-ref' },
                        h(
                          'div',
                          { className: 'thumb' },
                          h(Img, {
                            src: ref.dataUrl, alt: '第 ' + (index + 1) + ' 张参考图',
                            fallback: '第 ' + (index + 1) + ' 张读不出来',
                          }),
                          h('span', { className: 'mw-refno' }, '图' + (index + 1)),
                        ),
                        isRetouch
                          ? index === 0
                            ? h('div', { className: 'mw-hint', style: { textAlign: 'center' } }, '要修改的这张')
                            : h(
                                'select',
                                {
                                  className: 'mw-refrole', value: ref.role,
                                  title: '这张图用来做什么', 'aria-label': '第 ' + (index + 1) + ' 张用来做什么',
                                  onChange: (e) => setRefRole(ref.id, e.target.value),
                                },
                                EXTRA_ROLE_CHOICES.map((choice) =>
                                  h('option', { key: choice.id, value: choice.id }, choice.label),
                                ),
                              )
                          : h(
                              'select',
                              {
                                className: 'mw-refrole', value: ref.role,
                                title: '这张图用来做什么', 'aria-label': '第 ' + (index + 1) + ' 张用来做什么',
                                onChange: (e) => setRefRole(ref.id, e.target.value),
                              },
                              roles.map((role) =>
                                h('option', { key: role.id, value: role.id }, role.label),
                              ),
                            ),
                        h(
                          'div',
                          { className: 'mw-refops' },
                          h('button', {
                            type: 'button', className: 'mw-btn tiny', title: '往前挪一位',
                            'aria-label': '把第 ' + (index + 1) + ' 张往前挪一位',
                            onClick: () => moveRef(index, -1), disabled: index === 0,
                          }, '↑'),
                          h('button', {
                            type: 'button', className: 'mw-btn tiny', title: '往后挪一位',
                            'aria-label': '把第 ' + (index + 1) + ' 张往后挪一位',
                            onClick: () => moveRef(index, 1), disabled: index === refs.length - 1,
                          }, '↓'),
                          h('button', {
                            type: 'button', className: 'mw-btn tiny', title: '删掉这张',
                            'aria-label': '删掉第 ' + (index + 1) + ' 张',
                            onClick: () => removeRef(ref.id),
                          }, '×'),
                        ),
                      ),
                    ),
                    refs.length < refLimit
                      ? h('button', {
                          type: 'button', className: 'mw-btn mw-addref',
                          onClick: () => fileRef.current && fileRef.current.click(),
                          title: '再加一张', 'aria-label': '再加一张',
                        }, '+')
                      : null,
                  ),
              h('input', {
                ref: fileRef, type: 'file', accept: 'image/png,image/jpeg,image/webp',
                multiple: true, style: { display: 'none' }, onChange: onPickFiles,
                'aria-label': '选择参考图',
              }),
              // Pasting is a second door to the same list — same limits, same
              // roles, same numbering as a picked file.
              h(
                'div',
                { className: 'mw-refadd' },
                h('button', {
                  type: 'button', className: 'mw-btn', disabled: submitting,
                  onClick: () => fileRef.current && fileRef.current.click(),
                }, '选择文件'),
                h('button', {
                  type: 'button', className: 'mw-btn', disabled: submitting,
                  onClick: pasteFromClipboard,
                  title: '读一次剪贴板里的图片；拒绝授权就改用 Ctrl+V 或选文件',
                }, '从剪贴板粘贴'),
              ),
              pasteHint ? h('div', { className: 'mw-warn' }, pasteHint) : null,
              h('div', { className: 'mw-hint' }, '也可以直接按 Ctrl+V 把截图或网页上的图片贴进来。'),
              isRetouch
                ? h('div', { className: 'mw-hint' }, '第 1 张是要改的照片。要换背景或换衣服，可以再加第 2 张当参考，也可以在下面用文字描述。')
                : h('div', { className: 'mw-hint' }, '每张图下面选它「用来做什么」，模型会照这个分配角色，图 1、图 2 就是编号。'),
            ),
          )

          if (isRetouch && !verbatim) {
            fields.push(
              h(
                'div',
                { key: 'preset', className: 'mw-field' },
                h('div', { className: 'mw-label' }, '① 选一个最接近的处理方式（可跳过）'),
                h(
                  'div',
                  { className: 'mw-presets', role: 'group', 'aria-label': '处理方式' },
                  presets.map((preset) =>
                    h(
                      'button',
                      {
                        key: preset.id, type: 'button', title: preset.summary,
                        className: 'mw-preset' + (presetId === preset.id ? ' on' : ''),
                        'aria-pressed': presetId === preset.id,
                        onClick: () => choosePreset(preset.id),
                      },
                      preset.label,
                      presetId === preset.id ? h('span', { className: 'mw-tick', 'aria-hidden': true }, '✓') : null,
                    ),
                  ),
                ),
                selectedPreset
                  ? h(
                      'div',
                      { className: 'mw-note' },
                      h('div', null, h('b', null, selectedPreset.label), '：' + selectedPreset.summary),
                      Array.isArray(selectedPreset.changes) && selectedPreset.changes.length > 0
                        ? h('div', { style: { marginTop: 4 } }, '会改动：' + selectedPreset.changes.join('、'))
                        : null,
                      selectedPreset.caution
                        ? h('div', { style: { marginTop: 4 } }, selectedPreset.caution)
                        : null,
                      h(
                        'div',
                        { style: { marginTop: 4 } },
                        '其余部分会逐项写进「保持不变」清单——清单跟着这个预设走，不会自相矛盾。但这是提示词约束，不是蒙版重绘，脸型仍可能轻微变化。',
                      ),
                    )
                  : h('div', { className: 'mw-hint' }, '选了预设，系统会自动写好「只改这里、其余尽量不动」的说明。'),
              ),
            )
          }

          fields.push(
            h(
              'div',
              { key: 'note', className: 'mw-field' },
              h(
                'div',
                { className: 'mw-label' },
                verbatim
                  ? '写下完整指令（原样发给模型）'
                  : isRetouch
                    ? '② 再补充具体要求（可跳过）'
                    : '想怎么合（可跳过）',
              ),
              h(
                'label',
                { className: 'mw-switch' },
                h('input', {
                  type: 'checkbox', checked: verbatim, disabled: submitting,
                  onChange: (e) => {
                    setVerbatim(e.target.checked)
                    // The old preview describes the other mode; leaving it on
                    // screen would contradict "what you see is what is sent".
                    setPreviewText('')
                  },
                }),
                h('span', null, '直接发我写的完整指令，不套用模板'),
              ),
              verbatim
                ? h(
                    'div',
                    { className: 'mw-hint', style: { marginBottom: 6 } },
                    '预设、角色句式、「其余保持不变」清单都不会加进去，发出去的就是这一整段。'
                      + '用「图1」「图2」指代参考图，系统只会把它换成模型认的写法，不会替你补别的话。',
                  )
                : null,
              h('textarea', {
                className: 'mw-ta', rows: verbatim ? 6 : 3, value: editNote, disabled: submitting,
                'aria-label': verbatim ? '完整指令' : isRetouch ? '再补充具体要求' : '想怎么合',
                placeholder: verbatim
                  ? '例如：把图1里的人放到图2的沙滩上，保持原来的光线和肤色'
                  : isRetouch
                    ? '例如：背景换成海边日落，衣服换成米色针织衫'
                    : '例如：图1 的人放到图2 的沙滩上，穿图3 的衣服',
                onChange: (e) => writeDraft(draftTarget, e.target.value),
              }),
              h('div', { style: { height: 8 } }),
              h(
                'button',
                { type: 'button', className: 'mw-btn', style: { width: '100%' }, disabled: assistBusy, onClick: runAssist },
                assistBusy ? '正在写…' : '帮我写成精准的修改要求',
              ),
              assistError ? h('div', { className: 'mw-err', style: { marginTop: 8 } }, assistError) : null,
            ),
          )

          fields.push(
            h(
              'div',
              { key: 'pv', className: 'mw-field' },
              h(
                'button',
                { type: 'button', className: 'mw-disclosure', onClick: showPreview, disabled: previewBusy },
                previewBusy ? '正在生成…' : '看看我们会怎么告诉模型',
              ),
              previewText ? h('div', { className: 'mw-code' }, previewText) : null,
            ),
          )
        }

        function favoritesBlock(target) {
          const current = target === 'text' ? textPrompt : editNote
          return h(
            'div',
            { key: 'fav', className: 'mw-field' },
            h(
              'div',
              { className: 'mw-label' },
              '常用提示词',
              h('span', { style: { fontWeight: 400 } }, '（' + favorites.length + '/5）'),
            ),
            h(
              'button',
              {
                type: 'button',
                className: 'mw-btn',
                style: { width: '100%', marginBottom: favorites.length ? 8 : 0 },
                disabled: favoritesBusy || submitting || !current.trim() || favorites.length >= 5,
                title: current.trim() ? '把上面这句存起来，下次点一下就能填回来' : '先写一句再存',
                onClick: () => saveFavorite(target),
              },
              favorites.length >= 5 ? '已存满 5 条，先删一条' : '把上面这句存为常用',
            ),
            favoritesError ? h('div', { className: 'mw-err', style: { marginTop: 6 } }, favoritesError) : null,
            favorites.length === 0
              ? h('div', { className: 'mw-hint' }, '存下几句常写的，下次点一下填回来。')
              : h(
                  'div',
                  { className: 'mw-fav' },
                  favorites.map((item) =>
                    h(
                      'div',
                      { key: item.id, className: 'mw-favrow' },
                      h(
                        'button',
                        {
                          type: 'button',
                          className: 'mw-favtext',
                          title: '点一下填进上面的输入框',
                          onClick: () => {
                            writeDraft(target === 'text' ? 'text' : draftTarget, item.text)
                            notify('已填入常用提示词')
                          },
                        },
                        item.text,
                      ),
                      h(
                        'button',
                        {
                          type: 'button',
                          className: 'mw-btn tiny',
                          disabled: favoritesBusy,
                          title: '删掉这条',
                          onClick: () => removeFavorite(item.id),
                        },
                        '×',
                      ),
                    ),
                  ),
                ),
          )
        }

        fields.push(favoritesBlock(scenario === 'text' ? 'text' : 'edit'))

        // ---- 输出尺寸：默认只是一行摘要，点开才是弹层
        fields.push(
          h(
            'div',
            { key: 'size', className: 'mw-field' },
            h('div', { className: 'mw-label' }, '输出尺寸'),
            h(
              'button',
              {
                type: 'button', className: 'mw-sizeline', disabled: submitting,
                'aria-haspopup': 'dialog',
                title: '点一下选择比例、清晰度和张数',
                onClick: () => setSizeOpen(true),
              },
              h('span', { className: 'fill' }, sizeLine),
              h('span', { className: 'px' }, sizeCap + ' ›'),
            ),
            h('div', { className: 'mw-hint' }, '大约要等 ' + formatDuration(expected)),
            sizeCheck.valid ? null : h('div', { className: 'mw-warn' }, sizeCheck.msg),
            tooSlow
              ? h('div', { className: 'mw-warn' }, '这个组合超出单次允许时长，请减少图片数量或选低一档清晰度。')
              : null,
          ),
        )

        fields.push(
          h(
            'div',
            { key: 'adv', className: 'mw-field' },
            h(
              'button',
              {
                type: 'button', className: 'mw-disclosure', onClick: () => setAdvanced(!advanced),
                'aria-expanded': advanced,
              },
              advanced ? '收起更多设置' : '更多设置（一般不用动）',
            ),
            advanced
              ? h(
                  'div',
                  { style: { marginTop: 10 } },
                  customSize.trim()
                    ? h(
                        'div',
                        { className: 'mw-note' },
                        '现在用的是自定义尺寸 ' + prettySize(customSize.trim()) +
                          '。要改回固定档位，就到「输出尺寸」里重新选一次。',
                        h('div', { style: { marginTop: 6 } },
                          h('button', {
                            type: 'button', className: 'mw-btn tiny',
                            disabled: submitting, onClick: () => setCustomSize(''),
                          }, '改回固定档位'),
                        ),
                      )
                    : null,
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '重复编号（种子）'),
                    h(
                      'div',
                      { className: 'mw-row' },
                      h(
                        'select',
                        {
                          className: 'mw-sel', style: { flex: '0 0 104px' }, value: seedMode, disabled: submitting,
                          onChange: (e) => setSeedMode(e.target.value),
                        },
                        h('option', { value: 'random' }, '每次不同'),
                        h('option', { value: 'fixed' }, '固定不变'),
                      ),
                      h('input', {
                        className: 'mw-in', type: 'number', min: 0, value: seedValue,
                        disabled: submitting || seedMode === 'random', placeholder: '编号',
                        onChange: (e) => setSeedValue(e.target.value),
                      }),
                    ),
                    h(
                      'div',
                      { className: 'mw-hint' },
                      seedMode === 'fixed'
                        ? '固定后再配合同样的描述和尺寸，通常能出几乎一样的图。'
                        : '每张图都会记下自己的编号；想要一模一样时，用卡片上的「按这张的参数重做」。',
                    ),
                  ),
                  scenario === 'text'
                    ? h(
                        'div',
                        { className: 'mw-field' },
                        h('div', { className: 'mw-label' }, '描述里想避免的东西（可选）'),
                        h('input', {
                          className: 'mw-in', value: avoid, disabled: submitting,
                          placeholder: '例如：杂乱、多余的文字、路人',
                          onChange: (e) => setAvoid(e.target.value),
                        }),
                        h('div', { className: 'mw-hint' }, '这个模型不认「不要什么」，写作助手会把它换成正面说法。'),
                      )
                    : null,
                )
              : null,
          ),
        )

        function settingsDialog() {
          const draft = settingsDraft
          const setField = (patch) => setSettingsDraft((current) => ({ ...current, ...patch }))
          // Children of a closed <dialog> are still built, so everything below
          // has to survive settingsData being absent or partial.
          const ready = Boolean(settingsData && settingsData.settings && settingsData.defaults)
          const defaults = ready ? settingsData.defaults : {}
          return h(
            Modal,
            {
              open: settingsOpen,
              className: 'mw-dlg',
              labelledBy: 'glow-settings-title',
              onClose: () => setSettingsOpen(false),
            },
            h(
              'div',
              { className: 'mw-dlghead' },
              h('h1', { className: 'mw-dlgtitle', id: 'glow-settings-title' }, '设置'),
              h(
                'button',
                {
                  type: 'button', className: 'mw-btn tiny', style: { marginLeft: 'auto' },
                  onClick: () => setSettingsOpen(false),
                },
                '关闭',
              ),
            ),
            h(
              'div',
              { className: 'mw-dlgbody' },
              settingsError ? h('div', { className: 'mw-err' }, settingsError) : null,
              !ready
                ? h('div', { className: 'mw-hint' }, '正在读设置…')
                : h(
                    'div',
                    null,
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '出图方式'),
                    h(
                      'div',
                      { className: 'mw-chips' },
                      h(
                        'button',
                        {
                          type: 'button',
                          className: 'mw-chip' + (draft.imageApiMode !== 'sync' ? ' on' : ''),
                          disabled: settingsBusy,
                          onClick: () => setField({ imageApiMode: 'queue' }),
                        },
                        '后台排队（推荐）',
                      ),
                      h(
                        'button',
                        {
                          type: 'button',
                          className: 'mw-chip' + (draft.imageApiMode === 'sync' ? ' on' : ''),
                          disabled: settingsBusy,
                          onClick: () => setField({ imageApiMode: 'sync' }),
                        },
                        '直连出图（旧方式）',
                      ),
                    ),
                    h(
                      'div',
                      { className: 'mw-hint' },
                      draft.imageApiMode === 'sync'
                        ? '旧方式：一次请求一直等到出图，编辑大图容易被超时掐断。只在对新方式有疑问时临时切回来。'
                        : '提交后立刻返回，服务端排队慢慢做；页面关掉、DSH 重启都不会丢，回来还能拿到结果。',
                    ),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, draft.imageApiMode === 'sync' ? '出图服务地址（直连）' : '队列服务地址'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      // Each mode has its own address; the field edits the one in use.
                      value: draft.imageApiMode === 'sync' ? draft.imageSyncBaseUrl || '' : draft.imageBaseUrl || '',
                      placeholder: settingsData
                        ? draft.imageApiMode === 'sync'
                          ? defaults.imageSyncBaseUrl
                          : defaults.imageBaseUrl
                        : draft.imageApiMode === 'sync'
                          ? 'http://127.0.0.1:9741'
                          : 'http://127.0.0.1:9742',
                      onChange: (e) =>
                        setField(
                          draft.imageApiMode === 'sync'
                            ? { imageSyncBaseUrl: e.target.value }
                            : { imageBaseUrl: e.target.value },
                        ),
                    }),
                    h(
                      'div',
                      { className: 'mw-hint' },
                      draft.imageApiMode === 'sync'
                        ? '直连方式下填 9741（模型服务本身）。'
                        : '队列服务地址，通常和模型服务同一台机器、端口 9742。',
                    ),
                  ),
                  draft.imageApiMode === 'sync'
                    ? null
                    : h(
                        'div',
                        { className: 'mw-field' },
                        h('div', { className: 'mw-label' }, '模型服务地址（回滚用）'),
                        h('input', {
                          className: 'mw-in', disabled: settingsBusy,
                          value: draft.imageSyncBaseUrl || '',
                          placeholder: defaults.imageSyncBaseUrl || 'http://127.0.0.1:9741',
                          onChange: (e) => setField({ imageSyncBaseUrl: e.target.value }),
                        }),
                        h('div', { className: 'mw-hint' }, '原来的模型服务，切回直连时用这个地址，也用来读取它支持的尺寸上限。'),
                      ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '提示词服务地址'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      value: draft.llmBaseUrl || '',
                      placeholder: defaults.llmBaseUrl || 'http://127.0.0.1:9739',
                      onChange: (e) => setField({ llmBaseUrl: e.target.value }),
                    }),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '模型名'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      value: draft.llmModel || '',
                      onChange: (e) => setField({ llmModel: e.target.value }),
                    }),
                    h('div', { className: 'mw-hint' }, '该服务只接受 default_model，写别的名字会被当成仓库名而报错。'),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '直连出图时一次请求最多等多久（秒）'),
                    draft.imageApiMode === 'sync'
                      ? h('input', {
                          className: 'mw-in', type: 'number', min: 60, step: 60, disabled: settingsBusy,
                          value: Math.round((Number(draft.imageTimeoutMs) || 1800000) / 1000),
                          onChange: (e) => setField({ imageTimeoutMs: Number(e.target.value) * 1000 }),
                        })
                      : null,
                    h(
                      'div',
                      { className: 'mw-hint' },
                      draft.imageApiMode === 'sync'
                        ? '直连方式下这是一次请求的最长等待，出图时间超过它就会被切断。'
                        : '排队方式下这个值不起作用：任务在后台排队，没有总时长上限，只有结果保留天数。',
                    ),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '图片保存目录'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      value: draft.outputDir || '',
                      placeholder: '留空 = 用默认目录',
                      onChange: (e) => setField({ outputDir: e.target.value }),
                    }),
                    h('div', { className: 'mw-hint' }, '切换目录后画廊会跟着换，旧目录里的图和归档不会自动搬过来。'),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h(
                      'div',
                      { className: 'mw-label' },
                      h('input', {
                        type: 'checkbox', disabled: settingsBusy,
                        checked: draft.keepReferenceCopies !== false,
                        onChange: (e) => setField({ keepReferenceCopies: e.target.checked }),
                      }),
                      '保留参考图副本',
                    ),
                    h(
                      'div',
                      { className: 'mw-hint' },
                      '开着的时候，"这张是怎么改出来的"才能在图上看参考图；关掉能省空间，但旧图就再也对不上原图了。',
                    ),
                  ),
                  h(
                    'div',
                    { className: 'mw-row' },
                    h(
                      'button',
                      {
                        type: 'button', className: 'mw-btn', style: { flex: '1 1 auto' },
                        disabled: settingsBusy,
                        onClick: () => {
                          refreshCaps()
                          refreshHistory()
                          syncJobs()
                          notify('正在重新检查两个服务')
                        },
                      },
                      '重新检查服务',
                    ),
                  ),
                  settingsTest
                    ? h(
                        'div',
                        { className: 'mw-note', style: { marginTop: 8 } },
                        h(
                          'div',
                          null,
                          '出图服务：' + (settingsTest.image.ok ? '连上了' : '连不上 — ' + (settingsTest.image.error || '原因不明')),
                        ),
                        h('div', null, '提示词服务：' + (settingsTest.llm.ok ? '连上了' : '连不上')),
                      )
                    : null,
                  settingsData
                    ? h('div', { className: 'mw-hint', style: { marginTop: 8 } },
                        '设置存在 ' + ((settingsData.stored && settingsData.stored.path) || '本机') + '，不进仓库')
                    : null,
                  h(
                    'div',
                    { style: { marginTop: 8 } },
                    h(
                      'button',
                      { type: 'button', className: 'mw-disclosure', disabled: settingsBusy, onClick: resetSettingsDraft },
                      '恢复默认地址',
                    ),
                  ),
                ),
            ),
            h(
              'div',
              { className: 'mw-dlgfoot' },
              h(
                'button',
                {
                  type: 'button', className: 'mw-btn',
                  disabled: settingsBusy, onClick: testSettingsDraft,
                },
                settingsBusy ? '检测中…' : '测试连接',
              ),
              h(
                'button',
                {
                  type: 'button', className: 'mw-btn primary', style: { flex: '1 1 auto', width: 'auto' },
                  disabled: settingsBusy, onClick: saveSettingsDraft,
                },
                '保存并生效',
              ),
            ),
          )
        }

        const assistBox = assistOut && assistOut.text
          ? h(
              'div',
              { key: 'ao', className: 'mw-field' },
              h('div', { className: 'mw-label' }, '助手给的写法'),
              h('div', { className: 'mw-code', style: { marginTop: 0 } }, assistOut.text),
              h(
                'div',
                { style: { marginTop: 8, display: 'flex', gap: 6 } },
                h(
                  'button',
                  { type: 'button', className: 'mw-btn tiny', onClick: () => applyAssistText(assistOut.text) },
                  '用这条（会替换现在的文字）',
                ),
                h('button', { type: 'button', className: 'mw-btn tiny', onClick: () => setAssistOut(null) }, '不要'),
              ),
            )
          : null

        // ---- 任务区：进行中（进度 / 排队 / 取消）+ 最近记录（跨重启仍在）
        const activeBox = activeJobs.length
          ? h(
              'div',
              { key: 'active' },
              activeJobs.map((job) => {
                const queued = job.status === 'queued'
                const percent = Math.max(0, Math.min(100, Number(job.percent) || 0))
                const eta = Number(job.etaSeconds) || 0
                return h(
                  'div',
                  { key: job.id, className: 'mw-job' },
                  h('span', { className: 'mw-spin', 'aria-hidden': true }),
                  h(
                    'span',
                    { className: 'grow' },
                    h(
                      'span',
                      null,
                      queued
                        ? job.queuePosition > 1
                          ? '排队中 · 前面还有 ' + (job.queuePosition - 1) + ' 个'
                          : '排队中 · 下一个就轮到'
                        : '正在做' +
                          (percent > 0 ? ' · ' + percent + '%' : '') +
                          (eta > 0 ? ' · 大约还要 ' + formatDuration(eta) : '') +
                          // The wait was cancelled but the model keeps working.
                          (job.stoppedWaiting ? ' · 跑完照常存进作品' : ''),
                    ),
                    h(
                      'div',
                      { className: 'mw-jobnote' },
                      job.note || humanisePrompt(job.request && job.request.prompt).slice(0, 60),
                    ),
                    // A real <progress> so the bar is not just a coloured div.
                    h('progress', {
                      className: 'mw-progress', max: 100,
                      value: queued ? 0 : percent,
                      'aria-label': (queued ? '排队中' : '生成进度') + ' ' + (queued ? 0 : percent) + '%',
                    }),
                  ),
                  h(
                    'button',
                    {
                      type: 'button', className: 'mw-btn tiny', disabled: cancelBusy === job.id,
                      title: queued
                        ? '现在取消，这个任务不会开始'
                        : '已经开跑了：只能不在这儿等，图跑完会照常存进作品',
                      onClick: () => cancelJob(job.id),
                    },
                    cancelBusy === job.id ? '处理中…' : queued ? '取消' : '不等了',
                  ),
                )
              }),
            )
          : null

        const recentBox = recentJobs.length
          ? h(
              'div',
              { key: 'recent', className: 'mw-jobs' },
              // Records waiting to be claimed float to the top: there are only
              // twelve rows, and one that scrolls away is one the user cannot act
              // on. Order inside each group stays newest-first.
              recentJobs
                .slice()
                .sort((a, b) => {
                  const waitA = a.status === 'unretrieved' ? 0 : 1
                  const waitB = b.status === 'unretrieved' ? 0 : 1
                  if (waitA !== waitB) return waitA - waitB
                  return (b.finishedAt || b.createdAt || 0) - (a.finishedAt || a.createdAt || 0)
                })
                .slice(0, 12)
                .map((job) => {
                const status = jobStatusOf(job)
                const stamp = shortTime(job.finishedAt || job.createdAt)
                const reason = job.error || (job.status === 'canceled'
                  ? '还没轮到它就被取消了'
                  : job.status === 'unretrieved'
                    ? '任务还在服务端，只是暂时连不上；恢复后点「继续取回」'
                    : '')
                const items = Array.isArray(job.items) ? job.items : []
                const canResume = (job.resumable || job.status === 'unretrieved') && job.remoteId
                return h(
                  'div',
                  { key: job.id, className: 'mw-jobsrow' },
                  h('span', { className: 'mw-stat ' + status.tone }, status.word),
                  h('span', null, stamp),
                  h(
                    'span',
                    { className: 'grow', title: reason || job.note || '' },
                    reason || job.note || humanisePrompt(job.request && job.request.prompt).slice(0, 40),
                  ),
                  canResume
                    ? h(
                        'button',
                        {
                          type: 'button', className: 'mw-btn tiny', disabled: submitting,
                          title: '按任务号再去问一次服务端，把结果取回来',
                          onClick: () => resumeJob(job.id),
                        },
                        '继续取回',
                      )
                    : null,
                  items.length > 0
                    ? h(
                        'button',
                        {
                          type: 'button', className: 'mw-btn tiny',
                          title: '看看这次做出来的图',
                          onClick: () => {
                            const name = items[0] && (items[0].name || items[0].file || '')
                            const found = history.find((entry) => entry.name === name)
                            if (found) openViewer(history, history.indexOf(found))
                            else jumpToJobs()
                          },
                        },
                        '查看结果',
                      )
                    : null,
                )
              }),
            )
          : null

        const jobsBox = activeJobs.length || recentJobs.length
          ? h(
              'div',
              { key: 'jobs' },
              h(
                'div',
                { className: 'mw-sec' },
                '进行中',
                activeJobs.length > 0
                  ? h('span', { className: 'mw-hint', style: { fontWeight: 400 } }, activeJobs.length + ' 个')
                  : null,
              ),
              activeJobs.length === 0
                ? h('div', { className: 'mw-hint' }, '现在没有在跑的。')
                : activeBox,
              h(
                'div',
                { className: 'mw-sec' },
                '最近记录',
                h('span', { className: 'mw-hint', style: { fontWeight: 400 } }, '重启也还在'),
              ),
              recentJobs.length === 0
                ? h('div', { className: 'mw-hint' }, '还没有做完的任务。')
                : recentBox,
              h(
                'div',
                { className: 'mw-hint', style: { marginBottom: 4 } },
                '任务在后台跑，切到别的页面、刷新、最小化都不会中断；DSH 重启后也会接着等。',
              ),
            )
          : null

        function renderCard(item, list, index) {
          const refCountOf = Array.isArray(item.refFiles) ? item.refFiles.filter(Boolean).length : 0
          return h(
            'div',
            { key: item.name, className: 'mw-card' },
            h(
              'button',
              {
                type: 'button', className: 'mw-thumbbtn',
                title: '点一下放大查看',
                'aria-label': '放大查看这张图片',
                onClick: () => openViewer(list, index),
              },
              h(Img, {
                className: 'mw-thumbimg', src: item.fileUrl,
                alt: item.prompt || '生成的图片', loading: 'lazy',
                style: { aspectRatio: parseAspect(item.size) },
                fallback: '这张图打不开了',
              }),
            ),
            h(
              'div',
              { className: 'mw-cardbody' },
              h(
                'div',
                { className: 'mw-meta' },
                h('span', null, prettySize(item.size || '')),
                item.seconds ? h('span', null, formatDuration(item.seconds)) : null,
                item.note ? h('span', null, '你的要求：' + item.note.slice(0, 18)) : null,
              ),
              h(
                'div',
                { className: 'mw-cardact' },
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: '把当时的要求、尺寸和编号填回去',
                  onClick: () => reuse(item),
                }, '按这张的参数重做'),
                refCountOf > 0
                  ? h(
                      React.Fragment,
                      null,
                      // Two ways of comparing, both one click away: the layout is a
                      // choice of entry, not a sub-setting hiding behind 对比原图.
                      h('button', {
                        type: 'button', className: 'mw-btn tiny',
                        title: '原图和结果左右并排',
                        onClick: () => openCompare(list, index, 'side'),
                      }, '并排对比'),
                      h('button', {
                        type: 'button', className: 'mw-btn tiny',
                        title: '原图和结果叠在一起，拖分界线看差别',
                        onClick: () => openCompare(list, index, 'wipe'),
                      }, '滑动对比'),
                    )
                  : null,
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: '当成素材，继续在这张上改',
                  onClick: () => useAsReference(item),
                }, '继续改这张'),
                h('a', {
                  className: 'mw-btn tiny', href: item.fileUrl, download: item.name,
                }, '下载'),
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: item.archived ? '移回主列表' : '收进归档，主界面不再显示（不会删除）',
                  onClick: () => setArchived(item.name, !item.archived),
                }, item.archived ? '移出归档' : '归档'),
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: '先问一句再删',
                  onClick: () => askDelete(item),
                }, '删除'),
              ),
            ),
          )
        }

        const historyOnly = history.filter((entry) => !results.some((item) => item.name === entry.name))

        const resultsPane = h(
          'section',
          { className: 'mw-results' },
          pollError ? h('div', { className: 'mw-err' }, pollError + '（可以点右上角齿轮里的「重新检查服务」，或刷新页面）') : null,
          h('div', { ref: jobsAnchor }, jobsBox),
          h('div', { className: 'mw-sec' }, t('results.current')),
          results.length === 0
            ? h(
                'div',
                { className: 'mw-empty' },
                activeJobs.length > 0
                  ? '正在做，好了会自动出现在这里。'
                  : '还没有结果。左边选一个方式，再点最下面的按钮。',
              )
            : h('div', { className: 'mw-grid' }, results.map((item, index) => renderCard(item, results, index))),
          h(
            'div',
            { className: 'mw-sec' },
            '以前做的',
            h('span', { className: 'mw-hint', style: { fontWeight: 400 } }, '最近 ' + history.length + ' 张'),
          ),
          historyOnly.length === 0
            ? h('div', { className: 'mw-hint' }, '还没有历史记录。')
            : h('div', { className: 'mw-grid' }, historyOnly.map((item, index) => renderCard(item, historyOnly, index))),
          h(
            'div',
            { className: 'mw-sec' },
            h(
              'button',
              {
                type: 'button', className: 'mw-disclosure', onClick: toggleArchive,
                'aria-expanded': archivedOpen,
              },
              archivedOpen ? '收起归档箱' : '归档箱（' + archivedCount + '）',
            ),
            archivedCount > 0 && !archivedOpen
              ? h('span', { className: 'mw-hint', style: { fontWeight: 400 } }, '收进来的不占上面列表')
              : null,
          ),
          archivedOpen
            ? archivedBusy
              ? h('div', { className: 'mw-hint' }, '读取中…')
              : archivedError
                ? h('div', { className: 'mw-err' }, archivedError)
                : archivedItems.length === 0
                  ? h('div', { className: 'mw-hint' }, '归档箱是空的。点任意结果上的「归档」可以把它们收进来。')
                  : h(
                      'div',
                      { className: 'mw-grid' },
                      archivedItems.map((item, index) => renderCard(item, archivedItems, index)),
                    )
            : null,
          caps && caps.outputDir ? h('div', { className: 'mw-sec' }, '图片存在这里') : null,
          caps && caps.outputDir ? h('div', { className: 'mw-path mw-mono' }, caps.outputDir) : null,
        )

        const footer = h(
          'div',
          { className: 'mw-footer' },
          error ? h('div', { className: 'mw-err' }, error) : null,
          h(
            'button',
            {
              type: 'button', className: 'mw-btn primary',
              disabled: submitting || !sizeCheck.valid || tooSlow,
              onClick: submit,
            },
            submitting
              ? '提交中…'
              : scenario === 'text'
                ? '生成图片'
                : '开始修改',
          ),
          h(
            'div',
            { className: 'mw-hint', style: { textAlign: 'center', marginTop: 6 } },
            busy
              ? activeJobs.length + ' 个任务在跑，可以继续提交，会自动排队'
              : '大约要等 ' + formatDuration(expected),
          ),
        )

        // ---- 提示条：失败留在屏幕上等你看，提示 3 秒后自己走
        const toastNode = toast
          ? h(
              'div',
              { className: 'mw-toasts', role: 'status', 'aria-live': 'polite' },
              h(
                'div',
                { className: 'mw-toast' + (toast.kind === 'bad' ? ' bad' : '') },
                h('span', { className: 'grow' }, toast.text),
                toast.kind === 'bad' && lastSubmit.current
                  ? h('button', {
                      type: 'button', className: 'mw-btn tiny', disabled: submitting,
                      onClick: retryLast,
                    }, submitting ? '重试中…' : '重试')
                  : null,
                h(
                  'button',
                  {
                    type: 'button', className: 'mw-btn tiny', 'aria-label': '关掉这条提示',
                    onClick: () => notify(''),
                  },
                  '知道了',
                ),
              ),
            )
          : null

        const viewed = viewer && viewer.list.length > 0
          ? viewer.list[Math.min(viewer.index, viewer.list.length - 1)]
          : null

        // Reference images for the viewer: the result's own copies first, then
        // the task's copies, then a spoken explanation — never a hidden button.
        const viewedRefs = React.useMemo(() => {
          if (!viewed) return []
          const files = Array.isArray(viewed.refFiles) ? viewed.refFiles.filter(Boolean) : []
          if (files.length > 0) return files.map((name) => ({ key: name, src: fileUrl(name) }))
          const job = recentJobs.find((entry) => entry.id && entry.id === viewed.queueJobId)
          const urls = job && Array.isArray(job.refUrls) ? job.refUrls.filter(Boolean) : []
          return urls.map((url, index) => ({
            key: (viewed.name || '') + ':' + index,
            src: /^(https?:|data:|\/)/.test(url) ? url : fileUrl(url),
          }))
        }, [viewed, recentJobs])

        const canCompare = viewedRefs.length > 0
        // Three originals already fill the row; more would make each one tiny.
        const refShown = viewedRefs.slice(0, 3)

        /**
         * The wipe needs one rectangle both photos fill. Its shape comes from the
         * result, because that is the picture being judged: a retouch should be
         * seen whole, and a reference that does not share its framing loses its
         * edges instead of pushing the two out of line.
         *
         * The shape is taken from the decoded file whenever it has loaded. The
         * recorded `size` is only what we asked the service for, and a queue that
         * answers with a different shape would otherwise make us crop the result.
         */
        const [wipeNatural, setWipeNatural] = React.useState(null)
        React.useEffect(() => setWipeNatural(null), [viewed])

        const wipeAspect = React.useMemo(() => {
          if (wipeNatural && wipeNatural.w > 0 && wipeNatural.h > 0) {
            return wipeNatural.w / wipeNatural.h
          }
          const match = /^(\d+)\s*[x×]\s*(\d+)$/i.exec(String((viewed && viewed.size) || ''))
          if (!match) return null
          const w = Number(match[1])
          const h = Number(match[2])
          return w > 0 && h > 0 ? w / h : null
        }, [viewed, wipeNatural])

        const [wipeArea, setWipeArea] = React.useState({ w: 0, h: 0 })
        const fitRef = React.useRef(null)
        React.useEffect(() => {
          const el = fitRef.current
          if (!el) return undefined
          const measure = () => {
            // offsetWidth/Height, not getBoundingClientRect: the element carries
            // the zoom transform, and a transformed rect would make the box grow
            // again every time the user zooms in.
            const w = el.offsetWidth
            const h = el.offsetHeight
            setWipeArea((prev) =>
              Math.abs(prev.w - w) < 0.5 && Math.abs(prev.h - h) < 0.5 ? prev : { w, h },
            )
          }
          measure()
          // The dialog is shown by a child effect, so the first measurement can
          // land while it is still display:none. One more frame is enough.
          const raf = typeof requestAnimationFrame === 'function'
            ? requestAnimationFrame(measure)
            : 0
          if (typeof ResizeObserver !== 'function') {
            return () => {
              if (raf && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(raf)
            }
          }
          const observer = new ResizeObserver(measure)
          observer.observe(el)
          return () => {
            if (raf && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(raf)
            observer.disconnect()
          }
        }, [viewer, viewerMode, compareLayout, viewerActual])

        /** Fit the result's aspect into the measured area, never cropping it. */
        const wipeBox = React.useMemo(() => {
          const { w, h } = wipeArea
          if (!w || !h) return null
          if (!wipeAspect) return { width: w, height: h }
          const byWidth = { width: w, height: w / wipeAspect }
          return byWidth.height <= h ? byWidth : { width: h * wipeAspect, height: h }
        }, [wipeArea, wipeAspect])


        // Three different reasons, three different sentences.
        const noRefReason = !viewed
          ? ''
          : viewed.mode === 'text2image'
            ? '这是「写一句话出图」画的，本来就没有原图。'
            : !keepRefs
              ? '设置里的「保留参考图副本」关着，所以当时没存原图副本。'
              : '原图的副本已经不在了，可能被清理或删掉过。'

        function zoomBy(factor) {
          const next = Math.min(5, Math.max(1, Math.round(zoomRef.current * factor * 100) / 100))
          zoomRef.current = next
          setZoom(next)
          if (next <= 1) setPan({ x: 0, y: 0 })
        }

        const zoomStyle = {
          transform: 'translate(' + pan.x + 'px, ' + pan.y + 'px) scale(' + zoom + ')',
        }

        function onStagePointerDown(event) {
          if (event.button !== 0 || viewerActual) return
          const isWipe = viewerMode === 'compare' && compareLayout === 'wipe' && canCompare
          const rect = wipeRef.current ? wipeRef.current.getBoundingClientRect() : null
          const nearDivider = isWipe && rect
            ? Math.abs(event.clientX - (rect.left + (rect.width * cut) / 100)) <= 26
            : false
          let kind = 'pan'
          // WIPE: the divider wins on the line itself, and at 1× there is
          // nothing to pan, so the whole photo drags the divider instead.
          if (isWipe && (nearDivider || zoom <= 1)) kind = 'cut'
          else if (!isWipe && zoom <= 1) kind = 'swipe'
          if (kind === 'pan' && zoom <= 1) return
          dragRef.current = {
            kind, id: event.pointerId, startX: event.clientX, startY: event.clientY,
            pan, cut, rect,
          }
          setDragging(true)
          try {
            event.currentTarget.setPointerCapture(event.pointerId)
          } catch {
            /* pointer capture is a nicety, not a requirement */
          }
        }

        function onStagePointerMove(event) {
          const drag = dragRef.current
          if (!drag || drag.id !== event.pointerId) return
          if (drag.kind === 'cut') {
            const rect = drag.rect
            if (!rect || rect.width <= 0) return
            const percent = ((event.clientX - rect.left) / rect.width) * 100
            setCut(Math.min(100, Math.max(0, Math.round(percent))))
          } else if (drag.kind === 'pan') {
            setPan({
              x: drag.pan.x + (event.clientX - drag.startX),
              y: drag.pan.y + (event.clientY - drag.startY),
            })
          }
        }

        function onStagePointerUp(event) {
          const drag = dragRef.current
          dragRef.current = null
          setDragging(false)
          if (!drag || drag.id !== event.pointerId) return
          if (drag.kind === 'swipe' && viewer && viewer.list.length > 1) {
            const dx = event.clientX - drag.startX
            const dy = event.clientY - drag.startY
            if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy)) stepViewer(dx < 0 ? 1 : -1)
          }
        }

        const viewerNode =
          viewer && viewed
            ? h(
                Modal,
                {
                  open: true,
                  className: 'mw-viewer',
                  label: '查看图片',
                  initialFocus: closeViewerRef,
                  onClose: () => setViewer(null),
                },
                h(
                  'div',
                  { className: 'mw-viewerbody' },
                  viewerMode === 'compare' && canCompare
                    ? compareLayout === 'side'
                      ? h(
                          'div',
                          { className: 'mw-cmprow', ref: stageRef },
                          refShown.map((ref, index) =>
                            h(
                              'div',
                              { key: ref.key, className: 'mw-cmpcol' },
                              h(
                                'div',
                                {
                                  className: 'mw-stage',
                                  onPointerDown: onStagePointerDown,
                                  onPointerMove: onStagePointerMove,
                                  onPointerUp: onStagePointerUp,
                                  onPointerCancel: onStagePointerUp,
                                  onDoubleClick: resetZoom,
                                },
                                h(
                                  'div',
                                  { className: 'mw-zoom', style: zoomStyle },
                                  h(Img, {
                                    className: 'mw-viewerimg',
                                    src: ref.src,
                                    alt: '参考图 ' + (index + 1),
                                    fallback: '这张参考图读不出来',
                                  }),
                                ),
                              ),
                              h('div', { className: 'mw-cmpcap' }, '参考图 ' + (index + 1)),
                            ),
                          ),
                          h(
                            'div',
                            { className: 'mw-cmpcol' },
                            h(
                              'div',
                              {
                                className: 'mw-stage',
                                onPointerDown: onStagePointerDown,
                                onPointerMove: onStagePointerMove,
                                onPointerUp: onStagePointerUp,
                                onPointerCancel: onStagePointerUp,
                                onDoubleClick: resetZoom,
                              },
                              h(
                                'div',
                                { className: 'mw-zoom', style: zoomStyle },
                                h(Img, {
                                  className: 'mw-viewerimg',
                                  src: viewed.fileUrl,
                                  alt: '生成结果',
                                  fallback: '这张图打不开了',
                                }),
                              ),
                            ),
                            h('div', { className: 'mw-cmpcap' }, '生成结果'),
                          ),
                        )
                      : h(
                          'div',
                          {
                            className: 'mw-stage' + (dragging ? ' dragging' : ''),
                            ref: stageRef,
                            onPointerDown: onStagePointerDown,
                            onPointerMove: onStagePointerMove,
                            onPointerUp: onStagePointerUp,
                            onPointerCancel: onStagePointerUp,
                            onDoubleClick: resetZoom,
                          },
                          h(
                            'div',
                            { className: 'mw-zoom', ref: fitRef, style: zoomStyle },
                            h(
                              'div',
                              {
                                className: 'mw-wipebox',
                                ref: wipeRef,
                                style: wipeBox
                                  ? { width: wipeBox.width + 'px', height: wipeBox.height + 'px' }
                                  : undefined,
                              },
                              // Result underneath: it fills the rectangle exactly,
                              // so the picture being judged is never cropped.
                              h(Img, {
                                src: viewed.fileUrl, alt: '生成结果', fallback: '结果图没打开',
                                onLoad: (event) => {
                                  const el = event.currentTarget
                                  if (!el || !el.naturalWidth) return
                                  setWipeNatural((prev) =>
                                    prev && prev.w === el.naturalWidth && prev.h === el.naturalHeight
                                      ? prev
                                      : { w: el.naturalWidth, h: el.naturalHeight },
                                  )
                                },
                              }),
                              // Reference on top, revealing only the left part. Both
                              // layers are the same size and use the same fit, which
                              // is what keeps them on one scale.
                              h(
                                'span',
                                {
                                  className: 'mw-wipeover',
                                  style: { clipPath: 'inset(0 ' + (100 - cut) + '% 0 0)' },
                                },
                                h(Img, {
                                  src: viewedRefs[0].src, alt: '参考图', fallback: '原图读不出来了',
                                }),
                              ),
                              // The divider is drawn on the photo, not under it.
                              h('div', {
                                className: 'mw-divider',
                                style: { left: cut + '%' },
                                role: 'slider', tabIndex: 0,
                                'aria-label': '对比分界线，左右方向键微调',
                                'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-valuenow': cut,
                              }),
                            ),
                          ),
                        )
                    : h(
                        'div',
                        {
                          className: 'mw-stage' + (viewerActual ? ' actual' : '') +
                            (dragging ? ' dragging' : ''),
                          ref: stageRef,
                          onPointerDown: onStagePointerDown,
                          onPointerMove: onStagePointerMove,
                          onPointerUp: onStagePointerUp,
                          onPointerCancel: onStagePointerUp,
                          onDoubleClick: resetZoom,
                        },
                        h(
                          'div',
                          { className: 'mw-zoom', style: viewerActual ? undefined : zoomStyle },
                          h(Img, {
                            className: 'mw-viewerimg' + (viewerActual ? ' actual' : ''),
                            src: viewed.fileUrl,
                            alt: viewed.prompt || '图片',
                            fallback: '这张图打不开了，可以试试下载或刷新',
                          }),
                        ),
                      ),
                  viewerMode === 'compare' && canCompare && compareLayout === 'side'
                    ? null
                    : h(
                        'div',
                        { className: 'mw-captionrow' },
                        h('span', null, viewerMode === 'compare' && canCompare ? '原图（参考）' : noRefReason || '生成结果'),
                        h(
                          'span',
                          null,
                          viewerMode === 'compare' && canCompare ? '生成结果' : prettySize(viewed.size),
                        ),
                      ),
                ),
                h(
                  'div',
                  { className: 'mw-viewerbar' },
                  h(
                    'button',
                    {
                      type: 'button', className: 'mw-vbtn', ref: closeViewerRef,
                      onClick: () => setViewer(null),
                    },
                    '关闭',
                  ),
                  viewer.list.length > 1
                    ? h('button', {
                        type: 'button', className: 'mw-vbtn', onClick: () => stepViewer(-1),
                      }, '← 上一张')
                    : null,
                  viewer.list.length > 1
                    ? h('button', {
                        type: 'button', className: 'mw-vbtn', onClick: () => stepViewer(1),
                      }, '下一张 →')
                    : null,
                  canCompare
                    ? h(
                        'div',
                        { className: 'mw-zoomgroup mw-comparegroup', role: 'group', 'aria-label': '对比方式' },
                        // 并排与滑动并列在一级，不藏在「对比」的下一步里。
                        h('button', {
                          type: 'button',
                          className: 'mw-vbtn' + (viewerMode === 'compare' && compareLayout === 'side' ? ' on' : ''),
                          'aria-pressed': viewerMode === 'compare' && compareLayout === 'side',
                          title: '原图和结果左右并排',
                          onClick: () => {
                            setViewerMode('compare')
                            setCompareLayout('side')
                            resetZoom()
                          },
                        }, '并排对比'),
                        h('button', {
                          type: 'button',
                          className: 'mw-vbtn' + (viewerMode === 'compare' && compareLayout === 'wipe' ? ' on' : ''),
                          'aria-pressed': viewerMode === 'compare' && compareLayout === 'wipe',
                          title: '原图和结果叠在一起，拖分界线看差别',
                          onClick: () => {
                            setViewerMode('compare')
                            setCompareLayout('wipe')
                            resetZoom()
                          },
                        }, '滑动对比'),
                        h('button', {
                          type: 'button',
                          className: 'mw-vbtn' + (viewerMode === 'single' ? ' on' : ''),
                          'aria-pressed': viewerMode === 'single',
                          title: '只看生成结果',
                          onClick: () => {
                            setViewerMode('single')
                            resetZoom()
                          },
                        }, '只看结果'),
                      )
                    : null,
                  h(
                    'div',
                    { className: 'mw-zoomgroup', role: 'group', 'aria-label': '缩放' },
                    h('button', {
                      type: 'button', className: 'mw-vbtn', disabled: zoom <= 1 || viewerActual,
                      'aria-label': '缩小', title: '缩小（- 键）',
                      onClick: () => zoomBy(1 / 1.25),
                    }, '−'),
                    h('span', { className: 'mw-zoomval' }, Math.round(zoom * 100) + '%'),
                    h('button', {
                      type: 'button', className: 'mw-vbtn', disabled: zoom >= 5 || viewerActual,
                      'aria-label': '放大', title: '放大（+ 键）',
                      onClick: () => zoomBy(1.25),
                    }, '+'),
                  ),
                  // Everything else lives behind one menu instead of a third
                  // row of buttons. Newly shipped attributes are spread so the
                  // exact DOM names reach the element.
                  h('button', {
                    type: 'button', className: 'mw-vbtn',
                    'aria-label': '更多操作', title: '更多操作',
                    ...{ popovertarget: 'glow-viewer-menu', popovertargetaction: 'toggle' },
                  }, '⋯'),
                  h(
                    'div',
                    { className: 'mw-menu', id: 'glow-viewer-menu', ...{ popover: 'auto' } },
                    h('a', { href: viewed.fileUrl, download: viewed.name }, '下载'),
                    h('a', { href: viewed.fileUrl, target: '_blank', rel: 'noreferrer' }, '在新窗口打开'),
                    !canCompare || viewerMode === 'single'
                      ? h('button', {
                          type: 'button',
                          onClick: () => {
                            setViewerActual(!viewerActual)
                            resetZoom()
                          },
                        }, viewerActual ? '适应屏幕' : '按实际像素看')
                      : null,
                  ),
                  h(
                    'span',
                    { className: 'mw-viewerhint' },
                    '滚轮或 + / − 缩放 · 双击复位 · 拖动平移',
                  ),
                ),
              )
            : null

        // ---- 尺寸选择器：底部弹层，原生 <dialog>
        const sizeSheet = h(
          Modal,
          {
            open: sizeOpen,
            className: 'mw-sheet',
            labelledBy: 'glow-size-title',
            onClose: () => setSizeOpen(false),
          },
          h('div', { className: 'mw-sheetgrab', 'aria-hidden': true }),
          h(
            'div',
            { className: 'mw-dlghead' },
            h('h1', { className: 'mw-dlgtitle', id: 'glow-size-title' }, '输出尺寸'),
            h(
              'button',
              {
                type: 'button', className: 'mw-btn tiny', style: { marginLeft: 'auto' },
                onClick: () => setSizeOpen(false),
              },
              '完成',
            ),
          ),
          h(
            'div',
            { className: 'mw-dlgbody' },
            h(
              'div',
              { className: 'mw-field' },
              h('div', { className: 'mw-label' }, '比例'),
              h(ChoiceGroup, {
                label: '比例',
                items: scenario === 'text' ? RATIOS : [SOURCE_RATIO].concat(RATIOS),
                value: ratio,
                disabled: submitting,
                onPick: (id) => {
                  ratioTouched.current = true
                  setRatio(id)
                },
              }),
              followSource && !autoSize && firstRef === null
                ? h('div', { className: 'mw-hint' }, '先选一张照片，系统会按它的真实比例算出输出尺寸。')
                : null,
              followSource && autoSize
                ? h(
                    'div',
                    { className: 'mw-hint' },
                    '原图 ' + firstRef.w + '×' + firstRef.h + '（' + ratioLabel(firstRef.w, firstRef.h) +
                      '），输出 ' + autoSize.width + '×' + autoSize.height +
                      '（' + autoSize.ratio + '）',
                  )
                : null,
            ),
            h(
              'div',
              { className: 'mw-field' },
              h('div', { className: 'mw-label' }, '清晰度'),
              h(ChoiceGroup, {
                label: '清晰度', items: DENSITY, value: density,
                disabled: submitting, onPick: setDensity,
              }),
              h('div', { className: 'mw-hint' }, densityChoice.note +
                (followSource ? '。比例永远跟原图一致，这里只决定清晰度。' : '。')),
            ),
            scenario === 'text'
              ? h(
                  'div',
                  { className: 'mw-field' },
                  h('div', { className: 'mw-label' }, '一次出几张'),
                  h(ChoiceGroup, {
                    label: '一次出几张',
                    items: [{ id: '1', label: '1 张' }, { id: '2', label: '2 张（更慢，但可以挑一张）' }],
                    value: String(batch),
                    disabled: submitting,
                    onPick: (id) => setBatch(Number(id)),
                  }),
                )
              : null,
            h(
              'div',
              { className: 'mw-field' },
              h(
                'button',
                {
                  type: 'button', className: 'mw-disclosure',
                  onClick: () => setSizeAdvanced(!sizeAdvanced), 'aria-expanded': sizeAdvanced,
                },
                sizeAdvanced ? '收起高级' : '高级：自己填一个尺寸',
              ),
              sizeAdvanced
                ? h(
                    'div',
                    { style: { marginTop: 8 } },
                    h('input', {
                      className: 'mw-in', value: customSize, disabled: submitting,
                      placeholder: '留空就用上面选的，例如 1024x1536',
                      'aria-label': '自定义尺寸',
                      onChange: (e) => setCustomSize(e.target.value),
                    }),
                    h('div', { className: 'mw-hint' }, '宽和高要是 ' + multiple +
                      ' 的整数倍，落在 256–' + maxEdge + ' 之间。'),
                    customSize.trim()
                      ? h('div', { style: { marginTop: 6 } }, h('button', {
                          type: 'button', className: 'mw-btn tiny', onClick: () => setCustomSize(''),
                        }, '不要自定义了'))
                      : null,
                  )
                : null,
            ),
            h('div', { className: 'mw-hint' }, '这一档会是 ' + sizeCap +
              '，大约要等 ' + formatDuration(expected) + '。'),
          ),
          h(
            'div',
            { className: 'mw-dlgfoot' },
            h(
              'button',
              { type: 'button', className: 'mw-btn primary', onClick: () => setSizeOpen(false) },
              '完成',
            ),
          ),
        )

        // ---- 删除确认：说清会一起删掉什么，默认焦点在「不删了」
        const deleteDialog = h(
          Modal,
          {
            open: Boolean(confirmDelete),
            className: 'mw-dlg',
            labelledBy: 'glow-delete-title',
            initialFocus: deleteCancelRef,
            onClose: () => setConfirmDelete(null),
          },
          h(
            'div',
            { className: 'mw-dlghead' },
            h('h1', { className: 'mw-dlgtitle', id: 'glow-delete-title' }, '要删掉这张图吗'),
          ),
          h(
            'div',
            { className: 'mw-dlgbody' },
            h(
              'p',
              { style: { margin: '0 0 10px' } },
              confirmDelete
                ? '会删掉这张图和它的描述' +
                  (confirmDelete.refs > 0
                    ? '，以及 ' + confirmDelete.refs + ' 张参考图副本。'
                    : '。') +
                  '删了恢复不了。'
                : '',
            ),
          ),
          h(
            'div',
            { className: 'mw-dlgfoot' },
            h(
              'button',
              {
                type: 'button', className: 'mw-btn', ref: deleteCancelRef,
                onClick: () => setConfirmDelete(null),
              },
              '不删了',
            ),
            h(
              'button',
              {
                type: 'button', className: 'mw-btn danger',
                onClick: () => {
                  const target = confirmDelete
                  setConfirmDelete(null)
                  if (target) deleteItem(target.name)
                },
              },
              '删掉',
            ),
          ),
        )

        return h(
          'div',
          // `data-glow-root` + tabIndex -1 give the window-wide notice somewhere
          // to land focus after it opens this panel and removes itself.
          { className: 'mw-root', ref: rootRef, 'data-glow-root': '', tabIndex: -1 },
          h('style', null, CSS),
          head,
          h(
            'div',
            { className: 'mw-body' },
            h(
              'div',
              { className: 'mw-controls' },
              h(
                'div',
                { className: 'mw-fields' },
                h('div', { className: 'mw-step' }, '① 先选做什么'),
                scenarioPicker,
                h('div', { className: 'mw-step' }, scenario === 'text' ? '② 写一句描述' : '② 放图片、说怎么改'),
                fields.filter(Boolean),
                assistBox,
              ),
              footer,
            ),
            // 提示条贴在结果区底部，不压住左下角的「生成图片」大按钮
            h('div', { className: 'mw-right' }, resultsPane, toastNode),
          ),
          sizeSheet,
          deleteDialog,
          settingsDialog(),
          viewerNode,
        )
      }
    }

    // ------------------------------------------------------- frame-wide notices

    /**
     * Follows finished jobs for the frame-wide notice.
     *
     * The panel's own poller dies with the panel, which is exactly when someone
     * is most likely waiting elsewhere. This watcher polls **only while the panel
     * is closed**, so the two never run at the same time and one job is never
     * announced twice.
     */
    function createJobWatch() {
      const listeners = new Set()
      const seen = new Set()
      let notices = []
      let seq = 0
      let primed = false
      let panelOpen = 0
      let timer = null
      let busy = false

      function emit() {
        for (const listener of [...listeners]) listener()
      }

      function dismiss(id) {
        const next = notices.filter((notice) => notice.id !== id)
        if (next.length === notices.length) return
        notices = next
        emit()
      }

      function push(notice) {
        // The panel came back while this read was in flight: its own list says
        // the same thing in more detail, so drop it instead of covering the page.
        if (panelOpen > 0) return
        seq += 1
        // Three at most: a taller stack would cover the page it informs about.
        notices = notices.concat(Object.assign({ id: 'n' + seq }, notice)).slice(-3)
        emit()
      }

      /** What to say about one settled job, or null when it is not worth saying. */
      function noticeFor(job) {
        if (job.status === 'done' && Array.isArray(job.items) && job.items.length > 0) {
          return { kind: 'ok', count: job.items.length }
        }
        if (job.status === 'unretrieved') return { kind: 'unretrieved' }
        if (job.status === 'failed') return { kind: 'failed', detail: job.error || '' }
        // A cancel is what the user asked for; it stays quiet, like the panel.
        return null
      }

      async function poll() {
        if (busy || panelOpen > 0) return
        busy = true
        try {
          // A read that never returns would hold `busy` forever and silence the
          // watcher for the rest of the session; 10 s is far past a local answer.
          const data = await call('/jobs', { timeoutMs: 10000 })
          const finished = Array.isArray(data.recent) ? data.recent : []
          // No hand-off list (the panel never mounted): the first look only
          // catches up, since those jobs settled before anyone was watching.
          if (!primed) {
            primed = true
            for (const job of finished) seen.add(job.id)
            return
          }
          // The panel came back while this read was in flight. Return before
          // marking anything: a job marked as seen here would never be announced
          // by either side.
          if (panelOpen > 0) return
          const fresh = []
          for (const job of finished) {
            if (seen.has(job.id)) continue
            seen.add(job.id)
            const notice = noticeFor(job)
            if (notice) fresh.push(notice)
          }
          // Oldest first so the newest ends up at the bottom of the stack.
          for (const notice of fresh.reverse()) push(notice)
        } catch {
          // A watcher that cannot reach the plugin stays quiet: connection
          // trouble is the open panel's story to tell, and a notice about the
          // notifier itself would be one more thing to dismiss.
        } finally {
          busy = false
        }
      }

      return {
        subscribe(listener) {
          listeners.add(listener)
          return () => listeners.delete(listener)
        },
        snapshot() {
          return notices
        },
        dismiss,
        /**
         * @param open - true while the panel mounts, false as it unmounts.
         * @param handoff - what the panel leaves behind: `seen` are the ids it
         *   looked at, `owed` are jobs it saw but never got to tell the user
         *   about (its own round runs every 2.5 s). Without `owed`, a job that
         *   settled in that last slice would be announced by neither side.
         */
        setPanelOpen(open, handoff) {
          panelOpen = Math.max(0, panelOpen + (open ? 1 : -1))
          if (panelOpen > 0) {
            // The panel shows the same news in more detail; a leftover toast
            // pointing at what is now on screen is just something to dismiss.
            if (notices.length > 0) {
              notices = []
              emit()
            }
            return
          }
          if (handoff) {
            for (const id of handoff.seen || []) seen.add(id)
            primed = true
            for (const job of handoff.owed || []) {
              if (seen.has(job.id)) continue
              seen.add(job.id)
              const notice = noticeFor(job)
              if (notice) push(notice)
            }
          } else {
            primed = false
          }
          void poll()
        },
        start() {
          if (timer) return
          timer = setInterval(() => {
            if (panelOpen === 0) void poll()
          }, 4000)
        },
        stop() {
          if (timer) clearInterval(timer)
          timer = null
        },
      }
    }

    /** `{count}` in a dictionary string, filled without assuming an ICU format. */
    function fill(template, vars) {
      return String(template).replace(/\{(\w+)\}/g, (whole, key) =>
        Object.prototype.hasOwnProperty.call(vars, key) ? String(vars[key]) : whole,
      )
    }

    function createJobNotices(ctx, watch) {
      /**
       * Open the panel and put focus inside it.
       *
       * The button that asked for this disappears with its toast, so without a
       * landing spot a keyboard user would be dropped at the top of the document
       * with no idea where they ended up.
       */
      function showPanel() {
        try {
          ctx.layout.selectPanel(PANEL_ID)
        } catch {
          /* the panel is not registered during shutdown; nothing to open */
        }
        // The panel mounts on the next frames, not synchronously.
        let tries = 0
        const land = () => {
          const root = document.querySelector('[data-glow-root]')
          if (root && typeof root.focus === 'function') {
            root.focus()
            return
          }
          tries += 1
          if (tries < 10) requestAnimationFrame(land)
        }
        requestAnimationFrame(land)
      }

      /** Title and detail for a notice: a headline the eye catches, then the fact. */
      function noticeText(t, notice) {
        if (notice.kind === 'ok') {
          return [t('notice.doneTitle'), fill(t('notice.doneBody'), { count: notice.count })]
        }
        if (notice.kind === 'unretrieved') {
          return [t('notice.unretrievedTitle'), t('notice.unretrievedBody')]
        }
        if (notice.kind === 'failed') {
          const reason = String(notice.detail || '').trim().slice(0, 60)
          // The panel already refuses to show a bare colon; the toast must not
          // show a blank second line either when the reason came back empty.
          return [t('notice.failedTitle'), reason || t('notice.failedPlain')]
        }
        return ['', '']
      }

      /** What a screen reader hears: one sentence, not two boxes. */
      function noticeSpeech(t, notice) {
        const parts = noticeText(t, notice).filter(Boolean)
        return parts.join('，')
      }

      function Notice(props) {
        const { notice, t, onDismiss } = props
        const [held, setHeld] = React.useState(false)
        const dismiss = React.useCallback(() => onDismiss(notice.id), [onDismiss, notice.id])

        // Pausing while the pointer or the keyboard is on the toast: taking away
        // something being read or reached for is how toasts become hostile.
        React.useEffect(() => {
          if (held) return undefined
          const id = setTimeout(dismiss, 15000)
          return () => clearTimeout(id)
        }, [held, dismiss])

        const parts = noticeText(t, notice)

        return h(
          'div',
          {
            className: 'gl-toast ' + notice.kind,
            onFocus: () => setHeld(true),
            onBlur: () => setHeld(false),
            onMouseEnter: () => setHeld(true),
            onMouseLeave: () => setHeld(false),
          },
          h('span', { className: 'gl-toast-mark', 'aria-hidden': 'true' }),
          h(
            'div',
            { className: 'gl-toast-body' },
            h('span', { className: 'gl-toast-title' }, parts[0]),
            h('span', { className: 'gl-toast-text' }, parts[1]),
          ),
          h(
            'button',
            {
              type: 'button', className: 'gl-toast-open',
              onClick: () => {
                dismiss()
                showPanel()
              },
            },
            t('notice.view'),
          ),
          h(
            'button',
            {
              type: 'button', className: 'gl-toast-close', 'aria-label': t('notice.close'),
              onClick: dismiss,
            },
            '×',
          ),
        )
      }

      return function JobNotices(props) {
        const t = props.t
        const source = props.watch || watch
        const [notices, setNotices] = React.useState(source.snapshot())
        // Where focus was before the first toast appeared, so dismissing the last
        // one does not drop a keyboard user at the top of the document.
        const returnFocus = React.useRef(null)

        React.useEffect(() => source.subscribe(() => setNotices(source.snapshot())), [])

        React.useEffect(() => {
          if (notices.length === 0 || returnFocus.current) return
          returnFocus.current = document.activeElement
          return () => {
            returnFocus.current = null
          }
        }, [notices.length === 0])

        const onDismiss = React.useCallback((id) => {
          const wasLast = source.snapshot().length <= 1
          source.dismiss(id)
          // After the state settles: next toast, or back where the user was.
          requestAnimationFrame(() => {
            const next = document.querySelector('.gl-toast-close')
            if (next && typeof next.focus === 'function') {
              next.focus()
              return
            }
            if (!wasLast) return
            const back = returnFocus.current
            if (back && back !== document.body && typeof back.focus === 'function' && back.isConnected) back.focus()
          })
        }, [])

        const latest = notices.length > 0 ? notices[notices.length - 1] : null

        return h(
          'div',
          { className: 'gl-notices' },
          // Mounted from boot and only ever re-texted: a live region created at
          // the same moment as its content is a region screen readers miss. The
          // text, not the internal id, is what gets read out.
          h(
            'div',
            { className: 'gl-sr', role: 'status', 'aria-live': 'polite' },
            latest ? noticeSpeech(t, latest) : '',
          ),
          notices.length === 0
            ? null
            : h(
                'div',
                { className: 'gl-toasts', role: 'region', 'aria-label': t('notice.region') },
                notices.map((notice) =>
                  h(Notice, { key: notice.id, notice, t, onDismiss }),
                ),
              ),
        )
      }
    }

    // ------------------------------------------------------------------- apply

    return {
      inject: ['slots', 'layout', 'locale'],
      apply(ctx) {
        ctx.effect(
          () =>
            ctx.locale.register(NS, {
              zh: {
                title: '阁楼',
                'results.current': '本次结果',
                'panel.label': '阁楼',
                'notice.region': '阁楼任务通知',
                'notice.doneTitle': '做好了',
                'notice.doneBody': '{count} 张已存到本机',
                'notice.failedTitle': '这次没成功',
                'notice.failedPlain': '没有给出原因，换个描述再试一次',
                'notice.unretrievedTitle': '结果待取回',
                'notice.unretrievedBody': '任务还在服务端，连上后点「继续取回」',
                'notice.view': '查看',
                'notice.close': '关闭这条通知',
              },
            }),
          'glow: dictionaries',
        )

        const watch = createJobWatch()
        ctx.effect(() => {
          watch.start()
          return () => watch.stop()
        }, 'glow: job watch')

        // The notice outlives the panel, so its rules cannot ride on the panel's
        // own <style> element: they live in the document instead, for exactly as
        // long as this plugin is loaded.
        ctx.effect(() => {
          const style = document.createElement('style')
          style.setAttribute('data-glow-notices', '')
          style.textContent = NOTICE_CSS
          document.head.appendChild(style)
          return () => style.remove()
        }, 'glow: notice styles')

        const Workbench = createWorkbench(ctx, watch)
        const JobNotices = createJobNotices(ctx, watch)

        ctx.slots.inject('shell.overlay', () =>
          ctx.slots.register(
            {
              name: 'shell.overlay', id: 'glow.notices', order: 90, locale: NS,
              // The watcher goes in through the injection face so it can be
              // driven without a browser; the component reads it from props.
              inject: () => ({ watch }),
            },
            JobNotices,
          ),
        )

        ctx.slots.inject('sidebar.panellist', () =>
          ctx.slots.register(
            {
              name: 'sidebar.panellist',
              id: PANEL_ID,
              order: 30,
              label: () => ctx.locale.bind(NS)('panel.label'),
            },
            PanelIcon,
          ),
        )

        ctx.slots.inject('main', () =>
          ctx.slots.register({ name: 'main', key: PANEL_ID }, Workbench),
        )
      },
    }
  },
})
