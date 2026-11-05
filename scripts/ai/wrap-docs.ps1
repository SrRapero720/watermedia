#Requires -Version 7
# REFLOWS THE PROSE OF docs/DOCS-*.md AT 120 COLUMNS (CODE-MAP §0); WITH -Wiki THE WIKI PAGES GET THE SAME LINE BREAKS
# READS THE REPO ONLY: RESULTS LAND IN -Out (docs/ AND wiki/ MIRRORS) AND THE REPORT NAMES EVERY FILE THAT CHANGES;
# COPY THOSE WITH THE WRITE TOOL, NEVER WITH A SHELL WRITE. EXIT CODE 1 WHEN A FILE CAN'T BE MAPPED SAFELY
# USAGE: pwsh scripts/ai/wrap-docs.ps1 -Out <scratch folder> [-Wiki] [-Width 120]
param(
    [Parameter(Mandatory)][string]$Out,
    [switch]$Wiki,
    [int]$Width = 120,
    [string]$Docs = (Join-Path $PSScriptRoot '../../docs'),
    [string]$WikiDir = (Join-Path $PSScriptRoot '../../documentation')
)

$utf8 = [Text.UTF8Encoding]::new($false)
$cjkPunct = '，。；：、！？'

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

# TOKENS {T, Sep}: SPLIT AT SINGLE SPACES (Sep ' ') AND, WITH $cjk, AFTER FULLWIDTH PUNCTUATION (Sep '');
# CODE SPANS AND LINKS STAY WHOLE. NULL WHEN THE TEXT HAS DOUBLE OR EDGE SPACES, WHICH A RE-JOIN COULDN'T RESTORE
function Tokens([string]$s, [bool]$cjk) {
    if ($s.Contains('  ') -or $s.StartsWith(' ') -or $s.EndsWith(' ')) { return $null }
    $list = [Collections.Generic.List[object]]::new()
    $sb = [Text.StringBuilder]::new()
    $sep = ''
    $i = 0
    while ($i -lt $s.Length) {
        $ch = $s[$i]
        if ($ch -eq '`') {
            $j = $i; while ($j -lt $s.Length -and $s[$j] -eq '`') { $j++ }
            $run = $j - $i
            $k = $j; $close = -1
            while ($k -lt $s.Length) {
                if ($s[$k] -eq '`') {
                    $e = $k; while ($e -lt $s.Length -and $s[$e] -eq '`') { $e++ }
                    if ($e - $k -eq $run) { $close = $k; break }
                    $k = $e; continue
                }
                $k++
            }
            if ($close -ge 0) { [void]$sb.Append($s.Substring($i, $close + $run - $i)); $i = $close + $run }
            else { [void]$sb.Append($s.Substring($i, $run)); $i = $j }
            continue
        }
        if ($ch -eq '[') {
            $m = [regex]::Match($s.Substring($i), '^\[[^\[\]]+\]\([^)\s]+\)')
            if ($m.Success) { [void]$sb.Append($m.Value); $i += $m.Length; continue }
        }
        if ($ch -eq ' ') { $list.Add([pscustomobject]@{ T = $sb.ToString(); Sep = $sep }); [void]$sb.Clear(); $sep = ' '; $i++; continue }
        [void]$sb.Append($ch); $i++
        if ($cjk -and $cjkPunct.Contains($ch) -and $i -lt $s.Length -and $s[$i] -ne ' ') {
            $list.Add([pscustomobject]@{ T = $sb.ToString(); Sep = $sep }); [void]$sb.Clear(); $sep = ''
        }
    }
    if ($sb.Length) { $list.Add([pscustomobject]@{ T = $sb.ToString(); Sep = $sep }) }
    return , $list
}

function JoinTokens($tokens, [int]$from, [int]$to) {
    $sb = [Text.StringBuilder]::new()
    for ($n = $from; $n -lt $to; $n++) { if ($n -gt $from) { [void]$sb.Append($tokens[$n].Sep) }; [void]$sb.Append($tokens[$n].T) }
    return $sb.ToString()
}

