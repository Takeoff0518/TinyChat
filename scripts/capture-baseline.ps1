# 采集 TinyChat 的体验基线（P 阶段，见 docs/06-polish-stage.md §8）
#
# 为什么是引导式的：截图靠坐标点击会在改版后整份失效，而这里只要求按回车，
# 界面怎么变都不影响脚本。录屏同理——动效必须由人真的去做那个动作。
#
# 用法：
#   pwsh -File scripts/capture-baseline.ps1
#   pwsh -File scripts/capture-baseline.ps1 -Device 2TX9K24103023789 -OutDir captures\baseline-20261120
#   pwsh -File scripts/capture-baseline.ps1 -SkipMotion -SkipPerf      # 只拍界面
#
# 产物（默认 captures\baseline-<时间>\，已 gitignore）：
#   界面截图 18 张（9 个界面 x 浅色/深色）、动效录屏 4 段、gfxinfo 原始 dump、manifest.txt

[CmdletBinding()]
param(
    [string]$Device = '2TX9K24103023789',
    [string]$OutDir,
    [switch]$SkipScreens,
    [switch]$SkipMotion,
    [switch]$SkipPerf
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$buildConfig = Get-Content -Raw -LiteralPath (Join-Path $RepoRoot 'app/build.gradle.kts')
if ($buildConfig -notmatch '(?m)^\s*applicationId\s*=\s*"([^"]+)"') {
    throw '无法从构建配置读取应用标识。'
}
$Package = $Matches[1]

if (-not $OutDir) {
    $OutDir = Join-Path $RepoRoot ('captures\baseline-' + (Get-Date -Format 'yyyyMMdd-HHmm'))
}
elseif (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $RepoRoot $OutDir
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFail)
    $output = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) {
        throw "adb $($Arguments -join ' ') 失败：`n$output"
    }
    return $output
}

function Read-Hint {
    param([string]$Text)
    Write-Host ''
    Write-Host "  $Text" -ForegroundColor Cyan
    [void](Read-Host '  做完按回车继续')
}

