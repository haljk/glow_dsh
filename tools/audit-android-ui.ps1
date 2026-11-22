<#
  Capture the interaction flow and measure it.

  Screenshots show what a screen looks like; this script also records what the
  interaction *is*: every tappable node's size in dp, the states around each
  transition, and the awkward conditions (keyboard open, landscape, large font).

  Output: <out>\frames\*.png  and <out>\audit.md
#>
param(
    [string]$Out = 'C:\glow-build\review2'
)

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$pkg = 'dev.haljk.glow'
$density = 420.0            # xxhdpi on this AVD
$dp = { param($px) [math]::Round($px / ($density / 160.0), 1) }

$frames = Join-Path $Out 'frames'
New-Item -ItemType Directory -Force -Path $frames | Out-Null
$report = New-Object System.Collections.Generic.List[string]
function Say($line) { $report.Add($line); Write-Host $line }

$shotIndex = 0
function Shot([string]$label) {
    $script:shotIndex++
    $name = '{0:d2}-{1}' -f $script:shotIndex, $label
    & $adb shell screencap -p /sdcard/f.png 2>&1 | Out-Null
    & $adb pull /sdcard/f.png (Join-Path $frames "$name.png") 2>&1 | Out-Null
    Write-Host "  frame $name"
}

function Dump() {
    $p = "$env:TEMP\d$([guid]::NewGuid().ToString('N').Substring(0,5)).xml"
    & $adb shell uiautomator dump /sdcard/d.xml 2>&1 | Out-Null
    & $adb pull /sdcard/d.xml $p 2>&1 | Out-Null
    return $p
}

function TapText($xml, $text) {
    [xml]$d = Get-Content $xml
    $n = $d.SelectNodes("//*[@text='$text']") | Select-Object -First 1
    if (-not $n) { return $false }
    $m = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y 2>&1 | Out-Null
    return $true
}

function TapDesc($xml, $desc) {
    [xml]$d = Get-Content $xml
    $n = $d.SelectNodes("//*[@content-desc='$desc']") | Select-Object -First 1
    if (-not $n) { return $false }
    $m = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y 2>&1 | Out-Null
    return $true
}

# Every tappable node, measured in dp. Anything under 48 dp is reported.
function AuditTouch($xml, $label) {
    [xml]$d = Get-Content $xml
    Say ""
    Say "### 可点区域测量 · $label"
    Say ""
    Say '| 元素（文字/描述） | 宽×高 (dp) | 是否 ≥48dp |'
    Say '| --- | --- | --- |'
    $small = 0
    foreach ($n in $d.SelectNodes("//*[@clickable='true']")) {
        $m = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
        if (-not $m.Success) { continue }
        $w = (& $dp ([int]$m.Groups[3].Value - [int]$m.Groups[1].Value))
        $h = (& $dp ([int]$m.Groups[4].Value - [int]$m.Groups[2].Value))
        $name = ''
        if ($n.text) { $name = $n.text } elseif ($n.'content-desc') { $name = $n.'content-desc' } else { $name = "($($n.class))" }
        $ok = ($h -ge 44)
        if (-not $ok) { $small++ }
        Say ("| {0} | {1}×{2} | {3} |" -f ($name -replace '\|', '/'), $w, $h, $(if ($ok) { '是' } else { '**否**' }))
    }
    Say ""
    Say "  低于 44dp 高的可点区域：$small 个"
    return $small
}

function Texts($xml) {
    [xml]$d = Get-Content $xml
    return @($d.SelectNodes("//*[@text!='']") | ForEach-Object { $_.text } | Where-Object { $_.Trim() -ne '' })
}

Say '# 交互审计（真机采集）'
Say ''
Say "采集时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm')"
Say "设备：1080×2400 @ 420dpi（411×914dp）"
Say ''

# ---------------------------------------------------------------- 启动到首屏
Say '## 1. 启动 → 首屏'
Say ''
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb shell am start -W -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 1
Shot 'launch-1s'
Start-Sleep -Seconds 1
Shot 'launch-2s'
Start-Sleep -Seconds 4
Shot 'launch-settled'
$u = Dump
$t = Texts $u
Say ("首屏文字：{0}" -f ($t -join ' ¦ '))
Say ''
$totalSmall = 0
$totalSmall += AuditTouch $u '首屏'

# ---------------------------------------------------------------- 切换场景
Say ''
Say '## 2. 场景切换（点一下之后立刻与稳定后）'
Say ''
$u = Dump
TapText $u '拿一张照片精修' | Out-Null
Shot 'tab-retouch-immediately'
Start-Sleep -Seconds 2
Shot 'tab-retouch-settled'
$u = Dump
$totalSmall += AuditTouch $u '精修场景'
$u = Dump
TapText $u '多张图合在一起' | Out-Null
Start-Sleep -Seconds 2
Shot 'tab-combine-settled'
$u = Dump
$t = Texts $u
Say ("合成场景文字：{0}" -f ($t -join ' ¦ '))

# ---------------------------------------------------------------- 弹层交互
Say ''
Say '## 3. 底部弹层（打开 / 稳定 / 关闭）'
Say ''
$u = Dump
TapText $u '拿一张照片精修' | Out-Null
Start-Sleep -Seconds 2
$u = Dump
TapText $u '选择' | Out-Null
Shot 'sheet-opening'
Start-Sleep -Seconds 2
Shot 'sheet-open'
$u = Dump
$totalSmall += AuditTouch $u '预设弹层'

# 选中一个预设，看选中态反馈
TapText $u '换背景' | Out-Null
Start-Sleep -Seconds 2
Shot 'preset-applied'

