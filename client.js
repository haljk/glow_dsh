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

    const SIZE_CHOICES = [
      { value: '1024x1024', label: '方形（推荐）', note: '1024×1024' },
      { value: '864x1152', label: '竖版人像', note: '864×1152' },
      { value: '1088x1920', label: '手机壁纸（竖）', note: '1088×1920' },
      { value: '1920x1088', label: '电脑壁纸（横）', note: '1920×1088' },
      { value: '1536x1024', label: '横版照片', note: '1536×1024' },
      { value: '2048x1152', label: '超清横版', note: '2048×1152' },
      { value: '512x512', label: '小图试手（快）', note: '512×512' },
    ]

    /**
     * Resolution budgets for reference mode. The model is trained near 1024²,
     * and an edit's waiting time is dominated by how many references it has to
     * read rather than by canvas size — so "推荐" keeps the trained-on pixel
     * budget while inheriting the source photo's aspect ratio.
     */
    const DENSITY = [
      { id: 'fast', label: '快速（省时间，细节少一些）', area: 640 * 640 },
      { id: 'normal', label: '推荐（约 1024² 的清晰度）', area: 1024 * 1024 },
      { id: 'sharp', label: '高清（更锐利，慢一些）', area: 1440 * 1440 },
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
      { id: 'detail', label: '只参考局部细节' },
    ]

    const MAX_REF_BYTES = 8 * 1024 * 1024
    const MAX_TOTAL_BYTES = 60 * 1024 * 1024
    // Five is what the single-generation time budget actually allows: each
    // extra reference adds roughly 150 s (measured), so six would cross it.
    const MAX_COMBINE_REFS = 5

    const SCENARIOS = [
      { id: 'text', icon: '✎', title: '写一句话出图', summary: '没有素材，只想画一张图' },
      { id: 'retouch', icon: '◐', title: '拿一张照片精修', summary: '换背景、换衣服、换发型，其余尽量不动' },
      { id: 'combine', icon: '❏', title: '多张图合在一起', summary: '把这张的人放进那张的景，并指定每张图做什么' },
    ]

    // ----------------------------------------------------------------- style

    const CSS = `
.mw-root { display: flex; flex-direction: column; height: 100%; min-height: 0;
  background: var(--dsw-alias-bg-base); color: var(--dsw-alias-label-primary);
  font-size: 13px; line-height: 1.55; }
.mw-head { display: flex; align-items: center; gap: 12px; padding: 13px 20px;
  border-bottom: 1px solid var(--dsw-alias-border-l1); flex: none; }
.mw-title { font-size: 15px; font-weight: 600; }
.mw-badges { display: flex; gap: 8px; margin-left: auto; align-items: center; flex-wrap: wrap; }
.mw-badge { display: inline-flex; align-items: center; gap: 5px; font-size: 12px;
  color: var(--dsw-alias-label-secondary); border: 1px solid var(--dsw-alias-border-l1);
  border-radius: 999px; padding: 3px 9px; white-space: nowrap; }
.mw-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--dsw-alias-state-idle-primary); }
.mw-dot.ok { background: var(--dsw-alias-state-success-primary); }
.mw-dot.busy { background: var(--dsw-alias-state-warn-primary); }
.mw-dot.bad { background: var(--dsw-alias-state-error-primary); }
.mw-body { display: flex; gap: 0; flex: 1 1 auto; min-height: 0; }
.mw-controls { width: 404px; flex: none; overflow-y: auto; display: flex;
  flex-direction: column; border-right: 1px solid var(--dsw-alias-border-l1); }
.mw-fields { flex: 1 1 auto; padding: 16px 20px 20px; }
.mw-footer { flex: none; padding: 10px 20px 16px; border-top: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-base); }
.mw-results { flex: 1 1 auto; overflow-y: auto; padding: 16px 20px 60px; min-width: 0; }

.mw-scenarios { display: grid; grid-template-columns: 1fr; gap: 8px; margin-bottom: 18px; }
.mw-scenario { display: flex; gap: 10px; align-items: flex-start; text-align: left;
  font: inherit; cursor: pointer; padding: 10px 12px; border-radius: 10px;
  border: 1px solid var(--dsw-alias-border-l1); background: var(--dsw-alias-bg-layer-1);
  color: var(--dsw-alias-label-primary); }
.mw-scenario:hover { border-color: var(--dsw-alias-border-l2); }
.mw-scenario.on { border-color: var(--dsw-alias-brand-primary); background: var(--dsw-alias-bg-layer-2); }
.mw-sicon { font-size: 16px; line-height: 1.2; opacity: .8; flex: none; }
.mw-stitle { font-weight: 600; font-size: 13px; }
.mw-ssum { font-size: 11px; color: var(--dsw-alias-label-secondary); }
.mw-step { display: flex; align-items: baseline; gap: 6px; font-size: 13px; font-weight: 600;
  margin: 20px 0 8px; }
.mw-step:first-child { margin-top: 0; }
.mw-field { margin-bottom: 14px; }
.mw-label { display: flex; align-items: center; gap: 6px; font-size: 12px;
  color: var(--dsw-alias-label-secondary); margin-bottom: 6px; font-weight: 500; }
.mw-hint { font-size: 11px; color: var(--dsw-alias-label-secondary); opacity: .9; margin-top: 4px; }
.mw-warn { font-size: 11px; color: var(--dsw-alias-state-warn-label); margin-top: 4px; }
.mw-err { color: var(--dsw-alias-state-error-primary); font-size: 12px;
  background: var(--dsw-alias-bg-layer-1); border: 1px solid var(--dsw-alias-state-error-primary);
  border-radius: 8px; padding: 8px 10px; margin-bottom: 12px; white-space: pre-wrap; }
.mw-in, .mw-ta, .mw-sel { width: 100%; box-sizing: border-box; font: inherit; font-size: 13px;
  color: var(--dsw-alias-label-primary); background: var(--dsw-alias-bg-layer-1);
  border: 1px solid var(--dsw-alias-border-l1); border-radius: 8px; padding: 8px 10px; }
.mw-ta { resize: vertical; min-height: 84px; line-height: 1.55; }
.mw-in:focus, .mw-ta:focus, .mw-sel:focus { outline: none; border-color: var(--dsw-alias-brand-primary); }
.mw-in:disabled, .mw-ta:disabled, .mw-sel:disabled, .mw-btn:disabled { opacity: .55; cursor: not-allowed; }
.mw-row { display: flex; gap: 8px; align-items: center; }
.mw-row > * { min-width: 0; }
.mw-btn { font: inherit; font-size: 13px; cursor: pointer; border-radius: 8px;
  padding: 7px 13px; border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); white-space: nowrap; }
.mw-btn:hover:not(:disabled) { border-color: var(--dsw-alias-border-l2); }
.mw-btn:disabled { opacity: .5; cursor: not-allowed; }
.mw-btn.primary { background: var(--dsw-alias-brand-primary); border-color: var(--dsw-alias-brand-primary);
  color: var(--dsw-alias-label-primary-foreground); font-weight: 600; width: 100%;
  padding: 11px; font-size: 14px; }
.mw-btn.tiny { padding: 3px 8px; font-size: 11px; border-radius: 6px; }
.mw-btn.dashed { border-style: dashed; width: 100%; padding: 16px; font-size: 13px;
  color: var(--dsw-alias-label-secondary); }
.mw-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.mw-chip { font: inherit; font-size: 11px; cursor: pointer; border-radius: 999px;
  padding: 3px 9px; border: 1px solid var(--dsw-alias-border-l1);
  background: transparent; color: var(--dsw-alias-label-secondary); }
.mw-chip:hover:not(:disabled) { color: var(--dsw-alias-label-primary); border-color: var(--dsw-alias-border-l2); }
.mw-presets { display: flex; flex-wrap: wrap; gap: 6px; }
.mw-preset { font: inherit; font-size: 12px; cursor: pointer; border-radius: 8px;
  padding: 6px 11px; border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }
.mw-preset:hover { border-color: var(--dsw-alias-border-l2); }
.mw-preset.on { border-color: var(--dsw-alias-brand-primary); font-weight: 600;
  background: var(--dsw-alias-bg-layer-2); }
.mw-note { border: 1px solid var(--dsw-alias-border-l1); border-radius: 9px;
  padding: 9px 11px; background: var(--dsw-alias-bg-layer-2); font-size: 12px;
  color: var(--dsw-alias-label-secondary); margin-top: 8px; }
.mw-note b { color: var(--dsw-alias-label-primary); }
.mw-refs { display: flex; flex-wrap: wrap; gap: 12px; }
.mw-ref { width: 132px; }
.mw-ref .thumb { position: relative; }
.mw-ref img { width: 132px; height: 132px; object-fit: cover; border-radius: 9px;
  border: 1px solid var(--dsw-alias-border-l1); display: block; }
.mw-refno { position: absolute; top: 4px; left: 4px; background: var(--dsw-alias-bg-overlay);
  color: var(--dsw-alias-label-primary); border: 1px solid var(--dsw-alias-border-l2);
  font-size: 11px; line-height: 1; padding: 3px 6px; border-radius: 5px; }
.mw-refops { display: flex; gap: 3px; margin-top: 4px; justify-content: center; }
.mw-refrole { width: 100%; margin-top: 4px; font-size: 11px; padding: 4px 5px;
  border-radius: 6px; border: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1); color: var(--dsw-alias-label-primary); }
.mw-addref { width: 132px; height: 132px; padding: 0; font-size: 24px; }
.mw-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 12px; }
.mw-card { border: 1px solid var(--dsw-alias-border-l1); border-radius: 10px; overflow: hidden;
  background: var(--dsw-alias-bg-layer-1); display: flex; flex-direction: column; }
.mw-thumbimg { width: 100%; display: block; background: var(--dsw-alias-bg-layer-2); }
.mw-cardbody { padding: 8px 10px 10px; display: flex; flex-direction: column; gap: 6px; }
.mw-meta { font-size: 11px; color: var(--dsw-alias-label-secondary);
  display: flex; flex-wrap: wrap; gap: 8px; }
.mw-cardact { display: flex; gap: 6px; flex-wrap: wrap; }
.mw-promptpreview { font-size: 11px; color: var(--dsw-alias-label-secondary);
  background: var(--dsw-alias-bg-layer-2); border-radius: 6px; padding: 5px 7px;
  max-height: 54px; overflow: hidden; word-break: break-word; }
.mw-sec { font-size: 12px; font-weight: 600; color: var(--dsw-alias-label-secondary);
  margin: 20px 0 10px; display: flex; align-items: center; gap: 8px; }
.mw-sec:first-child { margin-top: 0; }
.mw-empty { color: var(--dsw-alias-label-secondary); font-size: 13px; padding: 40px 20px;
  text-align: center; border: 1px dashed var(--dsw-alias-border-l1); border-radius: 10px; }
.mw-job { display: flex; align-items: center; gap: 10px; font-size: 12px;
  background: var(--dsw-alias-bg-layer-1); border: 1px solid var(--dsw-alias-border-l1);
  border-radius: 9px; padding: 9px 12px; margin-bottom: 8px; }
.mw-job .grow { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.mw-spin { width: 12px; height: 12px; border-radius: 50%; flex: none;
  border: 2px solid var(--dsw-alias-border-l2);
  border-top-color: var(--dsw-alias-brand-primary); animation: mw-spin .8s linear infinite; }
@keyframes mw-spin { to { transform: rotate(360deg); } }
.mw-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.mw-path { font-size: 11px; color: var(--dsw-alias-label-secondary); word-break: break-all; }
.mw-disclosure { font: inherit; font-size: 12px; cursor: pointer; background: none;
  border: none; padding: 0; color: var(--dsw-alias-label-secondary); text-decoration: underline; }
.mw-code { font-size: 11px; line-height: 1.6; white-space: pre-wrap; word-break: break-word;
  background: var(--dsw-alias-bg-layer-2); border-radius: 8px; padding: 8px 10px;
  margin-top: 6px; color: var(--dsw-alias-label-secondary); }
.mw-thumbbtn { display: block; width: 100%; padding: 0; margin: 0; border: none;
  background: none; cursor: zoom-in; }
.mw-viewer { position: fixed; inset: 0; z-index: 90; display: flex; flex-direction: column;
  background: var(--dsw-alias-bg-base); }
.mw-viewerhead { display: flex; align-items: center; gap: 8px; padding: 10px 16px;
  border-bottom: 1px solid var(--dsw-alias-border-l1); flex: none; flex-wrap: wrap; }
.mw-viewerbody { flex: 1 1 auto; min-height: 0; overflow: auto; display: flex;
  align-items: center; justify-content: center; padding: 16px;
  background: var(--dsw-alias-bg-layer-2); }
.mw-viewerimg { max-width: 100%; max-height: 100%; object-fit: contain; display: block;
  border-radius: 6px; }
.mw-viewerimg.actual { max-width: none; max-height: none; border-radius: 0; }
.mw-viewerfoot { flex: none; padding: 8px 16px 12px; border-top: 1px solid var(--dsw-alias-border-l1);
  font-size: 11px; color: var(--dsw-alias-label-secondary); display: flex; gap: 10px; flex-wrap: wrap; }
.mw-cmp { flex: 1 1 auto; min-height: 0; display: flex; gap: 12px; padding: 12px 16px;
  background: var(--dsw-alias-bg-layer-2); overflow: hidden; }
.mw-cmpPane { flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.mw-cmpPane .cap { flex: none; font-size: 11px; color: var(--dsw-alias-label-secondary);
  text-align: center; }
.mw-cmpPane img { flex: 1 1 auto; min-height: 0; width: 100%; object-fit: contain;
  border-radius: 6px; }
.mw-wipewrap { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column;
  background: var(--dsw-alias-bg-layer-2); }
.mw-wipe { position: relative; flex: 1 1 auto; min-height: 0; overflow: hidden; margin: 12px 16px 0; }
.mw-wipe img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.mw-wipeslider { flex: none; padding: 10px 16px 14px; }
.mw-range { width: 100%; accent-color: var(--dsw-alias-brand-primary); }
.mw-fav { display: flex; flex-direction: column; gap: 6px; }
.mw-favrow { display: flex; align-items: flex-start; gap: 6px; }
.mw-favtext { flex: 1 1 auto; font: inherit; font-size: 11px; line-height: 1.5; text-align: left;
  color: var(--dsw-alias-label-secondary); background: var(--dsw-alias-bg-layer-2);
  border: 1px solid transparent; border-radius: 6px; padding: 5px 7px; cursor: pointer;
  max-height: 46px; overflow: hidden; }
.mw-favtext:hover { border-color: var(--dsw-alias-border-l2); color: var(--dsw-alias-label-primary); }
@media (max-width: 900px) {
  .mw-body { flex-direction: column; overflow-y: auto; }
  .mw-controls { width: 100%; border-right: none; }
  .mw-results { overflow-y: visible; }
}
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
              onClick: () => props.onPick(item),
            },
            item,
          ),
        ),
      )
    }

    function ServiceBadge(props) {
      const state = props.ok === true ? 'ok' : props.ok === false ? 'bad' : ''
      return h(
        'span',
        { className: 'mw-badge', title: props.title || '' },
        h('span', { className: 'mw-dot ' + (props.tone || state) }),
        props.label,
      )
    }

    function useT(ctx) {
      const translate = ctx.locale.bind(NS)
      const [, force] = React.useReducer((x) => x + 1, 0)
      React.useEffect(() => ctx.locale.subscribe(force), [ctx])
      return translate
    }

    // ---------------------------------------------------------------- workbench

    function createWorkbench(ctx) {
      return function Workbench() {
        const t = useT(ctx)

        const [scenario, setScenario] = React.useState('text')
        const [caps, setCaps] = React.useState(null)
        const [capsLoading, setCapsLoading] = React.useState(true)
        const [capsError, setCapsError] = React.useState('')
        const [presets, setPresets] = React.useState([])
        const [presetsError, setPresetsError] = React.useState('')
        const [roles, setRoles] = React.useState([])

        const [textPrompt, setTextPrompt] = React.useState('')
        const [editNote, setEditNote] = React.useState('')
        const [refs, setRefs] = React.useState([])
        const [presetId, setPresetId] = React.useState('')
        const [avoid, setAvoid] = React.useState('')

        const [size, setSize] = React.useState('1024x1024')
        const [customSize, setCustomSize] = React.useState('')
        // Reference mode follows the source photo's framing by default.
        const [sizeMode, setSizeMode] = React.useState('auto')
        const [density, setDensity] = React.useState('normal')
        const [batch, setBatch] = React.useState(1)
        const [seedMode, setSeedMode] = React.useState('random')
        const [seedValue, setSeedValue] = React.useState('')
        const [advanced, setAdvanced] = React.useState(false)

        const [activeJobs, setActiveJobs] = React.useState([])
        const [results, setResults] = React.useState([])
        const [history, setHistory] = React.useState([])
        const [error, setError] = React.useState('')
        const [notice, setNotice] = React.useState('')
        const [pollError, setPollError] = React.useState('')
        const [previewText, setPreviewText] = React.useState('')
        const [previewBusy, setPreviewBusy] = React.useState(false)
        const [submitting, setSubmitting] = React.useState(false)

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

        function openViewer(list, index) {
          const item = list[index]
          const hasRefs = item && Array.isArray(item.refFiles) && item.refFiles.length > 0
          setViewer({ list, index })
          setViewerActual(false)
          // An edited photo is almost always checked against its source, so
          // open straight into the comparison and let the user switch back.
          setViewerMode(hasRefs ? 'compare' : 'single')
          setCut(50)
        }

        function stepViewer(delta) {
          setViewer((current) => {
            if (!current || current.list.length === 0) return current
            const next = (current.index + delta + current.list.length) % current.list.length
            return { list: current.list, index: next }
          })
        }

        React.useEffect(() => {
          if (!viewer) return undefined
          const onKey = (event) => {
            if (event.key === 'Escape') {
              event.preventDefault()
              setViewer(null)
            } else if (event.key === 'ArrowLeft') {
              event.preventDefault()
              stepViewer(-1)
            } else if (event.key === 'ArrowRight') {
              event.preventDefault()
              stepViewer(1)
            }
          }
          window.addEventListener('keydown', onKey)
          return () => window.removeEventListener('keydown', onKey)
        }, [viewer])

        const fileRef = React.useRef(null)
        const consumed = React.useRef(new Set())
        const primed = React.useRef(false)

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
            setNotice(data.duplicate ? '这条已经在常用里了' : '已存为常用提示词')
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
          setSettingsData(data)
          setSettingsDraft({
            imageBaseUrl: data.settings.imageBaseUrl,
            llmBaseUrl: data.settings.llmBaseUrl,
            llmModel: data.settings.llmModel,
            outputDir: data.settings.outputDir || '',
            imageTimeoutMs: data.settings.imageTimeoutMs,
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
            setNotice('模型服务设置已保存并生效')
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
                body: { imageBaseUrl: settingsDraft.imageBaseUrl, llmBaseUrl: settingsDraft.llmBaseUrl },
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
            setNotice('已恢复默认地址')
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
            setNotice(archived ? '已收进归档，主界面不再显示' : '已从归档移回主列表')
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

              // On the first read, only remember what already happened: those
              // jobs belong to earlier visits and must not overwrite "本次结果".
              if (!primed.current) {
                primed.current = true
                for (const job of finished) consumed.current.add(job.id)
                return
              }

              const newlyDone = []
              let failure = ''
              for (const job of finished) {
                if (consumed.current.has(job.id)) continue
                consumed.current.add(job.id)
                if (job.status === 'done' && Array.isArray(job.items) && job.items.length > 0) {
                  newlyDone.push(job)
                } else if (job.status === 'failed') {
                  failure = job.error || '这次没成功，换个描述再试一次'
                }
              }
              // `recent` is newest-first, so the freshest result stays on top.
              if (newlyDone.length > 0) {
                const items = newlyDone.reduce((acc, job) => acc.concat(job.items), [])
                setResults(items)
                setNotice('做好了：' + items.length + ' 张，已存到本机')
                const seedOf = items[0] && items[0].seed
                if (seedOf !== undefined && seedOf !== null) setSeedValue(String(seedOf))
                refreshHistory()
              }
              if (failure) setError(failure)
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

        const imageCaps = caps && caps.image ? caps.image : null
        const imageOk = imageCaps ? Boolean(imageCaps.ok) : caps ? false : undefined
        const llmOk = caps && caps.llm ? Boolean(caps.llm.ok) : caps ? false : undefined
        const maxEdge = (imageCaps && imageCaps.maxEdge) || 2048
        const multiple = (imageCaps && imageCaps.multipleOf) || 32
        const timeoutMs = (caps && caps.imageTimeoutMs) || 900000

        const firstRef = refs.length > 0 ? refs[0] : null
        const autoSize = React.useMemo(() => {
          if (!firstRef || !firstRef.w || !firstRef.h) return null
          const budget = (DENSITY.find((item) => item.id === density) || DENSITY[1]).area
          return fitSize(firstRef.w, firstRef.h, budget, multiple, 256, maxEdge)
        }, [firstRef, density, multiple, maxEdge])

        // In reference mode the output framing should match the source photo;
        // the pixel budget is a separate, recommended choice.
        const followSource = scenario !== 'text' && sizeMode === 'auto'
        const effectiveSize = followSource && autoSize ? autoSize.text : customSize.trim() ? customSize.trim() : size
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
        const tooSlow = expected * 1000 > timeoutMs * 0.95

        function appendToText(text) {
          setTextPrompt((current) => {
            const base = current.trim()
            return base ? base.replace(/\s+$/, '') + '，' + text : text
          })
        }

        function chooseScenario(id) {
          setScenario(id)
          setError('')
          setNotice('')
          setAssistOut(null)
          setPreviewText('')
          const limit = id === 'retouch' ? 2 : MAX_COMBINE_REFS
          setRefs((current) => {
            if (current.length <= limit) return current
            setNotice('这个方式最多用 ' + limit + ' 张图，超出的已先去掉，可以再重新挑')
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
            setNotice('第 2 张图已设为「当作新背景」，可以在图片下面改')
          }
        }

        async function onPickFiles(event) {
          const files = Array.from(event.target.files || [])
          if (event.target) event.target.value = ''
          if (files.length === 0) return
          const room = refLimit - refs.length
          if (room <= 0) {
            setError('这个方式最多用 ' + refLimit + ' 张图')
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
              const dataUrl = await new Promise((resolve, reject) => {
                const reader = new FileReader()
                reader.onload = () => resolve(String(reader.result))
                reader.onerror = () => reject(new Error('读不了 ' + file.name))
                reader.readAsDataURL(file)
              })
              totalBytes += file.size
              const dim = await readImageSize(dataUrl)
              accepted.push({
                id: Math.random().toString(36).slice(2),
                name: file.name,
                size: file.size,
                dataUrl,
                w: dim.w,
                h: dim.h,
                role: refs.length + accepted.length === 0 ? 'subject' : 'detail',
              })
            }
            if (files.length > room) rejected.push('超出上限的 ' + (files.length - room) + ' 张')
          } catch (err) {
            setError(err.message)
          }
          if (accepted.length > 0) {
            setRefs((current) => current.concat(accepted).slice(0, refLimit))
            setNotice('已加入 ' + accepted.length + ' 张图')
            setPreviewText('')
          }
          if (rejected.length > 0) setError('已跳过：' + rejected.join('、'))
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

        async function submit() {
          if (submitting) return
          setError('')
          setNotice('')
          if (!sizeCheck.valid) {
            setError('画面尺寸不对：' + sizeCheck.msg + (sizeCheck.snapTo ? '，可以改成 ' + sizeCheck.snapTo : ''))
            return
          }
          if (tooSlow) {
            setError('这个组合等太久了，超过系统允许的单次时长。把图片减少一点或选小一号的尺寸。')
            return
          }
          const seed = resolveSeed()
          setSubmitting(true)
          try {
            if (scenario === 'text') {
              const prompt = textPrompt.trim()
              if (!prompt) {
                setError('先写一句你想画的画面')
                return
              }
              await call('/generate', {
                method: 'POST',
                body: { prompt, size: effectiveSize, n: batch, seed },
              })
              setNotice('已排上队了，做好会显示在右边')
            } else {
              if (refs.length === 0) {
                setError(scenario === 'retouch' ? '先选一张要修改的照片' : '先添加要合在一起的图片')
                return
              }
              if (!presetId && !editNote.trim()) {
                setError('先说想改什么（比如"背景换成海边日落"），或者选一个处理方式')
                return
              }
              await call('/edit', {
                method: 'POST',
                body: {
                  refs: refs.map((ref) => ({ role: ref.role, dataUrl: ref.dataUrl })),
                  presetId: presetId || undefined,
                  note: editNote.trim() || undefined,
                  size: effectiveSize,
                  seed,
                },
              })
              setNotice('已排上队了，做好会显示在右边')
            }
            setPreviewText('')
            syncJobs()
          } catch (err) {
            setError(err.message)
          } finally {
            setSubmitting(false)
          }
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
                presetId: presetId || undefined,
                note: editNote.trim() || undefined,
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
          if (scenario === 'text') setTextPrompt(text)
          else setEditNote(text)
          setNotice('已写入')
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
            refreshHistory()
          } catch (err) {
            setError(err.message)
          }
        }

        function reuse(item) {
          if (item.presetId) setPresetId(item.presetId)
          if (item.size) {
            const known = SIZE_CHOICES.some((choice) => choice.value === item.size)
            setSize(known ? item.size : '1024x1024')
            setCustomSize(known ? '' : item.size)
          }
          if (item.seed !== undefined && item.seed !== null) {
            setSeedMode('fixed')
            setSeedValue(String(item.seed))
          }
          if (item.mode === 'image2image') {
            setScenario('retouch')
            // Prefer the user's own wording: the stored prompt already carries
            // the preset instruction and would be wrapped again on resubmit.
            setEditNote(item.note || '')
            setNotice(
              item.note
                ? '已把当时的要求、尺寸和编号填回来了；照片需要你重新选一次'
                : '已把尺寸和编号填回来了；当时的要求是预设生成的，照片需要重新选',
            )
          } else {
            setScenario('text')
            setTextPrompt(item.prompt || '')
            setNotice('已把当时的描述、尺寸和编号填回来，通常能出几乎一样的图')
          }
          setAdvanced(true)
        }

        async function useAsReference(item) {
          try {
            const res = await fetch(item.fileUrl)
            if (!res.ok) throw new Error('HTTP ' + res.status)
            const blob = await res.blob()
            const dataUrl = await new Promise((resolve, reject) => {
              const reader = new FileReader()
              reader.onload = () => resolve(String(reader.result))
              reader.onerror = () => reject(new Error('读取失败'))
              reader.readAsDataURL(blob)
            })
            const limit = scenario === 'text' ? MAX_COMBINE_REFS : refLimit
            setRefs((current) =>
              current
                .concat([{
                  id: Math.random().toString(36).slice(2),
                  name: item.name,
                  size: blob.size,
                  dataUrl,
                  w: Number((/^(\d+)x(\d+)$/.exec(String(item.size || '')) || [])[1]) || 0,
                  h: Number((/^(\d+)x(\d+)$/.exec(String(item.size || '')) || [])[2]) || 0,
                  role: current.length === 0 ? 'subject' : 'detail',
                }])
                .slice(0, limit),
            )
            if (scenario === 'text') setScenario('retouch')
            setNotice('已作为素材加入，可以继续在这张图上改')
          } catch (err) {
            setError('加入失败：' + err.message)
          }
        }

        // ------------------------------------------------------------- render

        const head = h(
          'header',
          { className: 'mw-head' },
          h('div', { className: 'mw-title' }, t('title')),
          h(
            'div',
            { className: 'mw-badges' },
            h(ServiceBadge, {
              ok: imageOk,
              label: capsLoading
                ? '出图服务检查中…'
                : imageOk
                  ? '出图服务就绪' + (imageCaps.busy ? '（忙）' : '')
                  : '出图服务没连上',
              tone: imageCaps && imageCaps.busy ? 'busy' : undefined,
              title: imageCaps ? imageCaps.error || imageCaps.model || '' : '',
            }),
            h(ServiceBadge, {
              ok: llmOk,
              label: capsLoading ? '写作助手检查中…' : llmOk ? '写作助手就绪' : '写作助手没连上',
              title: '本地文字模型，用来帮你把描述写得更清楚',
            }),
            h(
              'button',
              { type: 'button', className: 'mw-btn tiny', onClick: refreshCaps, disabled: submitting },
              '重新检查',
            ),
          ),
        )

        const scenarioPicker = h(
          'div',
          { className: 'mw-scenarios' },
          SCENARIOS.map((item) =>
            h(
              'button',
              {
                key: item.id, type: 'button',
                className: 'mw-scenario' + (scenario === item.id ? ' on' : ''),
                onClick: () => chooseScenario(item.id),
              },
              h('span', { className: 'mw-sicon' }, item.icon),
              h(
                'span',
                null,
                h('div', { className: 'mw-stitle' }, item.title),
                h('div', { className: 'mw-ssum' }, item.summary),
              ),
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
                placeholder: '用大白话就行，例如：一只戴墨镜的橘猫坐在窗台上晒太阳，下午的光很暖',
                onChange: (e) => setTextPrompt(e.target.value),
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
                          h('img', { src: ref.dataUrl, alt: '第 ' + (index + 1) + ' 张参考图' }),
                          h('span', { className: 'mw-refno' }, '图' + (index + 1)),
                        ),
                        isRetouch
                          ? index === 0
                            ? h('div', { className: 'mw-hint', style: { textAlign: 'center' } }, '要修改的这张')
                            : h(
                                'select',
                                {
                                  className: 'mw-refrole', value: ref.role, title: '这张图用来做什么',
                                  onChange: (e) => setRefRole(ref.id, e.target.value),
                                },
                                EXTRA_ROLE_CHOICES.map((choice) =>
                                  h('option', { key: choice.id, value: choice.id }, choice.label),
                                ),
                              )
                          : h(
                              'select',
                              {
                                className: 'mw-refrole', value: ref.role, title: '这张图用来做什么',
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
                            onClick: () => moveRef(index, -1), disabled: index === 0,
                          }, '↑'),
                          h('button', {
                            type: 'button', className: 'mw-btn tiny', title: '往后挪一位',
                            onClick: () => moveRef(index, 1), disabled: index === refs.length - 1,
                          }, '↓'),
                          h('button', {
                            type: 'button', className: 'mw-btn tiny', title: '删掉这张',
                            onClick: () => removeRef(ref.id),
                          }, '×'),
                        ),
                      ),
                    ),
                    refs.length < refLimit
                      ? h('button', {
                          type: 'button', className: 'mw-btn mw-addref',
                          onClick: () => fileRef.current && fileRef.current.click(),
                          title: '再加一张',
                        }, '+')
                      : null,
                  ),
              h('input', {
                ref: fileRef, type: 'file', accept: 'image/png,image/jpeg,image/webp',
                multiple: true, style: { display: 'none' }, onChange: onPickFiles,
              }),
              isRetouch
                ? h('div', { className: 'mw-hint' }, '第 1 张是要改的照片。要换背景或换衣服，可以再加第 2 张当参考，也可以在下面用文字描述。')
                : h('div', { className: 'mw-hint' }, '每张图下面选它「用来做什么」，模型会照这个分配角色，图 1、图 2 就是编号。'),
            ),
          )

          if (isRetouch) {
            fields.push(
              h(
                'div',
                { key: 'preset', className: 'mw-field' },
                h('div', { className: 'mw-label' }, '① 选一个最接近的处理方式（可跳过）'),
                h(
                  'div',
                  { className: 'mw-presets' },
                  presets.map((preset) =>
                    h(
                      'button',
                      {
                        key: preset.id, type: 'button', title: preset.summary,
                        className: 'mw-preset' + (presetId === preset.id ? ' on' : ''),
                        onClick: () => choosePreset(preset.id),
                      },
                      preset.label,
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
              h('div', { className: 'mw-label' }, isRetouch ? '② 再补充具体要求（可跳过）' : '想怎么合（可跳过）'),
              h('textarea', {
                className: 'mw-ta', rows: 3, value: editNote, disabled: submitting,
                placeholder: isRetouch
                  ? '例如：背景换成海边日落，衣服换成米色针织衫'
                  : '例如：图1 的人放到图2 的沙滩上，穿图3 的衣服',
                onChange: (e) => setEditNote(e.target.value),
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
                            if (target === 'text') setTextPrompt(item.text)
                            else setEditNote(item.text)
                            setNotice('已填入常用提示词')
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

        // ---- shared controls
        const sizeBody = []

        if (followSource) {
          sizeBody.push(
            h(
              'div',
              { key: 'auto', className: 'mw-note' },
              autoSize
                ? h(
                    'div',
                    null,
                    h('b', null, '跟随原图比例'),
                    '　原图 ' + firstRef.w + '×' + firstRef.h + '（' + ratioLabel(firstRef.w, firstRef.h) + '）',
                    h('br'),
                    '输出 ' +
                      autoSize.width +
                      '×' +
                      autoSize.height +
                      '（' +
                      autoSize.ratio +
                      '，' +
                      (autoSize.pixels / 1000000).toFixed(2) +
                      ' MP）',
                  )
                : h('div', null, '跟随原图比例：选好照片后自动算出输出尺寸'),
            ),
          )
          sizeBody.push(
            h(
              'div',
              { key: 'density', style: { marginTop: 8 } },
              h('div', { className: 'mw-label' }, '清晰度'),
              h(
                'select',
                {
                  className: 'mw-sel', value: density, disabled: submitting,
                  onChange: (e) => setDensity(e.target.value),
                },
                DENSITY.map((item) => h('option', { key: item.id, value: item.id }, item.label)),
              ),
              h(
                'div',
                { className: 'mw-hint' },
                '比例永远跟原图一致，这里只决定清晰度。该模型在 1024² 附近训练，再高收益有限；调太低会让脸和细节糊掉。',
              ),
            ),
          )
          sizeBody.push(
            h(
              'div',
              { key: 'tosmanual', style: { marginTop: 6 } },
              h(
                'button',
                { type: 'button', className: 'mw-disclosure', onClick: () => setSizeMode('manual') },
                '想自己指定画面尺寸？',
              ),
            ),
          )
        } else {
          sizeBody.push(
            h(
              'select',
              {
                key: 'pick', className: 'mw-sel', value: customSize.trim() ? '__custom' : size, disabled: submitting,
                onChange: (e) => {
                  if (e.target.value === '__custom') {
                    setAdvanced(true)
                    setCustomSize(effectiveSize)
                  } else {
                    setSize(e.target.value)
                    setCustomSize('')
                  }
                },
              },
              SIZE_CHOICES.map((choice) =>
                h('option', { key: choice.value, value: choice.value }, choice.label + '（' + choice.note + '）'),
              ),
              h('option', { value: '__custom' }, '自定义…'),
            ),
          )
          if (scenario !== 'text' && refs.length > 0) {
            sizeBody.push(
              h(
                'div',
                { key: 'toauto', style: { marginTop: 6 } },
                h(
                  'button',
                  { type: 'button', className: 'mw-disclosure', onClick: () => setSizeMode('auto') },
                  '改回跟随原图比例',
                ),
              ),
            )
          }
        }

        sizeBody.push(
          sizeCheck.valid
            ? h('div', { key: 'eta', className: 'mw-hint' }, '大约要等 ' + formatDuration(expected))
            : h('div', { key: 'bad', className: 'mw-warn' }, sizeCheck.msg + (sizeCheck.snapTo ? '，建议改成 ' + sizeCheck.snapTo : '')),
          sizeCheck.valid && sizeCheck.minSide && sizeCheck.minSide < 768 && refCount > 0
            ? h('div', { key: 'low', className: 'mw-warn' }, '这个清晰度偏低，脸和细节容易糊；建议用「推荐」及以上。')
            : null,
          tooSlow
            ? h('div', { key: 'slow', className: 'mw-warn' }, '这个组合超出单次允许时长，请减少图片数量或选低一档清晰度。')
            : null,
        )

        fields.push(
          h(
            'div',
            { key: 'size', className: 'mw-field' },
            h('div', { className: 'mw-label' }, '画面尺寸'),
            sizeBody,
          ),
        )

        fields.push(
          h(
            'div',
            { key: 'adv', className: 'mw-field' },
            h(
              'button',
              { type: 'button', className: 'mw-disclosure', onClick: () => setAdvanced(!advanced) },
              advanced ? '收起更多设置' : '更多设置（一般不用动）',
            ),
            advanced
              ? h(
                  'div',
                  { style: { marginTop: 10 } },
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '自定义尺寸'),
                    h('input', {
                      className: 'mw-in', value: customSize, disabled: submitting,
                      placeholder: '留空就用上面选的',
                      onChange: (e) => setCustomSize(e.target.value),
                    }),
                    h('div', { className: 'mw-hint' }, '宽和高要是 ' + multiple + ' 的整数倍，系统会自动帮你对齐。'),
                  ),
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
                        h('div', { className: 'mw-label' }, '一次出几张'),
                        h(
                          'select',
                          {
                            className: 'mw-sel', value: String(batch), disabled: submitting,
                            onChange: (e) => setBatch(Number(e.target.value)),
                          },
                          h('option', { value: '1' }, '1 张'),
                          h('option', { value: '2' }, '2 张（更慢，但可以挑一张）'),
                        ),
                      )
                    : null,
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

        function settingsBlock() {
          const draft = settingsDraft
          const setField = (patch) => setSettingsDraft((current) => ({ ...current, ...patch }))
          return h(
            'div',
            { key: 'settings', className: 'mw-field' },
            h(
              'button',
              {
                type: 'button',
                className: 'mw-disclosure',
                onClick: () => {
                  const next = !settingsOpen
                  setSettingsOpen(next)
                  if (next && !settingsData) loadSettings()
                },
              },
              settingsOpen ? '收起模型服务' : '模型服务（出图与提示词服务地址）',
            ),
            settingsOpen
              ? h(
                  'div',
                  { style: { marginTop: 10 } },
                  settingsError ? h('div', { className: 'mw-err' }, settingsError) : null,
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '出图服务地址'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      value: draft.imageBaseUrl || '',
                      placeholder: settingsData ? settingsData.defaults.imageBaseUrl : 'http://127.0.0.1:9741',
                      onChange: (e) => setField({ imageBaseUrl: e.target.value }),
                    }),
                  ),
                  h(
                    'div',
                    { className: 'mw-field' },
                    h('div', { className: 'mw-label' }, '提示词服务地址'),
                    h('input', {
                      className: 'mw-in', disabled: settingsBusy,
                      value: draft.llmBaseUrl || '',
                      placeholder: settingsData ? settingsData.defaults.llmBaseUrl : 'http://127.0.0.1:9739',
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
                    h('div', { className: 'mw-label' }, '单次出图最长等待（秒）'),
                    h('input', {
                      className: 'mw-in', type: 'number', min: 30, step: 30, disabled: settingsBusy,
                      value: Math.round((Number(draft.imageTimeoutMs) || 900000) / 1000),
                      onChange: (e) => setField({ imageTimeoutMs: Number(e.target.value) * 1000 }),
                    }),
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
                    { className: 'mw-row' },
                    h(
                      'button',
                      {
                        type: 'button', className: 'mw-btn', style: { flex: '1 1 auto' },
                        disabled: settingsBusy, onClick: testSettingsDraft,
                      },
                      settingsBusy ? '检测中…' : '测试连接',
                    ),
                    h(
                      'button',
                      {
                        type: 'button', className: 'mw-btn', style: { flex: '1 1 auto' },
                        disabled: settingsBusy, onClick: saveSettingsDraft,
                      },
                      '保存并生效',
                    ),
                  ),
                  h(
                    'div',
                    { style: { marginTop: 6 } },
                    h(
                      'button',
                      { type: 'button', className: 'mw-disclosure', disabled: settingsBusy, onClick: resetSettingsDraft },
                      '恢复默认地址',
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
                    ? h('div', { className: 'mw-hint' }, '设置存在 ' + settingsData.stored.path + '，不进仓库')
                    : null,
                )
              : null,
          )
        }

        fields.push(settingsBlock())

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

        const jobsBox = activeJobs.length
          ? h(
              'div',
              { key: 'jobs' },
              activeJobs.map((job) =>
                h(
                  'div',
                  { key: job.id, className: 'mw-job' },
                  h('span', { className: 'mw-spin' }),
                  h(
                    'span',
                    { className: 'grow' },
                    job.status === 'queued' ? '排队中' : '正在做',
                    '　',
                    job.status === 'queued'
                      ? '前面还有 ' + Math.max(0, job.queuePosition - 1) + ' 个'
                      : '已等 ' + formatDuration(job.elapsedSeconds),
                    h(
                      'div',
                      { className: 'mw-hint', style: { marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' } },
                      humanisePrompt(job.request.prompt).slice(0, 60),
                    ),
                  ),
                ),
              ),
              h(
                'div',
                { className: 'mw-hint', style: { marginBottom: 4 } },
                '任务在后台跑，切到别的页面、刷新、最小化都不会中断；回来还能看到结果。',
              ),
            )
          : null

        function renderCard(item, list, index) {
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
              h('img', {
                className: 'mw-thumbimg', src: item.fileUrl,
                alt: item.prompt || '生成的图片', loading: 'lazy',
                style: { aspectRatio: parseAspect(item.size) },
              }),
            ),
            h(
              'div',
              { className: 'mw-cardbody' },
              h(
                'div',
                { className: 'mw-meta' },
                h('span', null, item.size || ''),
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
                Array.isArray(item.refFiles) && item.refFiles.length > 0
                  ? h('button', {
                      type: 'button', className: 'mw-btn tiny',
                      title: '和参考图并排或滑动对比',
                      onClick: () => {
                        openViewer(list, index)
                        setViewerMode('compare')
                      },
                    }, '对比原图')
                  : null,
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: '当成素材，继续在这张上改',
                  onClick: () => useAsReference(item),
                }, '继续改这张'),
                h('a', {
                  className: 'mw-btn tiny', href: item.fileUrl, download: item.name,
                  style: { textDecoration: 'none', display: 'inline-block' },
                }, '下载'),
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  title: item.archived ? '移回主列表' : '收进归档，主界面不再显示（不会删除）',
                  onClick: () => setArchived(item.name, !item.archived),
                }, item.archived ? '移出归档' : '归档'),
                h('button', {
                  type: 'button', className: 'mw-btn tiny', disabled: submitting,
                  onClick: () => deleteItem(item.name),
                }, '删除'),
              ),
            ),
          )
        }

        const historyOnly = history.filter((entry) => !results.some((item) => item.name === entry.name))

        const resultsPane = h(
          'section',
          { className: 'mw-results' },
          pollError ? h('div', { className: 'mw-err' }, pollError + '（可以点右上角重新检查，或刷新页面）') : null,
          jobsBox,
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
              { type: 'button', className: 'mw-disclosure', onClick: toggleArchive },
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
          notice ? h('div', { className: 'mw-hint', style: { marginBottom: 8 } }, notice) : null,
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

        const viewed = viewer && viewer.list.length > 0 ? viewer.list[Math.min(viewer.index, viewer.list.length - 1)] : null
        const refFiles = viewed && Array.isArray(viewed.refFiles) ? viewed.refFiles.filter(Boolean) : []
        const canCompare = refFiles.length > 0
        // Three references already fill the row; more would make each one tiny.
        const refShown = refFiles.slice(0, 3)

        const viewerNode =
          viewer && viewed
            ? h(
                'div',
                { className: 'mw-viewer', role: 'dialog', 'aria-modal': 'true', 'aria-label': '查看图片' },
                h(
                  'div',
                  { className: 'mw-viewerhead' },
                  h(
                    'button',
                    {
                      type: 'button', className: 'mw-btn tiny', autoFocus: true,
                      onClick: () => setViewer(null),
                    },
                    '关闭（Esc）',
                  ),
                  viewer.list.length > 1
                    ? h('button', { type: 'button', className: 'mw-btn tiny', onClick: () => stepViewer(-1) }, '← 上一张')
                    : null,
                  viewer.list.length > 1
                    ? h('button', { type: 'button', className: 'mw-btn tiny', onClick: () => stepViewer(1) }, '下一张 →')
                    : null,
                  h('span', { className: 'mw-hint', style: { margin: 0 } }, viewer.index + 1 + ' / ' + viewer.list.length),
                  canCompare
                    ? h(
                        'button',
                        {
                          type: 'button', className: 'mw-btn tiny',
                          onClick: () => setViewerMode(viewerMode === 'compare' ? 'single' : 'compare'),
                        },
                        viewerMode === 'compare' ? '只看结果' : '对比参考图',
                      )
                    : null,
                  canCompare && viewerMode === 'compare'
                    ? h(
                        'button',
                        {
                          type: 'button', className: 'mw-btn tiny',
                          onClick: () => setCompareLayout(compareLayout === 'side' ? 'wipe' : 'side'),
                        },
                        compareLayout === 'side' ? '换成滑动对比' : '换成并排对比',
                      )
                    : null,
                  !canCompare || viewerMode === 'single'
                    ? h(
                        'button',
                        {
                          type: 'button', className: 'mw-btn tiny',
                          onClick: () => setViewerActual(!viewerActual),
                        },
                        viewerActual ? '适应屏幕' : '看实际像素',
                      )
                    : null,
                  h(
                    'a',
                    {
                      className: 'mw-btn tiny', href: viewed.fileUrl, download: viewed.name,
                      style: { textDecoration: 'none', display: 'inline-block' },
                    },
                    '下载',
                  ),
                  h(
                    'a',
                    {
                      className: 'mw-btn tiny', href: viewed.fileUrl, target: '_blank', rel: 'noreferrer',
                      style: { textDecoration: 'none', display: 'inline-block' },
                    },
                    '在新窗口打开',
                  ),
                ),
                viewerMode === 'compare' && canCompare
                  ? compareLayout === 'side'
                    ? h(
                        'div',
                        {
                          className: 'mw-cmp',
                          onClick: (event) => {
                            if (event.target === event.currentTarget) setViewer(null)
                          },
                        },
                        refShown.map((ref, index) =>
                          h(
                            'div',
                            { key: 'ref' + index, className: 'mw-cmpPane' },
                            h('div', { className: 'cap' }, '参考图 ' + (index + 1)),
                            h('img', { src: fileUrl(ref), alt: '参考图 ' + (index + 1) }),
                          ),
                        ),
                        h(
                          'div',
                          { key: 'out', className: 'mw-cmpPane' },
                          h('div', { className: 'cap' }, '生成结果'),
                          h('img', { src: viewed.fileUrl, alt: '生成结果' }),
                        ),
                      )
                    : h(
                        'div',
                        { className: 'mw-wipewrap' },
                        h(
                          'div',
                          { className: 'mw-wipe' },
                          h('img', { src: fileUrl(refShown[0]), alt: '参考图' }),
                          h('img', {
                            src: viewed.fileUrl,
                            alt: '生成结果',
                            style: { clipPath: 'inset(0 0 0 ' + cut + '%)' },
                          }),
                        ),
                        h(
                          'div',
                          { className: 'mw-wipeslider' },
                          h(
                            'div',
                            { className: 'mw-hint', style: { marginBottom: 6 } },
                            '拖动滑块：左边是参考图，右边是生成结果',
                          ),
                          h('input', {
                            type: 'range', min: 0, max: 100, value: cut,
                            className: 'mw-range',
                            'aria-label': '对比分隔位置',
                            onChange: (event) => setCut(Number(event.target.value)),
                          }),
                        ),
                      )
                  : h(
                      'div',
                      {
                        className: 'mw-viewerbody',
                        onClick: (event) => {
                          if (event.target === event.currentTarget) setViewer(null)
                        },
                      },
                      h('img', {
                        className: 'mw-viewerimg' + (viewerActual ? ' actual' : ''),
                        src: viewed.fileUrl,
                        alt: viewed.prompt || '图片',
                      }),
                    ),
                h(
                  'div',
                  { className: 'mw-viewerfoot' },
                  h('span', null, viewed.size || ''),
                  viewed.seconds ? h('span', null, formatDuration(viewed.seconds)) : null,
                  viewed.note ? h('span', null, '你的要求：' + viewed.note) : null,
                  h('span', { style: { marginLeft: 'auto' } }, 'Esc 关闭 · 左右方向键翻页 · 点空白处关闭'),
                ),
              )
            : null

        return h(
          'div',
          { className: 'mw-root' },
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
            resultsPane,
          ),
          viewerNode,
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
              },
            }),
          'glow: dictionaries',
        )

        const Workbench = createWorkbench(ctx)

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