function Save-Screenshot {
    param([string]$Path)
    # 必须走 cmd 的重定向：PowerShell 的 > 会把二进制当文本编码，截出来的图是坏的
    cmd /c "adb -s $Device exec-out screencap -p > `"$Path`""
    if (-not (Test-Path $Path) -or (Get-Item $Path).Length -lt 1000) {
        throw "截图失败或文件过小：$Path"
    }
    Write-Host "  [截图] $(Split-Path -Leaf $Path)  $([math]::Round((Get-Item $Path).Length / 1KB)) KB" -ForegroundColor DarkGray
}

function Save-Recording {
    param([string]$Name, [int]$Seconds, [string]$Hint)
    $remote = "/sdcard/$Name.mp4"
    $p = Start-Process -FilePath 'adb' -NoNewWindow -PassThru -ArgumentList @(
        '-s', $Device, 'shell', 'screenrecord', '--bit-rate', '8000000',
        '--time-limit', "$Seconds", $remote
    )
    Read-Hint $Hint
    [void]$p.WaitForExit(($Seconds + 15) * 1000)
    $local = Join-Path $OutDir "$Name.mp4"
    [void](Invoke-Adb @('pull', $remote, $local) -AllowFail)
    [void](Invoke-Adb @('shell', 'rm', '-f', $remote) -AllowFail)
    if (Test-Path $local) {
        Write-Host "  [录屏] $Name.mp4  $([math]::Round((Get-Item $local).Length / 1MB, 1)) MB" -ForegroundColor DarkGray
    }
    else {
        Write-Warning "录屏没取回来：$Name"
    }
}

# ---------------------------------------------------------------- 准备

Write-Host '检查设备…'
$devices = & adb devices
if (-not ($devices -match "$Device\s+device")) {
    throw "设备 $Device 不在线。先 `adb devices` 看一眼，或用 -Device 指定正确的序列号。"
}

$resolved = Invoke-Adb @('shell', 'cmd', 'package', 'resolve-activity', '--brief', $Package) | Out-String
$launchComponent = ($resolved -split "`n" | Where-Object { $_ -match '/' } | Select-Object -First 1)
if ($launchComponent) { $launchComponent = $launchComponent.Trim() } else { $launchComponent = "$Package/.MainActivity" }

if (-not (Test-Path $OutDir)) { [void](New-Item -ItemType Directory -Path $OutDir -Force) }
Write-Host "产物目录：$OutDir" -ForegroundColor Green
Write-Host "启动组件：$launchComponent" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 1. 界面截图

$screens = @(
    @{ key = '01-sessions';  hint = '会话列表（回到首页，抽屉收起）' },
    @{ key = '02-chat';      hint = '聊天页：一个含长回答、代码块与表格的会话，停在中间' },
    @{ key = '03-drawer';    hint = '打开左侧会话抽屉' },
    @{ key = '04-model';     hint = '点顶栏模型胶囊，展开模型选择' },
    @{ key = '05-settings';  hint = '设置首页（从抽屉进设置）' },
    @{ key = '06-usage';     hint = '打开右侧「用法与费用」抽屉' },
    @{ key = '07-providers'; hint = '设置 → 服务商与 API Key 列表' },
    @{ key = '08-about';     hint = '设置 → 关于' },
    @{ key = '09-input';     hint = '回到聊天页，点输入框让键盘弹起' }
)

$modes = @(
    @{ key = 'light'; hint = '浅色' },
    @{ key = 'dark';  hint = '深色' }
)

if (-not $SkipScreens) {
    Write-Host ''
    Write-Host '=== 第一部分：界面截图（9 个界面 x 浅色/深色）===' -ForegroundColor Yellow

    foreach ($mode in $modes) {
        Write-Host ''
        Write-Host "先把系统与应用的界面切到「$($mode.hint)」：设置 → 外观。" -ForegroundColor Yellow
        Read-Hint "外观切到「$($mode.hint)」后，按回车开始这一组"

        foreach ($screen in $screens) {
            Read-Hint "切到：$($screen.hint)"
            Save-Screenshot (Join-Path $OutDir ("{0}-{1}.png" -f $screen.key, $mode.key))
        }
    }

    # 键盘收起，别把键盘状态留给后面的录屏
    [void](Invoke-Adb @('shell', 'input', 'keyevent', '4') -AllowFail)
}

# ---------------------------------------------------------------- 2. 动效录屏

if (-not $SkipMotion) {
    Write-Host ''
    Write-Host '=== 第二部分：动效录屏（每段 8 秒，脚本开始录之后再做动作）===' -ForegroundColor Yellow

    Save-Recording -Name 'm1-drawer' -Seconds 8 -Hint '录屏开始后：在聊天页打开抽屉、等它停住、再关掉。这一段录正常速度；慢放版本等看完基线再补'
    Save-Recording -Name 'm2-actionbar' -Seconds 8 -Hint '录屏开始后：发一句短问，等回答写完，让操作栏出现；再点「重新生成」，让操作栏消失'
    Save-Recording -Name 'm3-codeblock' -Seconds 10 -Hint '录屏开始后：打开一个长代码块，点「展开全部」，再点「收起」，来回两次'
    Save-Recording -Name 'm4-firsttoken' -Seconds 12 -Hint '录屏开始后：发一个问题，把首字出现到正文铺满两屏的过程录下来'
}

# ---------------------------------------------------------------- 3. 冷启动

$coldStarts = @()
if (-not $SkipPerf) {
    Write-Host ''
    Write-Host '=== 第三部分：冷启动耗时（三次）===' -ForegroundColor Yellow

    for ($i = 1; $i -le 3; $i++) {
        [void](Invoke-Adb @('shell', 'am', 'force-stop', $Package) -AllowFail)
        Start-Sleep -Milliseconds 1200
        $out = (Invoke-Adb @('shell', 'am', 'start', '-S', '-W', '-n', $launchComponent) -AllowFail) | Out-String
        $m = [regex]::Match($out, 'TotalTime:\s*(\d+)')
        if ($m.Success) {
            $coldStarts += [int]$m.Groups[1].Value
            Write-Host "  第 $i 次 TotalTime = $($m.Groups[1].Value) ms" -ForegroundColor DarkGray
        }
        else {
            Write-Warning "第 $i 次没解析到 TotalTime：`n$out"
        }
        Start-Sleep -Milliseconds 1500
    }
}
else {
    Write-Host '跳过冷启动与帧率采集。' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 4. 滑动帧率

$gfxSummary = ''
if (-not $SkipPerf) {
    Write-Host ''
    Write-Host '=== 第四部分：聊天页滑动帧率 ===' -ForegroundColor Yellow

    [void](Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package, 'reset') -AllowFail)
    Read-Hint '进入一个长会话，用一次流畅的上滑把列表滚到底，再滑回来（大约十秒）'
    $dump = (Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package) -AllowFail) | Out-String
    $dumpPath = Join-Path $OutDir 'gfxinfo.txt'
    [System.IO.File]::WriteAllText($dumpPath, $dump, (New-Object System.Text.UTF8Encoding($false)))

    $wanted = @('Total frames rendered', 'Janky frames', 'Number Missed Vsync', '50th percentile', '90th percentile', '95th percentile', '99th percentile')
    $lines = @()
    foreach ($line in ($dump -split "`n")) {
        foreach ($w in $wanted) {
            if ($line -match [regex]::Escape($w)) { $lines += $line.Trim() }
        }
    }
    $gfxSummary = ($lines -join "`n")
    Write-Host $gfxSummary -ForegroundColor DarkGray
    Write-Host "  原始 dump：$dumpPath" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- manifest

$prop = {
    param([string]$Name)
    (Invoke-Adb @('shell', 'getprop', $Name) -AllowFail | Out-String).Trim()
}

$versionName = (Invoke-Adb @('shell', 'dumpsys', 'package', $Package) -AllowFail | Out-String)
$versionMatch = [regex]::Match($versionName, 'versionName=(\S+)')
$refresh = (Invoke-Adb @('shell', 'dumpsys', 'display') -AllowFail | Out-String)
$refreshMatch = [regex]::Match($refresh, 'mRefreshRate[^\r\n]*')
$size = (Invoke-Adb @('shell', 'wm', 'size') -AllowFail | Out-String).Trim()
$density = (Invoke-Adb @('shell', 'wm', 'density') -AllowFail | Out-String).Trim()

$median = ''
if ($coldStarts.Count -gt 0) {
    $sorted = $coldStarts | Sort-Object
    $median = $sorted[[int]([math]::Floor($sorted.Count / 2))]
}

$manifest = @(
    "采集时间      $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
    "设备序列号    $Device"
    "机型          $(& $prop 'ro.product.model')"
    "系统版本      Android $(& $prop 'ro.build.version.release') (SDK $(& $prop 'ro.build.version.sdk'))"
    "屏幕          $size"
    "密度          $density"
    "刷新率        $($refreshMatch.Value)"
    "应用版本      $($versionMatch.Groups[1].Value)"
    "启动组件      $launchComponent"
    "冷启动        $($coldStarts -join ' / ') ms，中位数 $median ms"
    ''
    '滑动帧率：'
    $gfxSummary
) -join "`n"

[System.IO.File]::WriteAllText((Join-Path $OutDir 'manifest.txt'), $manifest, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "基线采集完成：$OutDir" -ForegroundColor Green
Write-Host $manifest -ForegroundColor DarkGray
