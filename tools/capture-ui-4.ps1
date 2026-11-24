# Fourth pass: the two reference scenarios and the job banner.
#
# Every step asserts what the screen should show before the shutter, because pass 1
# produced screenshots of the wrong state: a tap that misses leaves a valid PNG of the
# previous screen, and nothing in the file name reveals it.

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

function HasText($doc, $needle) {
    if ($null -eq $doc) { return $false }
    return ($doc.SelectNodes("//*[@text!='']") | Where-Object { $_.text -like "*$needle*" } | Measure-Object).Count -gt 0
}

function Shot($name) {
    $path = Join-Path $out "$name.png"
    & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$path`"" 2>&1 | Out-Null
    Write-Host ("  shot {0} ({1} KB)" -f $name, [math]::Round((Get-Item $path).Length / 1KB)) -ForegroundColor DarkGray
}

function WaitFor($needle, $tries = 10) {
    for ($i = 0; $i -lt $tries; $i++) {
        $d = Dump
        if (HasText $d $needle) { return $d }
        Start-Sleep -Milliseconds 700
    }
    return $null
}

function AddRef {
    $d = WaitFor '照片' 6
    $add = $null
    if ($d) { $add = $d.SelectNodes("//*[@text='照片']") | Select-Object -Last 1 }
    if (-not $add) { Write-Host '  ! 没找到「＋照片」' -ForegroundColor Red; return $false }
    TapNode $add
    Start-Sleep -Seconds 4
    # system photo picker: first thumbnail, then Add
    & $adb shell input tap 177 817 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    $d = Dump
    $confirm = $null
    if ($d) { $confirm = $d.SelectNodes("//*[@text!='']") | Where-Object { $_.text -like 'Add*' } | Select-Object -First 1 }
    if ($confirm) { TapNode $confirm } else { & $adb shell input keyevent 4 2>&1 | Out-Null }
    Start-Sleep -Seconds 3
    $d = WaitFor '作为' 8
    if (-not $d) { Write-Host '  ! 参考图没加上' -ForegroundColor Red; return $false }
    return $true
}

& $adb shell svc power stayon true 2>&1 | Out-Null
& $adb shell am force-stop dev.haljk.glow 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb shell am start -n dev.haljk.glow/.MainActivity 2>&1 | Out-Null
Start-Sleep -Seconds 9

# ------------------------------------------------------------------ 03 retouch
Write-Host '== 03 retouch with a reference ==' -ForegroundColor Cyan
$d = WaitFor '照片精修' 6
TapNode (FindExact $d '照片精修')
$d = WaitFor '选一张照片' 8
if ($d) {
    AddRef | Out-Null
    Start-Sleep -Seconds 2
    $d = Dump
    if (HasText $d '作为') { Shot '03-workbench-retouch' }
    else { Write-Host '  ! 03 状态不对，未截图' -ForegroundColor Red }
} else {
    Write-Host '  ! 没能切到精修场景' -ForegroundColor Red
}

# ------------------------------------------------------------------ 04 combine
Write-Host '== 04 combine with references ==' -ForegroundColor Cyan
$d = WaitFor '多图合成' 6
TapNode (FindExact $d '多图合成')
$d = WaitFor '最多 2 张' 8
if ($d) {
    AddRef | Out-Null
    Start-Sleep -Seconds 2
    $d = Dump
    if (HasText $d '作为') { Shot '04-workbench-combine' }
    else { Write-Host '  ! 04 状态不对，未截图' -ForegroundColor Red }
} else {
    Write-Host '  ! 没能切到合成场景' -ForegroundColor Red
}

# ------------------------------------------------------------------ 06/07 job banner
Write-Host '== 06/07 job banner ==' -ForegroundColor Cyan
$d = WaitFor '一句话出图' 6
TapNode (FindExact $d '一句话出图')
Start-Sleep -Seconds 2
$d = Dump
$field = $d.SelectNodes("//*[@class='android.widget.EditText']") | Select-Object -First 1
TapNode $field
Start-Sleep -Seconds 2
& $adb shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
for ($i = 0; $i -lt 50; $i++) { & $adb shell input keyevent 67 2>&1 | Out-Null }
& $adb shell input text "warm%slamp%sin%sa%squiet%sroom" 2>&1 | Out-Null
Start-Sleep -Seconds 1
& $adb shell input keyevent 4 2>&1 | Out-Null
Start-Sleep -Seconds 2
$d = WaitFor '小图试手' 6
if ($d) { TapNode ($d.SelectNodes("//*[@text='小图试手']") | Select-Object -First 1 | ForEach-Object { $_.ParentNode }) }
Start-Sleep -Seconds 2
$d = WaitFor '生成图片' 6
TapNode (FindExact $d '生成图片')
$d = WaitFor '正在生成' 8
if (-not $d) { $d = WaitFor '排队中' 6 }
if ($d) {
    Shot '06-job-running'
    Write-Host '  横幅已出现' -ForegroundColor DarkGray
} else {
    Write-Host '  ! 横幅没出现' -ForegroundColor Red
}
$d = WaitFor '知道了' 60
if ($d) { Shot '07-job-done' } else { Write-Host '  ! 任务未结束' -ForegroundColor Red }

Write-Host '== done ==' -ForegroundColor Green