# ---------------------------------------------------------------- 键盘
Say ''
Say '## 4. 输入框聚焦 + 键盘弹出（是否遮挡主按钮）'
Say ''
$u = Dump
$d = [xml](Get-Content $u)
$edit = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
if ($edit) {
    $m = [regex]::Match($edit.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    & $adb shell input tap ([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)) ([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb shell input text 'keyboard%stest' 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Shot 'keyboard-open'
    $u = Dump
    $t = Texts $u
    Say ("键盘弹出时仍在屏幕上的文字：{0}" -f (($t | Select-Object -First 12) -join ' ¦ '))
    $d = [xml](Get-Content $u)
    $btn = $d.SelectNodes("//*[@text='开始精修']") | Select-Object -First 1
    Say ("主按钮是否仍可见：{0}" -f $(if ($btn) { "是 $($btn.bounds)" } else { '**否，被键盘挤掉**' }))
    & $adb shell input keyevent 4 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}

# ---------------------------------------------------------------- 提交与横幅
Say ''
Say '## 5. 提交 → 横幅出现 → 排队/进行中状态'
Say ''
# Use text mode: it needs no photo picker, so the banner states are reachable
# without driving the system picker.
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
$u = Dump
$d = [xml](Get-Content $u)
$edit = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
if ($edit) {
    $m = [regex]::Match($edit.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    & $adb shell input tap ([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)) ([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    & $adb shell input text 'a%ssmall%sbrass%slamp' 2>&1 | Out-Null
    & $adb shell input keyevent 4 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}
Shot 'before-submit'
$u = Dump
TapText $u '生成图片' | Out-Null
Shot 'submit-immediate'
Start-Sleep -Seconds 3
Shot 'submit-3s'
$u = Dump
$t = Texts $u
Say ("提交后文字：{0}" -f (($t | Where-Object { $_ -match '正在|已等|完成|没有成功|先' }) -join ' ¦ '))
Start-Sleep -Seconds 8
$u = Dump
$t = Texts $u
Say ("10 秒后横幅：{0}" -f (($t | Where-Object { $_ -match '正在|已等' }) -join ' ¦ '))
Shot 'submit-10s'
Say ("横幅可点区域：见下表")
$totalSmall += AuditTouch $u '任务横幅'

# ---------------------------------------------------------------- 查看器
Say ''
Say '## 6. 查看器：打开、切换、返回'
Say ''
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 6
& $adb shell input swipe 540 1900 540 800 300 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell input swipe 540 1900 540 1200 300 2>&1 | Out-Null
Start-Sleep -Seconds 2
$u = Dump
$d = [xml](Get-Content $u)
# Tiles carry the result's own note as their description, so a fixed description
# cannot find them; tap the first tile by geometry instead.
$anchor = $d.SelectNodes("//*[@text[starts-with(.,'以前做的')]]") | Select-Object -First 1
if ($anchor) {
    $m = [regex]::Match($anchor.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    $tapY = [int]$m.Groups[4].Value + 280
    & $adb shell input tap 270 $tapY 2>&1 | Out-Null
}
Shot 'viewer-opening'
Start-Sleep -Seconds 3
Shot 'viewer-result'
$u = Dump
$t = Texts $u
Say ("查看器文字：{0}" -f ($t -join ' ¦ '))
$totalSmall += AuditTouch $u '查看器'
& $adb shell input swipe 800 1200 200 1200 200 2>&1 | Out-Null
Start-Sleep -Seconds 2
Shot 'viewer-reference'
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2
Shot 'viewer-back'
$u = Dump
$t = Texts $u
Say ("返回后是否回到工作台：{0}" -f $(if ($t -contains '以前做的（4）' -or ($t | Where-Object { $_ -like '以前做的*' })) { '是' } else { "**否**：$($t | Select-Object -First 6 -join ' ')" }))

# ---------------------------------------------------------------- 设置
Say ''
Say '## 7. 设置页'
Say ''
$u = Dump
TapText $u '设置' | Out-Null
Start-Sleep -Seconds 3
Shot 'settings'
$u = Dump
$totalSmall += AuditTouch $u '设置页'
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- 极端条件
Say ''
Say '## 8. 极端条件：横屏 / 大字号'
Say ''
& $adb shell settings put system accelerometer_rotation 0 2>&1 | Out-Null
& $adb shell settings put system user_rotation 1 2>&1 | Out-Null
Start-Sleep -Seconds 3
Shot 'landscape'
$u = Dump
$t = Texts $u
Say ("横屏首屏文字：{0}" -f (($t | Select-Object -First 10) -join ' ¦ '))
& $adb shell settings put system user_rotation 0 2>&1 | Out-Null
Start-Sleep -Seconds 2

& $adb shell settings put system font_scale 1.3 2>&1 | Out-Null
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 7
Shot 'large-font-1.3'
$u = Dump
$t = Texts $u
Say ("字号 1.3 倍时首屏文字：{0}" -f (($t | Select-Object -First 12) -join ' ¦ '))
& $adb shell settings put system font_scale 1.0 2>&1 | Out-Null
& $adb shell am force-stop $pkg 2>&1 | Out-Null
& $adb shell am start -n "$pkg/.MainActivity" 2>&1 | Out-Null
Start-Sleep -Seconds 5

Say ''
Say '## 汇总'
Say ''
Say "低于 44dp 的可点区域总计：**$totalSmall 个**"
Say "帧数：$script:shotIndex"

$report -join "`n" | Set-Content -Path (Join-Path $Out 'audit.md') -Encoding utf8NoBOM
Write-Host ''
Write-Host "报告: $(Join-Path $Out 'audit.md')"
Write-Host "帧:   $frames"