# A TOKEN THAT WOULD OPEN A NEW BLOCK WHEN IT STARTS A LINE
function Risky([string]$t) { return $t -match '^(#{1,6}|[-*+=_]+|\d{1,9}[.)]|>.*|<.*|\|.*|```.*|~~~.*)$' }

# PREFIX OF A LINE (QUOTE MARKERS, INDENT, LIST MARKER) AND THE PREFIX ITS CONTINUATION LINES TAKE
function Prefix([string]$line) {
    $m = [regex]::Match($line, '^(?<bq>(?:> ?)*)(?<ind> *)(?<lm>(?:[-*+]|\d{1,9}[.)]) +)?')
    return [pscustomobject]@{ First = $m.Value; Cont = $m.Groups['bq'].Value + $m.Groups['ind'].Value + (' ' * $m.Groups['lm'].Value.Length) }
}

# LINES THAT NEVER WRAP NOR CONTINUE A PARAGRAPH: HEADINGS, TABLE ROWS, HTML, ALERT MARKERS, HARD BREAKS
function Fixed([string]$l) { return $l -match '^(#{1,6} |\s*\||\s*<|(> ?)*\[!\w+\]\s*$)' -or $l -match '(  |\\)$' }

# GREEDY FILL OF ONE PARAGRAPH; RETURNS THE TOKEN INDEXES THAT START A NEW LINE
function Breaks($tokens, [string]$first, [string]$cont) {
    $lines = [Collections.Generic.List[object]]::new()
    $cur = [Collections.Generic.List[int]]::new()
    $curW = DisplayWidth $first
    for ($n = 0; $n -lt $tokens.Count; $n++) {
        $tw = DisplayWidth $tokens[$n].T
        $sw = $tokens[$n].Sep.Length
        if ($cur.Count -eq 0 -or $curW + $sw + $tw -le $Width) { if ($cur.Count) { $curW += $sw }; $cur.Add($n); $curW += $tw; continue }
        $lines.Add($cur); $cur = [Collections.Generic.List[int]]::new(); $cur.Add($n); $curW = (DisplayWidth $cont) + $tw
    }
    $lines.Add($cur)
    # NEVER START A LINE WITH A BLOCK MARKER: PULL THE PREVIOUS TOKEN DOWN WITH IT
    for ($n = 1; $n -lt $lines.Count; $n++) {
        while ((Risky $tokens[$lines[$n][0]].T) -and $lines[$n - 1].Count -gt 1) {
            $last = $lines[$n - 1][$lines[$n - 1].Count - 1]
            $lines[$n - 1].RemoveAt($lines[$n - 1].Count - 1)
            $lines[$n].Insert(0, $last)
        }
        if (Risky $tokens[$lines[$n][0]].T) { throw "unavoidable block marker at a line start: $($tokens[$lines[$n][0]].T)" }
    }
    $starts = @()
    for ($n = 1; $n -lt $lines.Count; $n++) { $starts += $lines[$n][0] }
    return , $starts
}

function Render([string]$first, [string]$cont, $tokens, $starts) {
    $out = [Collections.Generic.List[string]]::new()
    $bounds = @(0) + $starts + @($tokens.Count)
    for ($n = 0; $n -lt $bounds.Count - 1; $n++) { $out.Add($(if ($n) { $cont } else { $first }) + (JoinTokens $tokens $bounds[$n] $bounds[$n + 1])) }
    return , $out
}

