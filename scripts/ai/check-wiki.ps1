#Requires -Version 7
# CHECKS THE GITHUB WIKI CLONE AGAINST docs/DOCS-<LANG>.md (CODE-MAP §18, WIKI SYNC); EXIT CODE 1 ON ANY PROBLEM
# USAGE: pwsh scripts/ai/check-wiki.ps1 [-Wiki <wiki clone>] [-Docs <docs folder>]
param(
    [string]$Wiki = (Join-Path $PSScriptRoot '../../documentation'),
    [string]$Docs = (Join-Path $PSScriptRoot '../../docs')
)

# GITHUB ANCHOR: LOWERCASE, KEEP LETTERS/DIGITS/MARKS/SPACE/HYPHEN/UNDERSCORE, SPACES BECOME HYPHENS
function Anchor([string]$heading) {
    $h = $heading.Trim().ToLowerInvariant()
    $h = [regex]::Replace($h, '[^\p{L}\p{N}\p{M} _-]', '')
    return $h.Replace(' ', '-')
}

# LINES OUTSIDE CODE FENCES, SO LINKS AND HEADINGS INSIDE SAMPLES ARE IGNORED
function Prose([string[]]$lines) {
    $inCode = $false
    foreach ($l in $lines) {
        if ($l -match '^```') { $inCode = -not $inCode; continue }
        if (-not $inCode) { $l }
    }
}

$problems = 0
$files = Get-ChildItem -Path $Wiki -Recurse -Filter *.md | Where-Object { $_.FullName -notmatch '[\\/]\.git[\\/]' } | ForEach-Object {
    $lines = [IO.File]::ReadAllText($_.FullName) -split "`r?`n"
    $anchors = @{}
    foreach ($l in (Prose $lines)) { if ($l -match '^#{1,6} (.+)$') { $anchors[(Anchor $Matches[1])] = $true } }
    $rel = [IO.Path]::GetRelativePath($Wiki, $_.FullName)
    [pscustomobject]@{ Name = $_.BaseName; Rel = $rel; Dir = (Split-Path $rel -Parent); Lines = $lines; Anchors = $anchors }
}

# GITHUB WIKI PAGE NAMES ARE GLOBAL ACROSS FOLDERS; SIDEBARS AND FOOTERS REPEAT PER FOLDER AND ARE NO LINK TARGETS
$pages = @{}
foreach ($f in $files) {
    if ($f.Name.StartsWith('_')) { continue }
    if ($pages.ContainsKey($f.Name)) { "DUPLICATE PAGE $($f.Rel)"; $problems++ }
    $pages[$f.Name] = $f
}

foreach ($f in $files) {
    foreach ($l in (Prose $f.Lines)) {
        foreach ($m in [regex]::Matches($l, '\]\(([^)\s]+)\)')) {
            $target = $m.Groups[1].Value
            if ($target -match '^(https?:|mailto:)') { continue }
            $parts = $target.Split('#', 2)
            $name = if ($parts[0]) { $parts[0] } else { $f.Name }
            if (-not $pages.ContainsKey($name)) { "BROKEN PAGE   $($f.Rel) -> $target"; $problems++; continue }
            if ($parts.Count -eq 2 -and -not $pages[$name].Anchors.ContainsKey($parts[1])) { "BROKEN ANCHOR $($f.Rel) -> $target"; $problems++ }
        }
    }
}

# EVERY LANGUAGE: <LANG>-Home THEN ITS NUMBERED PAGES, JOINED IN ORDER, MUST HOLD ITS DOC LINE BY LINE
# LINK TARGETS AND BLANK LINES ARE IGNORED, SINCE THE CUT REWRITES CROSS-PAGE LINKS
$norm = { param($t) [regex]::Replace($t, '\]\([^)]*\)', ']()') }
$langs = @()
foreach ($doc in Get-ChildItem -Path $Docs -Filter 'DOCS-*.md') {
    $lang = $doc.BaseName.Substring(5)
    $langs += $lang
    $landing = $pages["$lang-Home"]
    if (-not $landing) { "FAIL $lang`: missing page $lang-Home"; $problems++; continue }
    if (-not ($files | Where-Object { $_.Name -eq '_Sidebar' -and $_.Dir -eq $landing.Dir })) { "FAIL $lang`: no _Sidebar.md next to $lang-Home"; $problems++ }
    if (-not ($pages['Home'].Lines -match "\]\($([regex]::Escape($lang))-Home\)")) { "FAIL $lang`: the root Home doesn't link $lang-Home"; $problems++ }
    $prefix = [regex]::Escape($lang)
    $numbered = @($pages.Keys | Where-Object { $_ -match "^$prefix-\d+-" } | Sort-Object { [int]($_ -replace "^$prefix-(\d+)-.*$", '$1') })
    $order = @("$lang-Home") + $numbered
    $want = @([IO.File]::ReadAllText($doc.FullName) -split "`r?`n" | ForEach-Object { & $norm $_ } | Where-Object { $_.Trim() })
    $got = @($order | ForEach-Object { $pages[$_].Lines } | ForEach-Object { & $norm $_ } | Where-Object { $_.Trim() })
    $i = 0
    while ($i -lt $want.Count -and $i -lt $got.Count -and $want[$i] -ceq $got[$i]) { $i++ }
    if ($i -eq $want.Count -and $i -eq $got.Count) { "OK   $lang ($($order.Count) pages)"; continue }
    "FAIL $lang`: pages differ from $($doc.Name) at text line $($i + 1)"
    "  doc:  $(if ($i -lt $want.Count) { $want[$i] } else { '<end>' })"
    "  wiki: $(if ($i -lt $got.Count) { $got[$i] } else { '<end>' })"
    $problems++
}

# PAGES OUTSIDE EVERY LANGUAGE ARE LEFTOVERS OF AN OLD WIKI
foreach ($name in $pages.Keys) {
    if ($name -eq 'Home') { continue }
    if (-not ($langs | Where-Object { $name.StartsWith("$_-") })) { "STRAY PAGE $($pages[$name].Rel)"; $problems++ }
}

"problems: $problems"
if ($problems) { exit 1 }
