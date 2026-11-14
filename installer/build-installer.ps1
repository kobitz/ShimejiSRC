param(
    [string]$Version = "1.10",
    [string]$InstallDir = "",
    [string]$Iscc = ""
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent $here

# --- locate the install folder (INSTALL_DIR in the gitignored Build.local.bat) -------------------
if (-not $InstallDir) {
    $local = Join-Path $repo "Build.local.bat"
    if (Test-Path $local) {
        $m = Select-String -Path $local -Pattern '^\s*set\s+"?INSTALL_DIR=([^"]+)"?' | Select-Object -First 1
        if ($m) { $InstallDir = $m.Matches[0].Groups[1].Value.Trim() }
    }
}
if (-not $InstallDir -or -not (Test-Path $InstallDir)) { throw "Install folder not found. Set INSTALL_DIR in Build.local.bat or pass -InstallDir." }
Write-Host "Install folder: $InstallDir"

# --- locate Inno Setup ----------------------------------------------------------------------------
if (-not $Iscc) {
    $cands = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe")
    $cmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($cmd) { $Iscc = $cmd.Source } else { $Iscc = $cands | Where-Object { Test-Path $_ } | Select-Object -First 1 }
}
if (-not $Iscc) { throw "Inno Setup 6 (ISCC.exe) not found. Install it from https://jrsoftware.org/isinfo.php" }

$dist = Join-Path $repo "dist"
$out = Join-Path $dist "Shimeji"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

function Copy-Tree($src, $dst, [string[]]$xf = @(), [string[]]$xd = @()) {
    $rc = @($src, $dst, "/E", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
    if ($xf.Count) { $rc += "/XF"; $rc += $xf }
    if ($xd.Count) { $rc += "/XD"; $rc += $xd }
    & robocopy @args | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed copying $src" }
}
$scrubFiles = @("memory.json", "memory.json.bak", "*.bak", "*.log", "*.log.*", "chat.log", "relationship_journal.txt", "driveindex.txt", "driveembed.txt", "claude history.txt", "*- Copy*", "Thumbs.db", "desktop.ini")

# --- allowlist: top-level files ------------------------------------------------------------------
$required = @("Shimeji.exe", "Shimeji-ee.jar")
$optional = @("TempSensor.exe", "whisper_server.py", "whisper_prompt.txt", "Setup.bat", "Admin.bat", "Debug.bat", "shimeji-video-tracker.xpi", "README.txt")
foreach ($f in $required) { if (-not (Test-Path (Join-Path $InstallDir $f))) { throw "Missing required file in install folder: $f (run Build.bat -> [1] Release first)" } Copy-Item (Join-Path $InstallDir $f) $out }
foreach ($f in $optional) { $p = Join-Path $InstallDir $f; if (Test-Path $p) { Copy-Item $p $out } else { Write-Warning "Not found (skipped): $f" } }
Get-ChildItem $InstallDir -Filter *.dll -File | ForEach-Object { Copy-Item $_.FullName $out }   # MinGW runtime DLLs

# --- allowlist: folders ---------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $InstallDir "jre"))) { throw "Missing jre\ in the install folder." }
Copy-Tree (Join-Path $InstallDir "jre") (Join-Path $out "jre")
Copy-Tree (Join-Path $InstallDir "conf") (Join-Path $out "conf") $scrubFiles

# --- img: loose files + the sets in mascots.txt ----------------------------------------------------
$imgOut = Join-Path $out "img"; New-Item -ItemType Directory -Path $imgOut | Out-Null
Get-ChildItem (Join-Path $InstallDir "img") -File | ForEach-Object { Copy-Item $_.FullName $imgOut }
$sets = Get-Content (Join-Path $here "mascots.txt") | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") }
$waves = New-Object System.Collections.Generic.HashSet[string]
foreach ($s in $sets) {
    $src = Join-Path $InstallDir "img\$s"
    if (-not (Test-Path $src)) { Write-Warning "Mascot set not found (skipped): $s"; continue }
    Copy-Tree $src (Join-Path $imgOut $s) $scrubFiles
    Get-ChildItem $src -Recurse -Filter *.xml -File | ForEach-Object {
        [regex]::Matches((Get-Content $_.FullName -Raw), '/([^"/\\<>]+\.wav)') | ForEach-Object { [void]$waves.Add($_.Groups[1].Value) }
    }
    Write-Host "  + $s"
}
# --- sound: only what the included mascots reference -----------------------------------------------
$soundOut = Join-Path $out "sound"; New-Item -ItemType Directory -Path $soundOut | Out-Null
foreach ($w in $waves) {
    $p = Join-Path $InstallDir "sound\$w"
    if (Test-Path $p) { Copy-Item $p $soundOut } else { Write-Warning "Sound referenced but missing: $w" }
}

# --- settings.properties: shipped as a default, scrubbed of per-user state ----------------------------
$sp = Join-Path $InstallDir "settings.properties"
if (Test-Path $sp) {
    $lines = Get-Content $sp | Where-Object { $_ -notmatch '^\s*(PinnedMascot\.|[^#=]*\.mascot\d+\s*=)' }
    $lines = $lines | ForEach-Object { if ($_ -match '^\s*WeatherLocation\s*=') { 'WeatherLocation=auto' } else { $_ } }
    [IO.File]::WriteAllLines((Join-Path $out "settings.properties"), $lines, (New-Object Text.UTF8Encoding $false))
    Write-Warning "settings.properties copied and scrubbed (PinnedMascot.*, *.mascotN, WeatherLocation). Open dist\Shimeji\settings.properties and eyeball it before you publish."
} else { Write-Warning "No settings.properties in the install folder; the app will create one on first run." }

# --- scrub verification: refuse to build if any personal file slipped in ----------------------------
$bad = Get-ChildItem $out -Recurse -File | Where-Object {
    $n = $_.Name
    $n -in @("memory.json", "chat.log", "relationship_journal.txt", "driveindex.txt", "driveembed.txt", "claude history.txt") -or
    $n -like "*.bak" -or $n -like "*.log" -or $n -like "*- Copy*" -or $n -like "ShimejieeLog*"
}
if ($bad) { $bad | ForEach-Object { Write-Host "  PERSONAL FILE: $($_.FullName)" }; throw "Scrub check failed: personal files in the bundle." }
$mb = [math]::Round(((Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB)
Write-Host "Bundle assembled: $out ($mb MB), scrub check passed."

# --- compile -----------------------------------------------------------------------------------------
& $Iscc "/DAppVersion=$Version" "/DSourceDir=$out" (Join-Path $here "ShimejiSRC.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed." }
$exe = Join-Path $dist "ShimejiSRC-Setup-$Version.exe"
$hash = (Get-FileHash $exe -Algorithm SHA256).Hash
Write-Host ""
Write-Host "Installer: $exe"
Write-Host "SHA-256:   $hash"
