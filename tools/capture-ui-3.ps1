# Third pass: archive, the save-feedback pair, and the two dark-mode surfaces.

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$out = 'C:\glow-build\uishots'

function Dump {
    for ($i = 0; $i -lt 10; $i++) {
        Start-Sleep -Milliseconds 300
        $p = Join-Path $env:TEMP ("u" + [guid]::NewGuid().ToString('N').Substring(0, 6) + ".xml")
        & $adb shell rm -f /sdcard/u.xml 2>&1 | Out-Null
        & $adb shell uiautomator dump /sdcard/u.xml 2>&1 | Out-Null
        & $adb pull /sdcard/u.xml $p 2>&1 | Out-Null
        if ((Test-Path $p) -and ((Get-Item $p).Length -gt 200)) {
            try {
                $doc = [xml][System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
                if ($doc.SelectNodes("//*[@text!='']").Count -gt 0) { return $doc }
            } catch { }
        }
    }
    return $null
}

function TapNode($node) {
    if ($null -eq $node) { return $false }
    $m = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $false }
    & $adb shell input tap ([int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)) ([int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)) 2>&1 | Out-Null
    return $true
}

function FindExact($doc, $text) {
    if ($null -eq $doc) { return $null }
    return $doc.SelectNodes("//*[@text='$text']") | Select-Object -First 1
}

function FindPrefix($doc, $text) {
    if ($null -eq $doc) { return $null }
    return $doc.SelectNodes("//*[@text!='']") | Where-Object { $_.text.StartsWith($text) } | Select-Object -First 1
}

function Shot($name) {
    $path = Join-Path $out "$name.png"
    & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if (-not ($bytes.Length -gt 1000 -and $bytes[0] -eq 0x89)) {
        Start-Sleep -Seconds 1
        & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    }
    Write-Host ("  shot {0} ({1} KB)" -f $name, [math]::Round((Get-Item $path).Length / 1KB)) -ForegroundColor DarkGray
}

function ScrollDown($n = 1) { for ($i = 0; $i -lt $n; $i++) { & $adb shell input swipe 540 1900 540 900 300 2>&1 | Out-Null; Start-Sleep -Milliseconds 900 } }
function ScrollUp($n = 1) { for ($i = 0; $i -lt $n; $i++) { & $adb shell input swipe 540 900 540 1900 300 2>&1 | Out-Null; Start-Sleep -Milliseconds 900 } }

& $adb shell svc power stayon true 2>&1 | Out-Null
& $adb shell cmd uimode night no 2>&1 | Out-Null
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9

# ------------------------------------------------------------------ 18 archive
Write-Host '== 18 archive ==' -ForegroundColor Cyan
$archive = $null
for ($step = 0; $step -lt 8; $step++) {
    $d = Dump
    $archive = FindPrefix $d '归档箱'
    if ($archive) { break }
    ScrollDown 1
}
if ($archive) {
    TapNode $archive
    Start-Sleep -Seconds 3
    Shot '18-archive'
    $back = (Dump).SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

# ------------------------------------------------------------------ 13..17 settings
Write-Host '== settings: top / advanced / bottom ==' -ForegroundColor Cyan
ScrollUp 6
$d = Dump
$set = FindExact $d '设置'
if ($set) { TapNode $set; Start-Sleep -Seconds 3 }
Shot '13-settings-top'
$d = Dump
$adv = FindExact $d '高级'
if (-not $adv) { ScrollDown 1; $d = Dump; $adv = FindExact $d '高级' }
if ($adv) { TapNode $adv; Start-Sleep -Seconds 2 }
Shot '14-settings-advanced'
ScrollDown 3
Shot '15-settings-bottom'

Write-Host '== 16/17 save feedback ==' -ForegroundColor Cyan
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
    $save = FindExact $d '保存并生效'
    if ($save) {
        TapNode $save
        Start-Sleep -Seconds 2
        Shot '16-settings-save-probing'
    }
    Start-Sleep -Seconds 18
    Shot '17-settings-save-failed'
    # restore the working address
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
        $save = FindExact $d '保存并生效'
        if ($save) { TapNode $save }
        Start-Sleep -Seconds 8
    }
}

# ------------------------------------------------------------------ dark mode
Write-Host '== dark: 20 workbench / 21 viewer / 22 settings ==' -ForegroundColor Cyan
& $adb shell cmd uimode night yes 2>&1 | Out-Null
Start-Sleep -Seconds 4
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9
Shot '20-dark-workbench'

$hit = $null
for ($step = 0; $step -lt 6; $step++) {
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
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

$d = Dump
$set = FindExact $d '设置'
if ($set) {
    TapNode $set
    Start-Sleep -Seconds 3
    Shot '22-dark-settings'
    $back = (Dump).SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 2 }
}
& $adb shell cmd uimode night no 2>&1 | Out-Null

Write-Host '== done ==' -ForegroundColor Green
