# post_pack_verify.ps1 - post-pack verification for BempDiff NSIS builds.
# Pure ASCII on purpose: PS 5.1 decodes BOM-less non-ASCII scripts via ANSI codepage.
#
# What it does (T01507, family check added T01543):
#   1. Locate the canonical installer for $Version across release root AND legacy _bk-* dirs
#      ("exists and >= 50MB" rule). Warns loudly when the canonical copy is NOT in the root
#      (stale-root scenario: users picking from \release\ would get an old build).
#   1b. Deliverable family check: the canonical setup.exe MUST have its .blockmap next to it
#      (NSIS differentialUpdate is on, so a missing blockmap = partial promote -> FAIL;
#      a 0-byte blockmap is tolerated with a WARN, verified benign in T00667).
#   2. Verify packaged webui bundle freshness: the index.html inside the packaged
#      win-unpacked tree must reference the SAME hashed bundles as the source-side
#      dist_input\webui\index.html (guards "packaged OK but ships an old frontend").
# Exit 0 = pass, 1 = fail. Prints CANONICAL-INSTALLER line for pickup automation.

param(
  [Parameter(Mandatory = $true)][string]$ReleaseDir,
  [Parameter(Mandatory = $true)][string]$Version,
  [Parameter(Mandatory = $true)][string]$SourceWebuiIndex
)
$ErrorActionPreference = 'Stop'
$MinInstallerBytes = 52428800  # 50MB stub guard

if (-not (Test-Path $ReleaseDir)) { Write-Error ("ReleaseDir not found: " + $ReleaseDir); exit 1 }

# ---- 1. candidate dirs: root first, then every _bk-* ----
$cands = New-Object System.Collections.Generic.List[string]
$cands.Add($ReleaseDir)
Get-ChildItem -LiteralPath $ReleaseDir -Directory -Filter '_bk-*' -ErrorAction SilentlyContinue |
  ForEach-Object { $cands.Add($_.FullName) }

$valid = New-Object System.Collections.Generic.List[string]
foreach ($d in $cands) {
  $f = Join-Path $d ("BempDiff-{0}-setup.exe" -f $Version)
  if (Test-Path -LiteralPath $f) {
    if ((Get-Item -LiteralPath $f).Length -ge $MinInstallerBytes) { $valid.Add($f) }
    else { Write-Host ("[WARN] stub installer (<50MB), ignored: " + $f) }
  }
}
if ($valid.Count -eq 0) {
  Write-Error ("no valid installer (>=50MB) for version " + $Version + " under " + $ReleaseDir + " (root + _bk-*)")
  exit 1
}

$canonical = $null
foreach ($f in $valid) {
  if ($f.StartsWith($ReleaseDir, [System.StringComparison]::OrdinalIgnoreCase) -and
      -not ($f.Substring($ReleaseDir.Length).StartsWith('\_bk-'))) { $canonical = $f; break }
}
if (-not $canonical) {
  $canonical = $valid | Sort-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } -Descending | Select-Object -First 1
  Write-Host "[WARN] canonical installer is NOT in release root - stale-root scenario. Promote it or pick from the path below."
}

# ---- 1b. deliverable family check (T01543, from T00638/T00667 suggestion) ----
# electron-builder NSIS keeps differentialUpdate=true here, so EVERY valid build emits a
# "<setup.exe>.blockmap" next to the installer. A missing blockmap means a partial promote
# (exe copied, blockmap copy failed/skipped) - exactly the family-inconsistency class T00667
# fixed - so FAIL loudly instead of shipping an auto-update-incomplete deliverable.
# A 0-byte blockmap is tolerated (verified benign for distribution in T00667) with a WARN.
# The reverse orphan (blockmap present but no valid exe) is already fatal above.
$bm = $canonical + '.blockmap'
if (-not (Test-Path -LiteralPath $bm)) {
  Write-Host "[ERROR] family check FAILED: installer present but its .blockmap is missing:"
  Write-Host ("        exe      : " + $canonical)
  Write-Host ("        expected : " + $bm)
  exit 1
}
$bmLen = (Get-Item -LiteralPath $bm).Length
if ($bmLen -eq 0) {
  Write-Host ("[WARN] blockmap is 0 bytes (delta updates unavailable; distribution unaffected): " + $bm)
} else {
  Write-Host ("[OK] deliverable family intact: setup.exe + blockmap (" + $bmLen + " bytes)")
}

Write-Host ("CANONICAL-INSTALLER: " + $canonical)

