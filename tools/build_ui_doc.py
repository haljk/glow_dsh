# Build the Word version of screenshots/README.md, with the screenshots embedded so the
# document stands on its own when it leaves the repository.

from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(r"D:\Texts\00_个人资料\AI Native\20261121 个人多媒体工作台\glow")
SHOTS = ROOT / "screenshots"
OUT = SHOTS / "阁楼-界面截图说明-v1.1.33.docx"

FONT = "微软雅黑"
ACCENT = RGBColor(0x5B, 0x3F, 0xD6)
GREY = RGBColor(0x5B, 0x59, 0x68)

# (file, 页面 · 状态, 说明)
SHOT_ROWS = [
    ("01-workbench-empty.png", "工作台 · 初始",
     "顶部状态点、三张场景卡（选中态为边框加浅紫底）、描述输入框、尺寸与张数、「以前做的」入口；"
     "底部主按钮在未填描述时呈禁用态。"),
    ("02-workbench-prompt.png", "工作台 · 已输入",
     "同一页面输入一句描述后：主按钮变为实心紫，底部提示语消失。"),
    ("03-workbench-retouch.png", "工作台 · 照片精修",
     "切到精修：① 选一张照片（已选一张，带「作为：主体」角色标签）→ ② 要做什么 → ③ 补充要求。"),
    ("04-workbench-combine.png", "工作台 · 多图合成",
     "切到合成：① 放入参考图（两张，各带角色）→ ② 想怎么合 → 输出清晰度三档。"),
    ("05-workbench-lower.png", "工作台 · 下半屏",
     "尺寸网格（四列两行加张数一行）与「以前做的」图库入口的完整结构。"),
    ("06-job-running.png", "任务进行中",
     "顶部横幅显示「排队中 / 已等 3 秒 / 不再等」，主按钮变为「正在生成」并转圈。"),
    ("07-job-done.png", "任务完成",
     "同一位置的完成态横幅，主按钮恢复可点。"),
    ("08-gallery.png", "以前做的",
     "两列图库网格，每条显示生成时填的描述与相对时间。"),
    ("09-viewer-switch.png", "查看器 · 切换",
     "单图全屏，右上角「原图 / 结果」胶囊，底部三段选择器（切换 / 并排 / 滑动）。"),
    ("10-viewer-side.png", "查看器 · 并排",
     "左右并置，中缝用背景色而非装饰线分隔。"),
    ("11-viewer-wipe.png", "查看器 · 滑动",
     "分界线只画在照片范围内，可拖动；下方有「拖动分界线对比」提示。"),
    ("12-viewer-textonly.png", "查看器 · 无参考图",
     "文生图结果没有可对比的原图，因此不出现三段选择器，只保留缩放提示。"),
    ("13-settings-top.png", "设置 · 顶部",
     "队列 / 同步模式选择（两枚 Chip）、服务地址、高级折叠入口。"),
    ("14-settings-advanced.png", "设置 · 高级展开",
     "回退用地址、模型名，以及「写别的会被当成仓库名」的说明。"),
    ("15-settings-bottom.png", "设置 · 等待时长与本地存储",
     "两个超时输入、保留参考图副本开关、保存按钮，以及两台服务的探测状态。"),
    ("16-settings-save-probing.png", "设置 · 保存中",
     "地址填成不可达时点保存：按钮「正在保存…」、反馈行「已保存，正在测试…」、两行「测试中…」。"),
    ("17-settings-save-failed.png", "设置 · 保存后连接失败",
     "红字「已保存，但出图服务连不上」，状态行给出地址与「无响应」，提示词服务仍显示已连接。"),
    ("18-archive.png", "归档箱 · 空态",
     "说明归档不删文件，只把结果从主列表挪走。"),
    ("19-instruction-sheet.png", "看会发送什么",
     "提交前展开的说明面板，展示最终发给模型的指令构成。"),
    ("20-dark-workbench.png", "深色模式 · 工作台",
     "同一张工作台在深色下的对比层次与状态色。"),
    ("21-dark-viewer.png", "深色模式 · 查看器",
     "查看器本来就是深色外壳，这里看的是它与系统深色模式的一致性。"),
    ("22-dark-settings.png", "深色模式 · 设置",
     "深色下的卡片、输入框、Chip 与状态点。"),
]