# UNITS: CODE AND FIXED LINES ALONE, BLANK LINES ALONE, AND PARAGRAPHS (A TEXT LINE PLUS ITS CONTINUATION LINES)
function Units([string[]]$lines) {
    $units = [Collections.Generic.List[object]]::new()
    $inCode = $false
    $i = 0
    while ($i -lt $lines.Count) {
        $l = $lines[$i]
        if ($l -match '^\s*(```|~~~)') { $inCode = -not $inCode; $units.Add(@{ Lines = @($l); Text = $false }); $i++; continue }
        if ($inCode -or -not $l.Trim() -or (Fixed $l)) { $units.Add(@{ Lines = @($l); Text = $false }); $i++; continue }
        $pre = Prefix $l
        $group = @($l); $i++
        while ($i -lt $lines.Count -and $lines[$i].Trim() -and $lines[$i] -notmatch '^\s*(```|~~~)' -and -not (Fixed $lines[$i]) -and
                (Prefix $lines[$i]).First -ceq $pre.Cont -and $lines[$i].Length -gt $pre.Cont.Length) { $group += $lines[$i]; $i++ }
        # JOIN THE PARAGRAPH: NO SPACE AFTER FULLWIDTH PUNCTUATION, ONE SPACE ANYWHERE ELSE
        $body = $l.Substring($pre.First.Length).TrimEnd()
        for ($g = 1; $g -lt $group.Count; $g++) {
            $next = $group[$g].Substring($pre.Cont.Length).TrimEnd()
            $body += $(if ($body.Length -and $cjkPunct.Contains($body[-1])) { '' } else { ' ' }) + $next
        }
        $units.Add(@{ Lines = $group; Text = $true; First = $pre.First; Cont = $pre.Cont; Body = $body })
    }
    return , $units
}

# FINAL LINES OF A PARAGRAPH: REFLOWED WHEN A LINE RUNS PAST THE WIDTH, PUNCTUATION BREAKS ONLY WHEN SPACES CAN'T FIT IT
function Reflow($u) {
    if (-not ($u.Lines | Where-Object { (DisplayWidth $_) -gt $Width })) { return $u.Lines }
    foreach ($cjk in $false, $true) {
        $tokens = Tokens $u.Body $cjk
        if ($null -eq $tokens) { return $u.Lines }
        $lines = Render $u.First $u.Cont $tokens (Breaks $tokens $u.First $u.Cont)
        if ($cjk -or -not ($lines | Where-Object { (DisplayWidth $_) -gt $Width })) { return $lines }
    }
}

# TOKEN INDEXES WHERE A PARAGRAPH'S LINES START, SO ANOTHER COPY OF IT (A WIKI PAGE) CAN TAKE THE SAME BREAKS
function Starts($u, $lines) {
    $lines = @($lines)
    $all = Tokens $u.Body $true
    if ($null -eq $all) { return $null }
    $starts = @(); $count = 0
    for ($n = 0; $n -lt $lines.Count; $n++) {
        if ($n) { $starts += $count }
        $part = Tokens $lines[$n].Substring($(if ($n) { $u.Cont.Length } else { $u.First.Length })).TrimEnd() $true
        if ($null -eq $part) { return $null }
        $count += $part.Count
    }
    if ($count -ne $all.Count) { return $null }
    $check = Render $u.First $u.Cont $all $starts
    for ($n = 0; $n -lt $lines.Count; $n++) { if ($check[$n] -cne $lines[$n].TrimEnd()) { return $null } }
    return , $starts
}

function ReadLines([string]$path) {
    $raw = [IO.File]::ReadAllText($path)
    $lines = $raw -split "`r?`n"
    if ($raw.EndsWith("`n")) { $lines = $lines[0..($lines.Count - 2)] }
    return [pscustomobject]@{ Lines = $lines; EndsNl = $raw.EndsWith("`n") }
}

function Save([string]$path, $lines, [bool]$endsNl) {
    New-Item -ItemType Directory -Force (Split-Path $path -Parent) | Out-Null
    [IO.File]::WriteAllBytes($path, $utf8.GetBytes(($lines -join "`n") + $(if ($endsNl) { "`n" } else { '' })))
}

