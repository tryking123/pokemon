[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath,

    [ValidateRange(1, 65535)]
    [int]$HostPort = 8080,

    [ValidateRange(1, 65535)]
    [int]$GameHostPort = 9002,

    [switch]$Check
)

$ErrorActionPreference = 'Stop'

if ($HostPort -eq $GameHostPort) {
    throw 'HTTP 和游戏网关必须使用不同的宿主端口。'
}

$mappings = [ordered]@{
    'tcp:8080' = "tcp:$HostPort"
    'tcp:9002' = "tcp:$GameHostPort"
}
$serviceUrl = "http://127.0.0.1:$HostPort"
$jar = Join-Path $PSScriptRoot 'target/pokemon-local-server-1.0.0.jar'

function Resolve-Adb {
    if ($AdbPath) {
        $file = Get-Item -LiteralPath $AdbPath
        if ($file.PSIsContainer) {
            throw '-AdbPath 应指向 ADB 可执行文件。'
        }
        return $file.FullName
    }

    $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) {
        return $command.Source
    }

    foreach ($root in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($root) {
            $candidate = Join-Path $root 'platform-tools/adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }

    $candidates = @('E:/mySorf/MuMuPlayer/nx_main/adb.exe')
    if ($env:LOCALAPPDATA) {
        $candidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    throw '找不到 ADB。请安装 Android SDK Platform Tools，或使用 -AdbPath 指定 adb.exe。'
}

function Invoke-AdbCommand {
    param([string[]]$AdbArguments)

    # Windows PowerShell 会把原生命令的 stderr 包装为错误记录。
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& $adb @AdbArguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB 执行失败：adb $($AdbArguments -join ' ')。$($output -join ' ')"
    }
    $output | ForEach-Object { $_.ToString() }
}

function Get-TargetSerial {
    $devices = @(Invoke-AdbCommand -AdbArguments @('devices', '-l') | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader|no permissions)(?:\s|$)') {
            [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
        }
    })

    if ($Serial) {
        $target = $devices | Where-Object Serial -CEQ $Serial | Select-Object -First 1
        if ($null -eq $target) {
            throw "没有发现设备 $Serial。请先在 MuMu 中打开目标实例，并确认 ADB 已连接。"
        }
        if ($target.State -ne 'device') {
            throw "设备 $Serial 当前状态为 $($target.State)。请在模拟器中启用 ADB 并确认授权后重试。"
        }
        return $target.Serial
    }

    $ready = @($devices | Where-Object State -EQ 'device')
    if ($ready.Count -eq 1) {
        return $ready[0].Serial
    }
    if ($ready.Count -gt 1) {
        throw "发现多个可用设备：$($ready.Serial -join '、')。请用 -Serial 指定目标。"
    }
    throw '没有已连接并授权的 ADB 设备。请先打开 MuMu 实例，并用 adb devices -l 检查连接。'
}

function Get-ExistingReverse {
    param([string]$DeviceEndpoint)
    Invoke-AdbCommand -AdbArguments @('-s', $targetSerial, 'reverse', '--list') |
        ForEach-Object {
            $columns = $_.Trim() -split '\s+'
            if ($columns.Count -ge 2 -and $columns[-2] -eq $deviceEndpoint) {
                $columns[-1]
            }
        }
}

function Confirm-ReverseMapping {
    param([string]$DeviceEndpoint, [string]$HostEndpoint)

    $current = @(Get-ExistingReverse -DeviceEndpoint $DeviceEndpoint)
    if ($current.Count -gt 0 -and ($current.Count -ne 1 -or $current[0] -ne $HostEndpoint)) {
        throw "设备 $targetSerial 的 $DeviceEndpoint 已转发到 $($current -join '、')。请核对现有映射。"
    }
    if ($Check -and $current.Count -eq 0) {
        throw "设备 $targetSerial 缺少 $DeviceEndpoint 到 $HostEndpoint 的映射。去掉 -Check 后可配置。"
    }
    return ($current.Count -eq 1)
}

function Test-PokemonService {
    try {
        $health = $http.GetAsync("$serviceUrl/health").GetAwaiter().GetResult()
        try {
            if ([int]$health.StatusCode -ne 200) {
                return $false
            }
            $state = $health.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if ($state.status -ne 'UP') {
                return $false
            }
        } finally {
            $health.Dispose()
        }

        # Fate 等服务也可能回应 /health；账号接口用来核对当前项目。
        $account = $http.GetAsync("$serviceUrl/api/account/me").GetAwaiter().GetResult()
        try {
            if ([int]$account.StatusCode -ne 401) {
                return $false
            }
            $errorBody = $account.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            return $errorBody.code -ceq 'UNAUTHORIZED' -and $errorBody.message -eq '请先登录本地账号'
        } finally {
            $account.Dispose()
        }
    } catch {
        return $false
    }
}

