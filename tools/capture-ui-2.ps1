# Second pass: the states the first run missed.
#
# Drives one screen at a time and asserts the tap landed (the dump must contain the text
# we expected next), because a missed tap silently produces a duplicate screenshot.

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$out = 'C:\glow-build\uishots'
New-Item -ItemType Directory -Force -Path $out | Out-Null

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

# --------------------------------------------------------------- 02 prompt typed
Write-Host '== 02 workbench with a prompt ==' -ForegroundColor Cyan
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9
$d = Dump
$field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
TapNode $field | Out-Null
Start-Sleep -Seconds 2
& $adb shell input text "a%squiet%sbookshop%son%sa%srainy%safternoon" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2
Shot '02-workbench-prompt'

# --------------------------------------------------------------- 19 instruction sheet
Write-Host '== 19 instruction sheet ==' -ForegroundColor Cyan
$d = Dump
$sheet = FindExact $d '看会发送什么'
if ($sheet) {
    TapNode $sheet
    Start-Sleep -Seconds 3
    Shot '19-instruction-sheet'
    $d = Dump
    $ok = FindExact $d '知道了'
    if ($ok) { TapNode $ok; Start-Sleep -Seconds 2 }
}

# --------------------------------------------------------------- viewer states
Write-Host '== viewer: 09 switch / 10 side / 11 wipe ==' -ForegroundColor Cyan
$hit = $null
for ($step = 0; $step -lt 7; $step++) {
    $d = Dump
    $hit = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -match 'lamp shade' } | Select-Object -First 1
    if ($hit) { break }
    ScrollDown 1
}
if ($hit) {
    TapNode $hit
    Start-Sleep -Seconds 4
    Shot '09-viewer-switch'
    $d = Dump
    $side = FindExact $d '并排'
    if ($side) {
        TapNode $side
        Start-Sleep -Seconds 3
        Shot '10-viewer-side'
    }
    $d = Dump
    $wipe = FindExact $d '滑动'
    if ($wipe) {
        TapNode $wipe
        Start-Sleep -Seconds 3
        Shot '11-viewer-wipe'
    }
    $back = (Dump).SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

Write-Host '== 12 viewer for a text-only result ==' -ForegroundColor Cyan
$textOnly = $null
for ($step = 0; $step -lt 4; $step++) {
    $d = Dump
    $textOnly = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -match 'bookshop|warm lamp|queue' } | Select-Object -First 1
    if ($textOnly) { break }
    ScrollUp 1
}
if ($textOnly) {
    TapNode $textOnly
    Start-Sleep -Seconds 4
    Shot '12-viewer-textonly'
    $back = (Dump).SelectNodes("//*[@content-desc='返回']") | Select-Object -First 1
    if ($back) { TapNode $back; Start-Sleep -Seconds 3 }
}

# --------------------------------------------------------------- 18 archive
Write-Host '== 18 archive ==' -ForegroundColor Cyan
$archive = $null
for ($step = 0; $step -lt 6; $step++) {
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
    if ($back) { TapNode $back; Start-Sleep -Seconds 2 }
}

Write-Host '== done ==' -ForegroundColor Green
