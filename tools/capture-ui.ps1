# Capture every main surface of the Glow app from a running emulator.
#
# Written as a script rather than hand-driven adb so the set is reproducible: the same
# order, the same states, the same file names. Screenshots land in C:\glow-build\uishots.
#
# The app is driven by uiautomator dumps (find the node with a given text, tap its
# centre), which is slower than fixed coordinates but survives layout changes.

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$out = 'C:\glow-build\uishots'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Dump {
    for ($i = 0; $i -lt 8; $i++) {
        Start-Sleep -Milliseconds 250
        $p = Join-Path $env:TEMP ("ui" + [guid]::NewGuid().ToString('N').Substring(0, 6) + ".xml")
        & $adb shell rm -f /sdcard/ui.xml 2>&1 | Out-Null
        & $adb shell uiautomator dump /sdcard/ui.xml 2>&1 | Out-Null
        & $adb pull /sdcard/ui.xml $p 2>&1 | Out-Null
        if ((Test-Path $p) -and ((Get-Item $p).Length -gt 200)) {
            try {
                $doc = [xml][System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
                if ($doc.SelectNodes("//*[@text!='']").Count -gt 0) { return $doc }
            } catch { }
        }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function TapNode($node) {
    if ($null -eq $node) { return $false }
    $m = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $false }
    $x = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
    $y = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    & $adb shell input tap $x $y 2>&1 | Out-Null
    return $true
}

function FindText($doc, $text, $startsWith = $false) {
    if ($null -eq $doc) { return $null }
    if ($startsWith) {
        return ($doc.SelectNodes("//*[@text!='']") | Where-Object { $_.text.StartsWith($text) } | Select-Object -First 1)
    }
    return $doc.SelectNodes("//*[@text='$text']") | Select-Object -First 1
}

function Shot($name) {
    $path = Join-Path $out "$name.png"
    # cmd.exe instead of PowerShell's ">": in Windows PowerShell 5.1 a redirect decodes
    # the native output as text and silently corrupts the PNG.
    & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    $ok = $false
    if (Test-Path $path) {
        $bytes = [System.IO.File]::ReadAllBytes($path)
        $ok = $bytes.Length -gt 1000 -and $bytes[0] -eq 0x89 -and $bytes[1] -eq 0x50
    }
    if (-not $ok) {
        Start-Sleep -Seconds 1
        & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    }
    Write-Host ("  shot {0} ({1} KB)" -f $name, [math]::Round((Get-Item $path).Length / 1KB)) -ForegroundColor DarkGray
}

function ScrollDown($times = 1) {
    for ($i = 0; $i -lt $times; $i++) {
        & $adb shell input swipe 540 1900 540 900 300 2>&1 | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function ScrollUp($times = 1) {
    for ($i = 0; $i -lt $times; $i++) {
        & $adb shell input swipe 540 900 540 1900 300 2>&1 | Out-Null
        Start-Sleep -Milliseconds 900
    }
}

function AddReferenceImage {
    # Opens the system photo picker and takes the first photo.
    $d = Dump
    $add = FindText $d '照片'
    if (-not $add) { return $false }
    TapNode $add
    Start-Sleep -Seconds 4
    & $adb shell input tap 177 817 2>&1 | Out-Null     # first thumbnail
    Start-Sleep -Seconds 2
    $d = Dump
    $confirm = $null
    if ($d) {
        $confirm = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -like 'Add*' } | Select-Object -First 1
    }
    if ($confirm) { TapNode $confirm } else { & $adb shell input keyevent 4 2>&1 | Out-Null }
    Start-Sleep -Seconds 3
    return $true
}

Write-Host '== reset to a clean start ==' -ForegroundColor Cyan
& $adb shell cmd uimode night no 2>&1 | Out-Null
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9

Write-Host '== 01 workbench, empty text ==' -ForegroundColor Cyan
Shot '01-workbench-empty'

Write-Host '== 02 workbench, with a prompt ==' -ForegroundColor Cyan
$d = Dump
$field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
TapNode $field
Start-Sleep -Seconds 2
& $adb shell input text "a%squiet%sbookshop%son%sa%srainy%safternoon" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2

Write-Host '== 03 retouch scenario with a reference ==' -ForegroundColor Cyan
$d = Dump
TapNode (FindText $d '照片精修')
Start-Sleep -Seconds 3
AddReferenceImage | Out-Null
Start-Sleep -Seconds 2
Shot '03-workbench-retouch'

Write-Host '== 04 combine scenario ==' -ForegroundColor Cyan
$d = Dump
TapNode (FindText $d '多图合成')
Start-Sleep -Seconds 3
AddReferenceImage | Out-Null
Start-Sleep -Seconds 2
Shot '04-workbench-combine'

Write-Host '== 05 workbench scrolled (size grid + gallery) ==' -ForegroundColor Cyan
$d = Dump
TapNode (FindText $d '一句话出图')
Start-Sleep -Seconds 2
ScrollDown 2
Shot '05-workbench-lower'

Write-Host '== 06/07 job banner: running then done ==' -ForegroundColor Cyan
$d = Dump
$chip = FindText $d '小图试手'
if ($chip) { TapNode $chip.ParentNode; Start-Sleep -Seconds 2 }
$d = Dump
$field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
TapNode $field
Start-Sleep -Seconds 2
& $adb shell input text "warm%slamp%sin%sa%squiet%sroom" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2
ScrollUp 2
$d = Dump
$go = FindText $d '生成图片'
if ($go) { TapNode $go }
Start-Sleep -Seconds 9
Shot '06-job-running'
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 4
    $d = Dump
    $done = FindText $d '知道了'
    if ($done) { break }
}
Shot '07-job-done'

Write-Host '== 08 gallery grid ==' -ForegroundColor Cyan
ScrollDown 3
Shot '08-gallery'

Write-Host '== 09/10/11 viewer: switch, side, wipe ==' -ForegroundColor Cyan
$opened = $false
for ($step = 0; $step -lt 6; $step++) {
    $d = Dump
    $hit = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -match 'lamp shade' } | Select-Object -First 1
    if ($hit) { TapNode $hit; $opened = $true; break }
    ScrollDown 1
}
if ($opened) {
    Start-Sleep -Seconds 4
    Shot '09-viewer-switch'
    $d = Dump
    $side = FindText $d '并排'
    if ($side) { TapNode $side; Start-Sleep -Seconds 3; Shot '10-viewer-side'; $d = Dump }
    $wipe = FindText $d '滑动'
    if ($wipe) { TapNode $wipe; Start-Sleep -Seconds 3; Shot '11-viewer-wipe'; $d = Dump }
    $back = $d.SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

Write-Host '== 12 viewer for a text-only result ==' -ForegroundColor Cyan
$d = Dump
$textOnly = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -match 'warm lamp|queue banner|clock fixed' } | Select-Object -First 1
if ($textOnly) {
    TapNode $textOnly
    Start-Sleep -Seconds 4
    Shot '12-viewer-textonly'
    $d = Dump
    $back = $d.SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

Write-Host '== 13/14/15 settings ==' -ForegroundColor Cyan
$d = Dump
$set = FindText $d '设置'
if ($set) { TapNode $set; Start-Sleep -Seconds 3 }
Shot '13-settings-top'
$d = Dump
$adv = FindText $d '高级'
if (-not $adv) { ScrollDown 1; $d = Dump; $adv = FindText $d '高级' }
if ($adv) { TapNode $adv; Start-Sleep -Seconds 2 }
Shot '14-settings-advanced'
ScrollDown 3
Shot '15-settings-bottom'

Write-Host '== 16 settings save feedback (failing address) ==' -ForegroundColor Cyan
ScrollUp 3
$d = Dump
$field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
if ($field) {
    TapNode $field
    Start-Sleep -Seconds 2
    & $adb shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
    for ($i = 0; $i -lt 40; $i++) { & $adb shell input keyevent 67 2>&1 | Out-Null }
    & $adb shell input text "http://10.255.255.1:9742" 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    & $adb shell input keyevent 4 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    ScrollDown 1
    $d = Dump
    $save = FindText $d '保存并生效'
    if ($save) { TapNode $save; Start-Sleep -Seconds 3; Shot '16-settings-save-probing' }
    Start-Sleep -Seconds 18
    Shot '17-settings-save-failed'
    # put the working address back and save again
    ScrollUp 1
    $d = Dump
    $field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
    if ($field) {
        TapNode $field
        Start-Sleep -Seconds 2
        & $adb shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
        for ($i = 0; $i -lt 40; $i++) { & $adb shell input keyevent 67 2>&1 | Out-Null }
        & $adb shell input text "http://100.99.63.62:9742" 2>&1 | Out-Null
        & $adb shell input keyevent 4 2>&1 | Out-Null
        Start-Sleep -Seconds 1
        ScrollDown 1
        $d = Dump
        $save = FindText $d '保存并生效'
        if ($save) { TapNode $save }
        Start-Sleep -Seconds 8
    }
}

Write-Host '== 18 archive ==' -ForegroundColor Cyan
$d = Dump
$back = $d.SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
if (-not $back) { $back = FindText $d '返回' }
if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
ScrollDown 4
$d = Dump
$archive = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -like '归档箱*' } | Select-Object -First 1
if ($archive) { TapNode $archive; Start-Sleep -Seconds 3; Shot '18-archive'; $d = Dump; $back = $d.SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1; if ($back) { TapNode $back; Start-Sleep -Seconds 2 } }

Write-Host '== 19 instruction sheet ==' -ForegroundColor Cyan
ScrollUp 3
$d = Dump
$sheet = FindText $d '看会发送什么'
if ($sheet) { TapNode $sheet; Start-Sleep -Seconds 3; Shot '19-instruction-sheet'; $d = Dump; $ok = FindText $d '知道了'; if ($ok) { TapNode $ok; Start-Sleep -Seconds 2 } }

Write-Host '== dark mode: 20/21/22 ==' -ForegroundColor Cyan
& $adb shell cmd uimode night yes 2>&1 | Out-Null
Start-Sleep -Seconds 4
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9
Shot '20-dark-workbench'
$d = Dump
$hit = $null
for ($step = 0; $step -lt 5; $step++) {
    $d = Dump
    $hit = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -match 'lamp shade' } | Select-Object -First 1
    if ($hit) { break }
    ScrollDown 1
}
if ($hit) {
    TapNode $hit
    Start-Sleep -Seconds 4
    Shot '21-dark-viewer'
    $back = (Dump).SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 2 }
}
$d = Dump
$set = FindText $d '设置'
if ($set) { TapNode $set; Start-Sleep -Seconds 3; Shot '22-dark-settings' }
& $adb shell cmd uimode night no 2>&1 | Out-Null

Write-Host '== done ==' -ForegroundColor Green
Get-ChildItem $out -Filter '*.png' | Sort-Object Name | ForEach-Object { Write-Host ("  {0}  {1} KB" -f $_.Name, [math]::Round($_.Length / 1KB)) }
