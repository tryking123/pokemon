param(
    [Parameter(Mandatory = $true)][string] $AndroidJar,
    [Parameter(Mandatory = $true)][string] $D8Jar,
    [Parameter(Mandatory = $true)][string] $JdkHome,
    [string] $MavenRepository = 'E:/mySorf/m2/repository'
)

$ErrorActionPreference = 'Stop'
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$D8Jar = (Resolve-Path -LiteralPath $D8Jar).Path
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$jar = Join-Path $JdkHome 'bin/jar.exe'
$tempBase = (Resolve-Path -LiteralPath $env:TEMP).Path
$build = Join-Path $tempBase ('pokemon-client-build-' + [Guid]::NewGuid().ToString('N'))

function Write-Annotation([string] $Name, [string] $Body) {
    $segments = $Name.Split('.')
    $package = $segments[0..($segments.Length - 2)] -join '.'
    $simple = $segments[-1]
    $folder = New-Item -ItemType Directory -Path (Join-Path $build ('annotations/' + $package.Replace('.', '/'))) -Force
    @"
package $package;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
@Retention(RetentionPolicy.RUNTIME)
public @interface $simple { $Body }
"@ | Set-Content -LiteralPath (Join-Path $folder.FullName ($simple + '.java')) -Encoding utf8
}

try {
    $stubClasses = New-Item -ItemType Directory -Path (Join-Path $build 'stubs') -Force
    $appClasses = New-Item -ItemType Directory -Path (Join-Path $build 'app')
    $dexOutput = New-Item -ItemType Directory -Path (Join-Path $build 'dex')
    $stubArchive = Join-Path $build 'unity-compile-only.jar'
    $appArchive = Join-Path $build 'local-client.jar'
    $stubSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java').FullName
    $appSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src'),(Join-Path $PSScriptRoot 'embedded/src') -Recurse -Filter '*.java').FullName
    $shared = Join-Path $PSScriptRoot '../../服务端代码/src/main'
    $sharedSources = @(Get-ChildItem -LiteralPath (Join-Path $shared 'java') -Recurse -Filter '*.java' |
        Where-Object { $_.Name -notmatch 'Controller\.java$|^(ApiErrorHandler|PokemonLocalServer)\.java$' })
    $components = foreach ($source in $sharedSources) {
        $contents = Get-Content -LiteralPath $source.FullName -Raw
        if ($contents -match '(?m)^@(Service|Repository|Component)\s*$' -and $contents -match '(?m)^package ([^;]+);') {
            $Matches[1] + '.' + $source.BaseName
        }
    }

    Write-Annotation 'javax.annotation.PostConstruct' ''
    Write-Annotation 'javax.annotation.PreDestroy' ''
    foreach ($name in @('Service','Repository','Component')) { Write-Annotation "org.springframework.stereotype.$name" '' }
    Write-Annotation 'org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization' ''
    Write-Annotation 'org.springframework.context.annotation.DependsOn' 'String[] value();'
    Write-Annotation 'org.springframework.context.annotation.Lazy' 'boolean value() default true;'
    Write-Annotation 'org.springframework.beans.factory.annotation.Value' 'String value();'
    Write-Annotation 'org.springframework.core.annotation.Order' 'int value();'
    Write-Annotation 'org.springframework.transaction.annotation.Transactional' 'boolean readOnly() default false; Propagation propagation() default Propagation.REQUIRED;'
    'package org.springframework.transaction.annotation; public enum Propagation { REQUIRED, MANDATORY }' |
        Set-Content -LiteralPath (Join-Path $build 'annotations/org/springframework/transaction/annotation/Propagation.java') -Encoding utf8
    $annotationSources = @(Get-ChildItem -LiteralPath (Join-Path $build 'annotations') -Recurse -Filter '*.java').FullName
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
    $sourceLines = foreach ($source in (@($appSources) + @($annotationSources) + @($sharedSources.FullName))) {
        '"' + $source.Replace('\', '/') + '"'
    }
    [IO.File]::WriteAllLines($sourceList, [string[]]$sourceLines, [Text.UTF8Encoding]::new($false))
    & $javac '-J-Dfile.encoding=UTF-8' --release 8 -encoding UTF-8 -classpath $compilePath -d $appClasses.FullName "@$sourceList"
    if ($LASTEXITCODE -ne 0) { throw '编译本地客户端失败' }
    & $jar cf $appArchive -C $appClasses.FullName .
    if ($LASTEXITCODE -ne 0) { throw '打包本地客户端 Java 类失败' }

    & $java -cp $D8Jar com.android.tools.r8.D8 --release --min-api 26 --lib $AndroidJar --classpath $stubArchive --output $dexOutput.FullName $appArchive @jackson
    if ($LASTEXITCODE -ne 0) { throw '生成客户端 DEX 失败' }
    $entryDir = New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'apk-entries') -Force
    $destination = Join-Path $entryDir.FullName 'classes9.dex'
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
