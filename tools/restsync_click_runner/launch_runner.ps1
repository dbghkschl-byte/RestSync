$ErrorActionPreference = 'Stop'
$runnerRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverPath = Join-Path $runnerRoot 'server.py'

function Test-RestSyncRunner {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 1
        return ($health.ok -eq $true -and $health.service -eq 'restsync-emulator-click-runner')
    } catch { return $false }
}

if (Test-RestSyncRunner) {
    Write-Host 'RestSync 자동 클릭 실행기가 이미 켜져 있습니다.'
    exit 0
}

function Get-RestSyncPythonExecutable([string]$candidate, [bool]$launcher = $false) {
    if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $null }
    # Windows Store execution aliases can launch an installer; never probe those.
    $aliases = Join-Path $env:LOCALAPPDATA 'Microsoft/WindowsApps'
    if ($candidate.StartsWith($aliases, [System.StringComparison]::OrdinalIgnoreCase)) { return $null }
    $probe = $null
    try {
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $candidate
        $info.Arguments = $(if ($launcher) { '-3 ' } else { '' }) + '-c "import sys; print(sys.executable)"'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $probe = [System.Diagnostics.Process]::Start($info)
        if (-not $probe.WaitForExit(3000)) { $probe.Kill(); return $null }
        $detected = $probe.StandardOutput.ReadToEnd().Trim()
        if ($probe.ExitCode -eq 0 -and $detected -and (Test-Path -LiteralPath $detected -PathType Leaf)) { return $detected }
    } catch { return $null }
    finally { if ($probe) { $probe.Dispose() } }
    return $null
}

$pythonPath = $null
$savedPython = Join-Path $runnerRoot 'python_path.txt'
if (Test-Path -LiteralPath $savedPython -PathType Leaf) {
    $candidate = ([System.IO.File]::ReadAllText($savedPython)).Trim().Trim('"')
    if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { $pythonPath = $candidate }
}
if (-not $pythonPath -and $env:RESTSYNC_PYTHON) {
    $candidate = $env:RESTSYNC_PYTHON.Trim().Trim('"')
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $pythonPath = $candidate }
}
if (-not $pythonPath) {
    try {
        $packages = @(Get-AppxPackage -Name 'OpenAI.CodexPrimaryRuntime*' -ErrorAction Stop | Sort-Object Version -Descending)
        foreach ($package in $packages) {
            if (-not $package.InstallLocation) { continue }
            $candidate = Join-Path $package.InstallLocation 'dependencies/python/python.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $pythonPath = $candidate; break }
        }
    } catch { }
}
if (-not $pythonPath) {
    $runtimeRoot = Join-Path $env:ProgramFiles 'WindowsApps'
    $runtimes = @(Get-ChildItem -LiteralPath $runtimeRoot -Directory -Filter 'OpenAI.CodexPrimaryRuntime*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    foreach ($runtime in $runtimes) {
        $candidate = Join-Path $runtime.FullName 'dependencies/python/python.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $pythonPath = $candidate; break }
    }
}
if (-not $pythonPath) {
    try {
        $pyLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
        if ($pyLauncher) { $pythonPath = Get-RestSyncPythonExecutable $pyLauncher.Source $true }
    } catch { }
}
if (-not $pythonPath) {
    try {
        $pythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
        if ($pythonCommand) { $pythonPath = Get-RestSyncPythonExecutable $pythonCommand.Source }
    } catch { }
}
if (-not $pythonPath) { throw 'Python 3 실행기를 찾지 못했습니다. Codex 런타임 또는 Python 3를 설치한 뒤 다시 실행하세요.' }
if (-not (Test-Path -LiteralPath $serverPath)) { throw 'server.py가 없습니다. ZIP 전체를 압축 해제한 폴더에서 실행하세요.' }

$arguments = @('-u', ('"' + $serverPath + '"'))
$process = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $runnerRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runnerRoot 'server.stdout.log') -RedirectStandardError (Join-Path $runnerRoot 'server.stderr.log') -PassThru
$ready = $false
for ($attempt = 0; $attempt -lt 32; $attempt++) {
    if (Test-RestSyncRunner) { $ready = $true; break }
    if ($process.HasExited) { break }
    Start-Sleep -Milliseconds 250
}
if (-not $ready) { throw '실행기를 시작하지 못했습니다. 같은 폴더의 server.stderr.log를 확인하세요.' }
Write-Host 'RestSync 개발용 자동 클릭 실행기를 켰습니다.'
Write-Host 'Pleos 에뮬레이터의 RestSync 설정에서 시연 영상 버튼을 누르세요.'