$failed = 0
$norm = { param($s) [regex]::Replace($s, '\]\([^)]*\)', ']()') }
foreach ($doc in Get-ChildItem -Path $Docs -Filter 'DOCS-*.md') {
    $lang = $doc.BaseName.Substring(5)
    $read = ReadLines $doc.FullName
    $units = Units $read.Lines
    $outLines = [Collections.Generic.List[string]]::new()
    $plans = [Collections.Generic.List[object]]::new()
    $long = 0
    foreach ($u in $units) {
        $final = @(if ($u.Text) { Reflow $u } else { $u.Lines })
        foreach ($l in $final) { $outLines.Add($l); if ($u.Text -and (DisplayWidth $l) -gt $Width) { $long++ } }
        $plans.Add(@{ Unit = $u; Final = $final })
    }
    Save (Join-Path $Out "docs/$($doc.Name)") $outLines $read.EndsNl
    $changed = -not (($outLines -join "`n") -ceq ($read.Lines -join "`n"))
    "$(if ($changed) { 'CHANGED  ' } else { 'unchanged' }) docs/$($doc.Name) ($($outLines.Count) lines, $long prose lines still over $Width columns)"
    if (-not $Wiki) { continue }

    # WIKI PAGES OF THIS LANGUAGE HOLD THE SAME PARAGRAPHS (LINK TARGETS ASIDE): GIVE THEM THE DOC'S FINAL BREAKS
    $prefixRe = [regex]::Escape($lang)
    $pageFiles = @(Get-ChildItem -Path $WikiDir -Recurse -Filter "$lang-*.md" | Where-Object { $_.BaseName -match "^$prefixRe-(Home|\d+-.+)$" })
    $ordered = @($pageFiles | Where-Object { $_.BaseName -eq "$lang-Home" }) + @($pageFiles | Where-Object { $_.BaseName -ne "$lang-Home" } |
        Sort-Object { [int]($_.BaseName -replace "^$prefixRe-(\d+)-.*$", '$1') })
    $docText = @($plans | Where-Object { $_.Unit.Lines[0].Trim() })
    $cursor = 0
    try {
        foreach ($page in $ordered) {
            $pread = ReadLines $page.FullName
            $pout = [Collections.Generic.List[string]]::new()
            foreach ($pu in (Units $pread.Lines)) {
                if (-not $pu.Lines[0].Trim()) { foreach ($l in $pu.Lines) { $pout.Add($l) }; continue }
                if ($cursor -ge $docText.Count) { throw "$($page.Name) runs past the end of $($doc.Name)" }
                $p = $docText[$cursor]; $cursor++
                if (-not $pu.Text -or -not $p.Unit.Text) {
                    if ((& $norm ($pu.Lines -join "`n")) -cne (& $norm ($p.Unit.Lines -join "`n"))) { throw "$($page.Name) differs from $($doc.Name) at: $($pu.Lines[0])" }
                    foreach ($l in $pu.Lines) { $pout.Add($l) }; continue
                }
                if ((& $norm $pu.Body) -cne (& $norm $p.Unit.Body)) { throw "$($page.Name) differs from $($doc.Name) at: $($pu.Lines[0])" }
                $starts = Starts $p.Unit $p.Final
                $tokens = Tokens $pu.Body $true
                if ($null -eq $starts -or $null -eq $tokens) { throw "$($page.Name): can't map the line breaks of: $($pu.Lines[0])" }
                foreach ($l in (Render $pu.First $pu.Cont $tokens $starts)) { $pout.Add($l) }
            }
            $rel = [IO.Path]::GetRelativePath($WikiDir, $page.FullName)
            Save (Join-Path $Out "wiki/$rel") $pout $pread.EndsNl
            $pchanged = -not (($pout -join "`n") -ceq ($pread.Lines -join "`n"))
            "$(if ($pchanged) { 'CHANGED  ' } else { 'unchanged' }) wiki/$($rel.Replace('\', '/'))"
        }
        if ($cursor -ne $docText.Count) { throw "the $lang pages cover $cursor of $($docText.Count) blocks of $($doc.Name)" }
    } catch { "FAIL $lang wiki: $($_.Exception.Message) (re-cut the pages from the doc first)"; $failed++ }
}
if ($failed) { exit 1 }
