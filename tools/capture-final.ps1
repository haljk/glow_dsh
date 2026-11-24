# Capture the redesigned surfaces for the aesthetic review.
#
# Selectors follow the new copy: the size grid is gone, so screens are reached by the
# labels that exist now (输出, 全部 ›, 扫码配对, 高级).

$ErrorActionPreference = 'Continue'
$adb = 'C:\Android\Sdk\platform-tools\adb.exe'
$out = 'C:\glow-build\final'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Dump {
    for ($i = 0; $i -lt 8; $i++) {
        Start-Sleep -Milliseconds 300
        $p = Join-Path $env:TEMP ("d" + [guid]::NewGuid().ToString('N').Substring(0, 6) + ".xml")
        & $adb shell rm -f /sdcard/d.xml 2>&1 | Out-Null
        & $adb shell uiautomator dump /sdcard/d.xml 2>&1 | Out-Null
        & $adb pull /sdcard/d.xml $p 2>&1 | Out-Null
        if ((Test-Path $p) -and ((Get-Item $p).Length -gt 200)) {
            try {
                $doc = [xml][System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
                if ($doc.SelectNodes("//*[@text!='']").Count -gt 0) { return $doc }
            } catch { }
        }
    }
    return $null
}

function TapText([string]$text) {
    $d = Dump
    if (-not $d) { return $false }
    $n = $d.SelectNodes("//*[@text='$text']") | Select-Object -First 1
    if (-not $n) { $n = $d.SelectNodes("//*[@content-desc='$text']") | Select-Object -First 1 }
    if (-not $n) { return $false }
    $m = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $false }
    & $adb shell input tap ([int]((([int]$m.Groups[1].Value) + ([int]$m.Groups[3].Value)) / 2)) ([int]((([int]$m.Groups[2].Value) + ([int]$m.Groups[4].Value)) / 2)) 2>&1 | Out-Null
    return $true
}

function Shot([string]$name) {
    $path = Join-Path $out "$name.png"
    & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    Write-Host ("  shot {0} ({1} KB)" -f $name, [math]::Round((Get-Item $path).Length / 1KB)) -ForegroundColor DarkGray
}

& $adb shell svc power stayon true 2>&1 | Out-Null
& $adb shell cmd uimode night no 2>&1 | Out-Null
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9

Shot '01-workbench'

# 输出设置弹层
if (TapText '输出') { Start-Sleep -Seconds 2; Shot '02-output-sheet'; & $adb shell input keyevent 4 2>&1 | Out-Null; Start-Sleep -Seconds 1 }

# 全部作品 + 多选
if (TapText '全部 ›') {
    Start-Sleep -Seconds 3
    Shot '03-library'
    & $adb shell input swipe 260 1500 260 1500 900 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Shot '04-selecting'
    if (TapText '退出多选') { Start-Sleep -Seconds 1 }
    if (TapText '返回') { Start-Sleep -Seconds 2 }
}

# 设置主区 / 高级 / 二维码
if (TapText '设置') {
    Start-Sleep -Seconds 3
    Shot '05-settings-main'
    if (TapText '高级') { Start-Sleep -Seconds 2; Shot '06-settings-advanced' }
    if (TapText '生成配置二维码') { Start-Sleep -Seconds 4; Shot '07-pairing'; if (TapText '关闭') { Start-Sleep -Seconds 1 } }
    if (TapText '返回') { Start-Sleep -Seconds 2 }
}

Write-Host '== done ==' -ForegroundColor Green