REVIEW_POINTS = [
    ("工作台的信息密度",
     "一屏里同时有状态、三种玩法、输入框、尺寸与张数、图库入口，底部还有固定操作条。"
     "是否过密？三张场景卡是否有必要常驻，还是应该收进首次引导？"),
    ("场景卡的选中态",
     "现在用「浅紫底 + 2dp 主色边框」表示选中，与输入框聚焦时的紫色边框是否冲突、是否足够明显。"),
    ("三段选择器的位置",
     "它位于图片下方、操作条上方。手机上是否应收进右上角菜单，把整个下沿留给照片。"),
    ("查看器的黑白比例",
     "方形图在 411dp 宽的屏幕上上下各留约 373px（见第 09 张）。这是「照片居中、两侧留黑」的取舍，"
     "是否可接受。"),
    ("设置页的信息架构",
     "连接、等待时长、本地存储三段，外加折叠的高级。是否有更合适的组织方式，"
     "尤其是「队列 / 同步」这种技术选择是否该暴露给普通用户。"),
    ("深色模式",
     "见第 20–22 张，重点看卡片边界与状态点在深色下是否还需要更强的区分。"),
]


def set_east_asian(style_or_run, name=FONT):
    rpr = style_or_run.element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        from docx.oxml import OxmlElement

        fonts = OxmlElement("w:rFonts")
        rpr.append(fonts)
    fonts.set(qn("w:eastAsia"), name)
    fonts.set(qn("w:ascii"), name)
    fonts.set(qn("w:hAnsi"), name)


def style_font(style, size, bold=False, color=None):
    style.font.name = FONT
    style.font.size = Pt(size)
    style.font.bold = bold
    if color is not None:
        style.font.color.rgb = color
    set_east_asian(style)


def add_body(doc, text, size=10.5, color=None, space_after=6, align=None):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.font.size = Pt(size)
    run.font.name = FONT
    if color is not None:
        run.font.color.rgb = color
    set_east_asian(run)
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.35
    if align is not None:
        p.alignment = align
    return p


def add_kv_table(doc, rows):
    table = doc.add_table(rows=0, cols=2)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.LEFT
    for key, value in rows:
        cells = table.add_row().cells
        cells[0].width = Cm(3.6)
        cells[1].width = Cm(12.4)
        p0 = cells[0].paragraphs[0]
        r0 = p0.add_run(key)
        r0.font.bold = True
        r0.font.size = Pt(9.5)
        set_east_asian(r0)
        p1 = cells[1].paragraphs[0]
        r1 = p1.add_run(value)
        r1.font.size = Pt(9.5)
        set_east_asian(r1)
    return table


doc = Document()

# page setup: A4, 2cm margins
section = doc.sections[0]
section.page_width = Cm(21)
section.page_height = Cm(29.7)
section.left_margin = Cm(2.2)
section.right_margin = Cm(2.2)
section.top_margin = Cm(2.0)
section.bottom_margin = Cm(1.8)

style_font(doc.styles["Normal"], 10.5)
for name, size in (("Heading 1", 17), ("Heading 2", 13), ("Heading 3", 11)):
    style_font(doc.styles[name], size, bold=True, color=ACCENT)

# ------------------------------------------------------------------ cover block
title = doc.add_paragraph()
tr = title.add_run("阁楼（Glow）界面截图说明")
tr.font.size = Pt(24)
tr.font.bold = True
tr.font.color.rgb = ACCENT
set_east_asian(tr)
title.paragraph_format.space_after = Pt(2)

sub = doc.add_paragraph()
sr = sub.add_run("Android 客户端 · 当前版本界面 · 供外部设计评审")
sr.font.size = Pt(11)
sr.font.color.rgb = GREY
set_east_asian(sr)
sub.paragraph_format.space_after = Pt(14)