# ---- 2. packaged webui bundle freshness ----
# T01670: match BOTH index-*.js and index-*.css — vite emits hashed css bundles into
# assets/ too, and the T01670 extra-file check below compares the full assets/ listing
# against this set; a js-only regex would flag every css bundle as "extra" (false FAIL).
$bundleRe = 'index-[A-Za-z0-9_-]+\.(?:js|css)'
function Get-Bundles([string]$idxPath) {
  if (-not (Test-Path -LiteralPath $idxPath)) { return $null }
  $html = [System.IO.File]::ReadAllText($idxPath)
  return @([regex]::Matches($html, $bundleRe) | ForEach-Object { $_.Value } | Sort-Object -Unique)
}
$srcBundles = Get-Bundles $SourceWebuiIndex
if (-not $srcBundles -or $srcBundles.Count -eq 0) {
  Write-Error ("source webui index has no hashed bundle refs (or file missing): " + $SourceWebuiIndex)
  exit 1
}

$packagedIndex = $null
foreach ($d in $cands) {
  $p = Join-Path $d 'win-unpacked\resources\bempdiff\dist_input\webui\index.html'
  if (Test-Path -LiteralPath $p) {
    if ($null -eq $packagedIndex) { $packagedIndex = $p }
    else {
      $a = Get-Item -LiteralPath $packagedIndex; $b = Get-Item -LiteralPath $p
      if ($b.LastWriteTimeUtc -gt $a.LastWriteTimeUtc) { $packagedIndex = $p }
    }
  }
}
if (-not $packagedIndex) {
  Write-Host "[WARN] no packaged win-unpacked webui index found - bundle freshness check skipped"
  exit 0
}
$pkgBundles = Get-Bundles $packagedIndex
$diff = Compare-Object -ReferenceObject $srcBundles -DifferenceObject $pkgBundles -ErrorAction SilentlyContinue
if ($diff) {
  Write-Host ("[ERROR] packaged webui is STALE vs source:")
  Write-Host ("        packaged : " + (($pkgBundles | Select-Object -First 6) -join ', '))
  Write-Host ("        source   : " + (($srcBundles | Select-Object -First 6) -join ', '))
  Write-Host ("        packaged index: " + $packagedIndex)
  exit 1
}
Write-Host ("[OK] packaged webui bundles match source (" + ($pkgBundles.Count) + " bundles): " + $packagedIndex)

# ---- 2b. T01670: reject stale extra bundles in packaged assets dir ----
# emptyOutDir:false in vite.config.js causes webui/dist/assets to accumulate historical
# index-*.js/css bundles across builds. The mirror step (robocopy dist_input/webui) only
# copies current build output, but if webui/dist itself wasn't cleaned before npm run build,
# the NEW build's dist still contains old bundles. This check ensures the packaged
# webui/assets/ directory contains ONLY the bundles referenced by its own index.html
# (plus non-bundle assets like splash-logo.png / vendor/ which live at webui root, not assets/).
# packagedIndex = ...\dist_input\webui\index.html  ->  assets dir = ...\dist_input\webui\assets
$assetsDir = Join-Path (Split-Path $packagedIndex -Parent) 'assets'
if (Test-Path -LiteralPath $assetsDir) {
  $actualFiles = Get-ChildItem -LiteralPath $assetsDir -File | ForEach-Object { $_.Name } | Sort-Object
  # Only index-* hashed bundles should be in assets/; any extras indicate accumulation.
  $expectedFiles = $pkgBundles | Sort-Object
  $extras = Compare-Object -ReferenceObject $expectedFiles -DifferenceObject $actualFiles -ErrorAction SilentlyContinue |
    Where-Object { $_.SideIndicator -eq '=>' } | ForEach-Object { $_.InputObject }
  if ($extras) {
    Write-Host "[ERROR] T01670: packaged webui/assets/ contains EXTRA files not referenced by index.html:"
    foreach ($f in $extras) { Write-Host ("        extra: " + $f) }
    Write-Host ("        assets dir: " + $assetsDir)
    Write-Host "        Fix: ensure build_dist_input.ps1 clears webui/dist before npm run build"
    exit 1
  }
  Write-Host ("[OK] T01670: no extra bundles in packaged assets/ (" + $actualFiles.Count + " files, all referenced)")
} else {
  Write-Host "[WARN] T01670: packaged assets dir not found - extra bundle check skipped"
}

Write-Host "[OK] post-pack verify passed."
exit 0
