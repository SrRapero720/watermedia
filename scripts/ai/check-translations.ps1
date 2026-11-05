#Requires -Version 7
# CHECKS EVERY docs/DOCS-<LANG>.md AGAINST docs/DOCS-EN-US.md (CODE-MAP §0, DOCUMENTATION); EXIT CODE 1 ON ANY PROBLEM
# PROSE LINES WIDER THAN -Width ONLY WARN: scripts/ai/wrap-docs.ps1 REFLOWS THEM
# USAGE: pwsh scripts/ai/check-translations.ps1 [-Source <english doc>] [-Targets <translations>] [-Width 120]
param(
    [string]$Source = (Join-Path $PSScriptRoot '../../docs/DOCS-EN-US.md'),
    [string[]]$Targets = @(Get-ChildItem -Path (Join-Path $PSScriptRoot '../../docs') -Filter 'DOCS-*.md' | Where-Object { $_.Name -ne 'DOCS-EN-US.md' } | ForEach-Object { $_.FullName }),
    [int]$Width = 120
)

# DISPLAY WIDTH: CJK AND FULLWIDTH COUNT 2, SURROGATE PAIRS (EMOJI) 2, VARIATION SELECTORS 0
function DisplayWidth([string]$s) {
    $w = 0
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        if ([char]::IsSurrogate($ch)) { $w += 1; continue }
        if ($c -ge 0xFE00 -and $c -le 0xFE0F) { continue }
        if (($c -ge 0x1100 -and $c -le 0x115F) -or ($c -ge 0x2E80 -and $c -le 0xA4CF) -or ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
            ($c -ge 0xF900 -and $c -le 0xFAFF) -or ($c -ge 0xFE30 -and $c -le 0xFE4F) -or ($c -ge 0xFF00 -and $c -le 0xFF60) -or
            ($c -ge 0xFFE0 -and $c -le 0xFFE6)) { $w += 2 } else { $w += 1 }
    }
    return $w
}

# GITHUB ANCHOR: LOWERCASE, KEEP LETTERS/DIGITS/MARKS/SPACE/HYPHEN/UNDERSCORE, SPACES BECOME HYPHENS
function Anchor([string]$heading) {
    $h = $heading.Trim().ToLowerInvariant()
    $h = [regex]::Replace($h, '[^\p{L}\p{N}\p{M} _-]', '')
    return $h.Replace(' ', '-')
}

# CODE BLOCKS VERBATIM, HEADINGS OUTSIDE THEM, SAME-PAGE LINKS, ALERT BADGES, DASHES, NUL BYTES AND OVERLONG PROSE LINES
function Parse([string]$path) {
    $text = [IO.File]::ReadAllText($path)
    $lines = $text -split "`r?`n"
    $code = New-Object System.Collections.Generic.List[string]
    $headings = New-Object System.Collections.Generic.List[string]
    $long = New-Object System.Collections.Generic.List[int]
    $buffer = $null
    $n = 0
    foreach ($line in $lines) {
        $n++
        if ($line -match '^```') {
            if ($null -eq $buffer) { $buffer = New-Object System.Text.StringBuilder; [void]$buffer.AppendLine($line) }
            else { [void]$buffer.AppendLine($line); $code.Add($buffer.ToString()); $buffer = $null }
            continue
        }
        if ($null -ne $buffer) { [void]$buffer.AppendLine($line); continue }
        if ($line -match '^(#{1,6}) (.+)$') { $headings.Add($Matches[1] + '|' + $Matches[2]); continue }
        # TABLE ROWS AND HTML CAN'T WRAP, SO ONLY PROSE COUNTS
        if ($line -notmatch '^\s*(\||<)' -and (DisplayWidth $line) -gt $Width) { $long.Add($n) }
    }
    $anchors = @{}
    foreach ($h in $headings) { $anchors[(Anchor ($h.Split('|', 2)[1]))] = $true }
    return [pscustomobject]@{
        Lines = $lines.Count
        Code = $code
        Levels = ($headings | ForEach-Object { $_.Split('|')[0] }) -join ','
        Anchors = $anchors
        Links = @([regex]::Matches($text, '\]\(#([^)]+)\)') | ForEach-Object { $_.Groups[1].Value })
        Badges = ([regex]::Matches($text, '(?m)^> \[!(NOTE|IMPORTANT|WARNING|TIP|CAUTION)\]') | ForEach-Object { $_.Groups[1].Value }) -join ','
        Dashes = ([regex]::Matches($text, "[$([char]0x2013)$([char]0x2014)]")).Count
        Nul = ([regex]::Matches($text, '\x00')).Count
        Long = $long
    }
}

function WarnLong([string]$path, $doc) {
    if ($doc.Long.Count) { "WARN $(Split-Path $path -Leaf): $($doc.Long.Count) prose lines over $Width columns (lines $($doc.Long -join ', '))" }
}

$failed = 0
$src = Parse $Source
$broken = $src.Links | Where-Object { -not $src.Anchors.ContainsKey($_) } | Sort-Object -Unique
if ($broken -or $src.Dashes -or $src.Nul) {
    "FAIL $(Split-Path $Source -Leaf) (source): broken links [$($broken -join ', ')], em/en dashes $($src.Dashes), NUL bytes $($src.Nul)"
    $failed++
}
WarnLong $Source $src
foreach ($t in $Targets) {
    $dst = Parse $t
    $problems = @()
    if ($dst.Code.Count -ne $src.Code.Count) { $problems += "code blocks: $($dst.Code.Count) vs $($src.Code.Count)" }
    else { for ($i = 0; $i -lt $src.Code.Count; $i++) { if ($dst.Code[$i] -cne $src.Code[$i]) { $problems += "code block $($i + 1) differs" } } }
    if ($dst.Levels -cne $src.Levels) { $problems += 'heading levels differ' }
    if ($dst.Badges -cne $src.Badges) { $problems += 'badges differ' }
    $missing = $dst.Links | Where-Object { -not $dst.Anchors.ContainsKey($_) } | Sort-Object -Unique
    if ($missing) { $problems += 'broken links: ' + ($missing -join ', ') }
    if ($dst.Links.Count -ne $src.Links.Count) { $problems += "links: $($dst.Links.Count) vs $($src.Links.Count)" }
    if ($dst.Dashes) { $problems += "em/en dashes: $($dst.Dashes)" }
    if ($dst.Nul) { $problems += "NUL bytes: $($dst.Nul)" }
    $name = Split-Path $t -Leaf
    if ($problems) { "FAIL $name ($($dst.Lines) lines)"; $problems | ForEach-Object { "  - $_" }; $failed++ }
    else { "OK   $name ($($dst.Lines) lines, $($dst.Code.Count) code blocks, $($dst.Links.Count) links)" }
    WarnLong $t $dst
}

if ($failed) { exit 1 }