add_kv_table(doc, [
    ("应用", "阁楼（Glow）"),
    ("版本", "1.1.33-10b1faf（versionCode 33）"),
    ("包名", "dev.haljk.glow"),
    ("截图环境", "Android 16（API 36）模拟器 · 1080×2400 · 密度 2.6 · 屏宽约 411dp · 界面语言中文"),
    ("服务", "接入局域网内的真实模型服务（队列 :9742 / 提示词 :9739），截图中的「服务已连接」为真实探测结果"),
    ("素材", "「以前做的」中的图片均为实际生成结果"),
    ("截图方式", "uiautomator 定位控件 + adb 截屏，按 01→22 顺序驱动同一台设备，无后期修饰"),
    ("文档日期", "2026-11-24"),
])

doc.add_paragraph()

doc.add_paragraph("这份文档怎么用", style="Heading 1")
add_body(
    doc,
    "按编号顺序看，即是一条完整的使用路径：进入工作台 → 选择一种玩法 → 提交 → 等待 → 看图 → 对比 → 设置；"
    "第 20–22 张是同一批页面在系统深色模式下的样子。每张截图单独占一页，下方标注该屏的状态与设计意图。",
)
add_body(doc, "文末列出了希望顾问重点判断的六处，以及截图与设计令牌的复现方式。")

# ------------------------------------------------------------------ index table
doc.add_paragraph("截图目录", style="Heading 1")
index = doc.add_table(rows=1, cols=3)
index.style = "Table Grid"
header = index.rows[0].cells
for cell, text in zip(header, ("编号", "页面 / 状态", "说明")):
    p = cell.paragraphs[0]
    r = p.add_run(text)
    r.font.bold = True
    r.font.size = Pt(9.5)
    set_east_asian(r)
for i, (name, page, note) in enumerate(SHOT_ROWS, start=1):
    cells = index.add_row().cells
    for cell, text, width in ((cells[0], f"{i:02d}", Cm(1.4)), (cells[1], page, Cm(4.2)), (cells[2], note, Cm(10.4))):
        cell.width = width
        p = cell.paragraphs[0]
        r = p.add_run(text)
        r.font.size = Pt(9)
        set_east_asian(r)
        p.paragraph_format.space_after = Pt(2)

# ------------------------------------------------------------------ one page per shot
for i, (name, page, note) in enumerate(SHOT_ROWS, start=1):
    path = SHOTS / name
    if not path.exists():
        continue
    doc.add_page_break()
    doc.add_paragraph(f"{i:02d} · {page}", style="Heading 2")
    add_body(doc, note, size=10, color=GREY, space_after=8)
    pic_par = doc.add_paragraph()
    pic_par.alignment = WD_ALIGN_PARAGRAPH.CENTER
    pic_par.paragraph_format.space_after = Pt(4)
    run = pic_par.add_run()
    # 1080x2400 portrait: bound by height so the whole screen fits one page
    run.add_picture(str(path), height=Cm(19.5))
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cr = cap.add_run(name)
    cr.font.size = Pt(8)
    cr.font.color.rgb = GREY
    set_east_asian(cr)

# ------------------------------------------------------------------ review points
doc.add_page_break()
doc.add_paragraph("希望顾问重点判断的六处", style="Heading 1")
for idx, (head, body) in enumerate(REVIEW_POINTS, start=1):
    doc.add_paragraph(f"{idx}. {head}", style="Heading 3")
    add_body(doc, body, size=10.5, space_after=8)

doc.add_paragraph("复现方式", style="Heading 1")
add_body(
    doc,
    "截图脚本位于仓库 tools/capture-ui*.ps1：用 uiautomator 定位控件、adb 截屏，按同一顺序驱动同一台模拟器，"
    "每次按下快门前会断言预期文字已出现在屏幕上，避免拍到上一屏却仍以有效图片保存。",
)
add_body(
    doc,
    "颜色、字号、间距等样式令牌见 docs/PRODUCT-DESIGN.md 与 "
    "android/app/src/main/kotlin/dev/haljk/glow/ui/theme/Theme.kt。",
)

doc.save(OUT)
print(f"saved: {OUT}")
paragraphs = len(doc.paragraphs)
tables = len(doc.tables)
pictures = len(doc.inline_shapes)
print(f"paragraphs={paragraphs} tables={tables} pictures={pictures}")
