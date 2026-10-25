# Offline byte-level verification of the Paper-side codec against real Minecraft PacketByteBuf.
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less files as ANSI/GBK.
#
# Usage:  powershell -ExecutionPolicy Bypass -File verify\run-verify.ps1
#
# It resolves (read-only) the already-cached jars:
#   - yarn-named minecraft-merged 1.21.11 (loom cache)  -> real PacketByteBuf / StringEncoding
#   - netty-buffer / netty-common / netty-codec-base / netty-transport / fastutil / guava / gson (gradle cache)
# then compiles verify\com\niuqu\chatbubble\paper\CodecByteCheck.java and runs it.
# Nothing outside this project is written; Gradle cache files are only read.

$ErrorActionPreference = 'Stop'

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $here

$loomCache = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven"
$mcJar = Get-ChildItem $loomCache -Recurse -Filter "minecraft-merged-1.21.11-net.fabricmc.yarn.*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources' } |
    Select-Object -First 1
if (-not $mcJar) { throw "minecraft-merged 1.21.11 (yarn) not found under $loomCache" }

$gradleHome = Join-Path (Split-Path -Parent $project) '.gradle-home'
if (Test-Path (Join-Path $gradleHome 'caches\modules-2\files-2.1')) {
    $m2 = Join-Path $gradleHome 'caches\modules-2\files-2.1'
} else {
    $m2 = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
}

function Find-Jar([string] $pattern) {
    $hit = Get-ChildItem $m2 -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $hit) { throw "jar not found in gradle cache: $pattern" }
    return $hit.FullName
}

# Optional: only needed if the class is actually touched at runtime; skip silently when absent.
function Try-Jar([string] $pattern) {
    $hit = Get-ChildItem $m2 -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

$libs = @(
    (Find-Jar 'netty-buffer-*.jar'),
    (Find-Jar 'netty-common-*.jar'),
    (Find-Jar 'netty-codec-base-*.jar'),
    (Find-Jar 'netty-transport-*.jar'),
    (Find-Jar 'fastutil-*.jar')
)
foreach ($optional in @('guava-*.jar', 'gson-*.jar', 'log4j-api-*.jar', 'slf4j-api-*.jar',
                        'datafixerupper-*.jar', 'brigadier-*.jar', 'joml-*.jar',
                        'adventure-api-*.jar', 'adventure-key-*.jar', 'examination-api-*.jar',
                        'bungeecord-chat-*.jar', 'snakeyaml-*.jar')) {
    $jar = Try-Jar $optional
    if ($jar) { $libs += $jar }
}
$libs = $libs | Where-Object { $_ }

$classes = Join-Path $project 'build\classes\java\main'
if (-not (Test-Path $classes)) { throw "run 'gradle build' first: $classes missing" }

$out = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$cp = (($libs + $mcJar.FullName + $classes) -join ';')

Write-Host "== javac =="
& javac -encoding UTF-8 -cp $cp -d $out (Join-Path $here 'com\niuqu\chatbubble\paper\CodecByteCheck.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "== run =="
& java -cp "$cp;$out" com.niuqu.chatbubble.paper.CodecByteCheck
exit $LASTEXITCODE
