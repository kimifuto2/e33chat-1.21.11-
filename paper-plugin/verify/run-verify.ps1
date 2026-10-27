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

# There may be more than one Gradle cache: the user-level ~/.gradle and the workspace-level
# .gradle-home shared by every branch of this repo. Search all of them -- paper-api and netty
# often live in different roots.
$m2Candidates = @()
$m2Candidates += "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$m2Candidates += (Join-Path (Split-Path -Parent (Split-Path -Parent $project)) '.gradle-home\caches\modules-2\files-2.1')
$m2Candidates += (Join-Path (Split-Path -Parent $project) '.gradle-home\caches\modules-2\files-2.1')
$m2Roots = @()
foreach ($candidate in $m2Candidates) {
    if ($candidate -and (Test-Path $candidate) -and ($m2Roots -notcontains $candidate)) { $m2Roots += $candidate }
}
if ($m2Roots.Count -eq 0) { throw 'no gradle modules-2 cache found' }
Write-Host "== gradle caches =="
foreach ($root in $m2Roots) { Write-Host "   $root" }

function Find-Jar([string] $pattern) {
    foreach ($root in $m2Roots) {
        $hit = Get-ChildItem $root -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    throw "jar not found in gradle cache: $pattern"
}

# Optional: only needed if the class is actually touched at runtime; skip silently when absent.
function Try-Jar([string] $pattern) {
    foreach ($root in $m2Roots) {
        $hit = Get-ChildItem $root -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
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

# Use the JDK that JAVA_HOME points at (the project's own toolchain), not whatever is on PATH:
# paper-api 26.x ships Java 25 class files (major 69) and javac 21 refuses to read them.
$javac = 'javac'
$java  = 'java'
if ($env:JAVA_HOME) {
    $candidateJavac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $candidateJava  = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path $candidateJavac) { $javac = $candidateJavac }
    if (Test-Path $candidateJava)  { $java  = $candidateJava }
}
Write-Host ("== toolchain: " + $javac)

$cp = (($libs + $mcJar.FullName + $classes) -join ';')

Write-Host "== javac =="
& $javac -encoding UTF-8 -cp $cp -d $out (Join-Path $here 'com\niuqu\chatbubble\paper\CodecByteCheck.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "== run =="
& $java -cp "$cp;$out" com.niuqu.chatbubble.paper.CodecByteCheck
$codecExit = $LASTEXITCODE

# ---------------------------------------------------------------------------------------------
# Stage 2: channel-name probe against paper-api's own validator.
# Registering a channel with an invalid name makes Messenger throw -> the plugin would disable
# itself in onEnable, so this is not cosmetic. Skipped silently when paper-api is not cached.
# Picks the paper-api version this project actually compiles against (parsed from build.gradle),
# so a machine holding several paper-api versions does not pick a Java-25 one for a Java-21 build.
# ---------------------------------------------------------------------------------------------
Write-Host ""
Write-Host "== channel probe (paper-api) =="
$bgText = Get-Content -Raw -Encoding UTF8 (Join-Path $project 'build.gradle')
$wantVersion = $null
if ($bgText -match "paperApiVersion\s*=\s*'([^']+)'") {
    $wantVersion = $Matches[1]
} elseif ($bgText -match "paper-api:([^']+)'") {
    $wantVersion = $Matches[1]
}
$paperApi = $null
foreach ($root in $m2Roots) {
    if ($wantVersion) {
        $hit = Get-ChildItem $root -Recurse -Filter "paper-api-$wantVersion*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($hit) { $paperApi = $hit; break }
    }
}
if (-not $paperApi) {
    Write-Host "paper-api not found in gradle cache -> probe skipped"
} else {
    Write-Host ("using " + $paperApi.FullName)
    # paper-api's StandardMessenger needs commons-lang3 at runtime, otherwise the negative
    # controls fail with NoClassDefFoundError instead of the real validation error.
    $probeCp = $paperApi.FullName
    $lang3 = Try-Jar 'commons-lang3-*.jar'
    if ($lang3) { $probeCp = "$probeCp;$lang3" } else { Write-Host "commons-lang3 not found -> negative controls may crash" }
    $probeOut = Join-Path $here 'probe\out'
    New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
    & $javac -encoding UTF-8 -cp $probeCp -d $probeOut (Join-Path $here 'probe\ChannelProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "probe javac failed ($LASTEXITCODE)" }
    & $java -cp "$probeCp;$probeOut" ChannelProbe
    if ($LASTEXITCODE -ne 0) { $codecExit = $LASTEXITCODE }
}

Write-Host ("codec exit code = " + $codecExit)
exit $codecExit