function Test-HostPort {
    param([int]$Port)
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(500) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Start-LocalBackend {
    $java = Get-Command java -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $java) {
        throw '找不到 Java。请将 Java 8 或兼容版本的 java 加入 PATH。'
    }

    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        $maven = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $maven) {
            throw '服务端 JAR 尚未构建，并且 PATH 中找不到 Maven。'
        }
        Write-Host '正在构建服务端...'
        Push-Location -LiteralPath $PSScriptRoot
        try {
            & $maven.Source '-B' '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
                throw 'Maven 未能生成服务端 JAR。请检查上方构建输出。'
            }
        } finally {
            Pop-Location
        }
    }

    $runDir = Join-Path $PSScriptRoot 'target/local-run'
    New-Item -ItemType Directory -Path $runDir -Force | Out-Null
    $stamp = [DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff') + '-' + $PID
    $stdout = Join-Path $runDir "$stamp.log"
    $stderr = Join-Path $runDir "$stamp.err.log"
    $arguments = @('-jar', ('"' + $jar + '"'), '--server.address=127.0.0.1', "--server.port=$HostPort")
    $launch = @{
        FilePath = $java.Source
        ArgumentList = $arguments
        WorkingDirectory = $PSScriptRoot
        WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout
        RedirectStandardError = $stderr
        PassThru = $true
    }
    $previousGamePort = [Environment]::GetEnvironmentVariable('POKEMON_GAME_PORT', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('POKEMON_GAME_PORT', "$GameHostPort", 'Process')
        $backend = Start-Process @launch
    } finally {
        [Environment]::SetEnvironmentVariable('POKEMON_GAME_PORT', $previousGamePort, 'Process')
    }

    $started = [Diagnostics.Stopwatch]::StartNew()
    while ($started.Elapsed.TotalSeconds -lt 30) {
        if ((Test-PokemonService) -and (Test-HostPort -Port $GameHostPort)) {
            Write-Host "本地服务已启动（PID $($backend.Id)）。日志：$stdout"
            return
        }
        if ($backend.HasExited) {
            throw "服务端进程已退出，退出码 $($backend.ExitCode)。日志：$stdout；错误日志：$stderr"
        }
        Start-Sleep -Milliseconds 300
    }
    throw "等待本地服务就绪超时。日志：$stdout；错误日志：$stderr"
}

$adb = Resolve-Adb
$targetSerial = Get-TargetSerial
$missing = @($mappings.GetEnumerator() | Where-Object {
    -not (Confirm-ReverseMapping -DeviceEndpoint $_.Key -HostEndpoint $_.Value)
})

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$http = [System.Net.Http.HttpClient]::new($handler)
$http.Timeout = [TimeSpan]::FromSeconds(2)
try {
    if (Test-PokemonService) {
        if (-not (Test-HostPort -Port $GameHostPort)) {
            throw "HTTP 已就绪，但游戏网关 $GameHostPort 未就绪。请确认 IDEA 运行的是当前版本，并核对 POKEMON_GAME_PORT。"
        }
        Write-Host "本地 HTTP 和游戏网关已就绪：$serviceUrl、TCP $GameHostPort"
    } elseif (Test-HostPort -Port $HostPort) {
        throw "宿主端口 $HostPort 已被占用，且没有返回守护小精灵服务的健康响应。可以用 -HostPort 指定另一个空闲端口。"
    } elseif ($Check) {
        throw "宿主未运行守护小精灵本地服务：$serviceUrl。请去掉 -Check 后执行。"
    } else {
        if (Test-HostPort -Port $GameHostPort) {
            throw "宿主游戏端口 $GameHostPort 已被占用。请通过 -GameHostPort 指定空闲端口。"
        }
        Start-LocalBackend
    }

    foreach ($mapping in $missing) {
        Invoke-AdbCommand -AdbArguments @('-s', $targetSerial, 'reverse', '--no-rebind', $mapping.Key, $mapping.Value) | Out-Null
        if (-not (Confirm-ReverseMapping -DeviceEndpoint $mapping.Key -HostEndpoint $mapping.Value)) {
            throw "设备 $targetSerial 的映射 $($mapping.Key) → $($mapping.Value) 未出现在 ADB 列表中。"
        }
    }

    Write-Host "设备 $targetSerial 已配置：Android 8080 → 宿主 $HostPort，Android 9002 → 宿主 $GameHostPort"
} finally {
    $http.Dispose()
}
