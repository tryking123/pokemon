param(
    [Parameter(Mandatory = $true)][string] $Zig,
    [Parameter(Mandatory = $true)][string] $JdkHome,
    [string] $OutputRoot = (Join-Path $PSScriptRoot '../apk-entries')
)

$ErrorActionPreference = 'Stop'
$compiler = (Resolve-Path -LiteralPath $Zig).Path
$include = Join-Path $JdkHome 'include'
foreach ($path in @((Join-Path $include 'jni.h'), (Join-Path $include 'win32/jni_md.h'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "缺少 JNI 编译头：$path" }
}
$targets = @(
    @{ Abi='arm64-v8a'; Target='aarch64-linux-android'; Flags=@() },
    @{ Abi='armeabi-v7a'; Target='arm-linux-android'; Flags=@('-mcpu=generic+v7a') }
)
foreach ($target in $targets) {
    $folder = Join-Path $OutputRoot ('lib/' + $target.Abi)
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $output = Join-Path $folder 'libpokemonoffline.so'
    $arguments = @('cc', '-target', $target.Target) + $target.Flags + @(
        '-shared', '-fPIC', '-nostdlib', '-fno-stack-protector', '-O2',
        ('-I' + (Join-Path $PSScriptRoot 'include')), ('-I' + $include), ('-I' + (Join-Path $include 'win32')),
        (Join-Path $PSScriptRoot 'offline_bridge.c'), '-Wl,-z,max-page-size=16384', '-o', $output
    )
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0) { throw "无法编译 $($target.Abi) 进程内桥" }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
}
