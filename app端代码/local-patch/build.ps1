param(
    [Parameter(Mandatory = $true)][string] $AndroidJar,
    [Parameter(Mandatory = $true)][string] $D8Jar,
    [Parameter(Mandatory = $true)][string] $JdkHome,
    [string] $MavenRepository = 'E:/mySorf/m2/repository',
    [string] $OutputArchive
)

$ErrorActionPreference = 'Stop'
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$D8Jar = (Resolve-Path -LiteralPath $D8Jar).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
foreach ($tool in @('javac.exe', 'java.exe', 'jar.exe')) {
    $candidate = Join-Path $JdkHome ('bin/' + $tool)
    if (-not (Test-Path -LiteralPath $candidate)) { throw "JDK 缺少可执行文件：$candidate" }
}
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$jar = Join-Path $JdkHome 'bin/jar.exe'
$tempBase = (Resolve-Path -LiteralPath $env:TEMP).Path
$build = Join-Path $tempBase ('pokemon-client-build-' + [Guid]::NewGuid().ToString('N'))

try {
    $stubClasses = New-Item -ItemType Directory -Path (Join-Path $build 'stubs') -Force
    $appClasses = New-Item -ItemType Directory -Path (Join-Path $build 'app')
    $dexOutput = New-Item -ItemType Directory -Path (Join-Path $build 'dex')
    $stubArchive = Join-Path $build 'unity-compile-only.jar'
    $appArchive = Join-Path $build 'local-client.jar'
    $stubSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java').FullName
    $appSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src'),(Join-Path $PSScriptRoot 'embedded/src') -Recurse -Filter '*.java').FullName
    $shared = Join-Path $PSScriptRoot 'offline'
    $sharedSources = @(Get-ChildItem -LiteralPath (Join-Path $shared 'src') -Recurse -Filter '*.java' |
        Where-Object { $_.Name -notmatch 'Controller\.java$|^(ApiErrorHandler|PokemonLocalServer)\.java$' })
    $components = foreach ($source in $sharedSources) {
        $contents = Get-Content -LiteralPath $source.FullName -Raw
        if ($contents -match '(?m)^@(Service|Repository|Component)\s*$' -and $contents -match '(?m)^package ([^;]+);') {
            $Matches[1] + '.' + $source.BaseName
        }
    }

    $jackson = foreach ($part in @('annotations','core','databind')) {
        (Resolve-Path -LiteralPath (Join-Path $MavenRepository "com/fasterxml/jackson/core/jackson-$part/2.13.5/jackson-$part-2.13.5.jar")).Path
    }

    & $javac --release 8 -encoding UTF-8 -classpath $AndroidJar -d $stubClasses.FullName @stubSources
    if ($LASTEXITCODE -ne 0) { throw '编译 Unity 接口声明失败' }
    & $jar cf $stubArchive -C $stubClasses.FullName .
    if ($LASTEXITCODE -ne 0) { throw '打包 Unity 编译类路径失败' }

    $compilePath = (@($AndroidJar, $stubArchive) + $jackson) -join [IO.Path]::PathSeparator
    # 使用 javac 参数文件，避免源码增加后超过 Windows 创建进程的命令行长度。
    $sourceList = Join-Path $build 'sources.args'
    $sourceLines = foreach ($source in (@($appSources) + @($sharedSources.FullName))) {
        '"' + $source.Replace('\', '/') + '"'
    }
    [IO.File]::WriteAllLines($sourceList, [string[]]$sourceLines, [Text.UTF8Encoding]::new($false))
    & $javac '-J-Dfile.encoding=UTF-8' --release 8 -encoding UTF-8 -classpath $compilePath -d $appClasses.FullName "@$sourceList"
    if ($LASTEXITCODE -ne 0) { throw '编译本地客户端失败' }
    & $jar cf $appArchive -C $appClasses.FullName .
    if ($LASTEXITCODE -ne 0) { throw '打包本地客户端 Java 类失败' }
    if ($OutputArchive) { Copy-Item -LiteralPath $appArchive -Destination $OutputArchive -Force }

    & $java -cp $D8Jar com.android.tools.r8.D8 --release --min-api 26 --lib $AndroidJar --classpath $stubArchive --output $dexOutput.FullName $appArchive @jackson
    if ($LASTEXITCODE -ne 0) { throw '生成客户端 DEX 失败' }
    $entryDir = New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'apk-entries') -Force
    $destination = Join-Path $entryDir.FullName 'classes9.dex'
    if (Test-Path -LiteralPath $destination) {
        $backupDir = Join-Path $PSScriptRoot 'build/dex-backups'
        New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        Copy-Item -LiteralPath $destination -Destination (Join-Path $backupDir ("classes9-" + $stamp + ".dex")) -Force
    }
    Copy-Item -LiteralPath (Join-Path $dexOutput.FullName 'classes.dex') -Destination $destination -Force
    $assetFolder = New-Item -ItemType Directory -Path (Join-Path $entryDir.FullName 'assets/pokemon-local') -Force
    foreach ($item in @(Get-ChildItem -LiteralPath (Join-Path $shared 'resources') | Where-Object Name -ne 'application.properties')) {
        Copy-Item -LiteralPath $item.FullName -Destination $assetFolder.FullName -Recurse -Force
    }
    $components | Sort-Object | Set-Content -LiteralPath (Join-Path $assetFolder.FullName 'components.txt') -Encoding utf8
    Get-FileHash -LiteralPath $destination -Algorithm SHA256
}
finally {
    if (Test-Path -LiteralPath $build) {
        $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
        if ([IO.Path]::GetDirectoryName($resolvedBuild) -eq $tempBase -and
                [IO.Path]::GetFileName($resolvedBuild) -match '^pokemon-client-build-[0-9a-f]{32}$') {
            Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
        }
    }
}
