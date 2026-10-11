# PowerShell 7. Dry run by default; all timing choices are explicit measurement conditions.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PortalUrl,
    [Parameter(Mandatory)][ValidateSet('LIST','DETAIL')][string]$Endpoint,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]{1,128}$')][string]$TargetId,
    [Parameter(Mandatory)][ValidateRange(1,3)][int]$Rounds,
    [Parameter(Mandatory)][ValidateRange(1,10)][int]$Repeats,
    [Parameter(Mandatory)][ValidateRange(1,3)][int]$MaxConcurrency,
    [Parameter(Mandatory)][ValidateRange(1,30000)][int]$ConnectTimeoutMs,
    [Parameter(Mandatory)][ValidateRange(1,60000)][int]$RequestTimeoutMs,
    [Parameter(Mandatory)][ValidateRange(1,120000)][int]$DurationMs,
    [Parameter(Mandatory)][ValidateRange(0,10000)][int]$IntervalMs,
    [Parameter(Mandatory)][ValidateRange(0,60000)][int]$RoundGapMs,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('EXTERNAL','LOCAL_MOCK')][string]$ExpectedSource = 'EXTERNAL',
    [switch]$ExecuteApprovedPlan,
    [PSCredential]$Credential,
    [SecureString]$CandidateKey
)
$ErrorActionPreference = 'Stop'
$portalUri = [Uri]$PortalUrl
$isProduction = $portalUri.Scheme -eq 'https' -and $portalUri.Host -eq 'internal-employee-portal-production.up.railway.app' -and $portalUri.Port -eq 443
$isPreview = $portalUri.Scheme -eq 'http' -and $portalUri.Host -in @('localhost','127.0.0.1')
if ($portalUri.UserInfo -or $portalUri.Query -or $portalUri.Fragment -or $portalUri.AbsolutePath -ne '/' -or
    (-not $isProduction -and -not $isPreview) -or
    ($isProduction -and $ExpectedSource -ne 'EXTERNAL') -or ($isPreview -and $ExpectedSource -ne 'LOCAL_MOCK')) {
    throw 'Only the approved portal origin or an explicitly selected loopback mock is supported.'
}
$plannedPerRun = $Repeats * $MaxConcurrency
$totalBudget = $Rounds * $plannedPerRun
if ($totalBudget -gt 10) { throw 'This small experiment tool caps the whole invocation at 10 application attempts.' }
$origin = $PortalUrl.TrimEnd('/')
$plan = [ordered]@{
    endpoint=$Endpoint; type=if ($MaxConcurrency -eq 1) {'GET_BASELINE'} else {'GET_CONCURRENCY'}
    rounds=$Rounds; repeatsPerPhase=$Repeats; maxConcurrency=$MaxConcurrency
    plannedPerRun=$plannedPerRun; wholeInvocationBudget=$totalBudget
    connectTimeoutMs=$ConnectTimeoutMs; requestTimeoutMs=$RequestTimeoutMs
    durationMs=$DurationMs; intervalMs=$IntervalMs; roundGapMs=$RoundGapMs
    expectedSource=$ExpectedSource; retries='OFF'; post='UNSUPPORTED'
}
$plan | ConvertTo-Json
if (-not $ExecuteApprovedPlan) { Write-Output 'DRY RUN: no network request or credential prompt.'; return }
if (-not $Credential) { $Credential = Get-Credential -Message 'Portal administrator login (not saved)' }
if (-not $CandidateKey) { $CandidateKey = Read-Host 'X-Candidate-Key (not saved)' -AsSecureString }
$plainKey = [Net.NetworkCredential]::new('', $CandidateKey).Password
if ($plainKey -notmatch '^[\x21-\x7E]{1,256}$') { $plainKey=$null; throw 'Invalid key format.' }
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$signedIn = $false
$batchId = [Guid]::NewGuid().ToString()
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
function Get-LabCsrf {
    Invoke-RestMethod -Uri "$origin/api/auth/csrf" -WebSession $session -TimeoutSec 20 -MaximumRedirection 0
}
function Save-LabJson([string]$FileName, $Data) {
    $serialized = $Data | ConvertTo-Json -Depth 50
    if ($serialized -match '"(?:candidateKey|password|password_hash|firstName|lastName|dateOfBirth|employeeId|checkId|email|phone)"\s*:') {
        throw 'Unexpected sensitive fields; refused to save server result.'
    }
    $destination = Join-Path $outputPath $FileName
    if (Test-Path -LiteralPath $destination) { throw 'Result file already exists; refused overwrite.' }
    [IO.File]::WriteAllText($destination, $serialized, [Text.UTF8Encoding]::new($false))
}
try {
    $csrf = Get-LabCsrf
    $headers = @{ $csrf.headerName = $csrf.token }
    $login = Invoke-WebRequest -Uri "$origin/api/auth/login" -Method Post -WebSession $session -Headers $headers `
        -Body @{ username=$Credential.UserName; password=$Credential.GetNetworkCredential().Password } `
        -TimeoutSec 20 -MaximumRedirection 0
    if ($login.StatusCode -ne 204) { throw 'Login did not return 204.' }
    $signedIn = $true
    $capabilities = Invoke-RestMethod -Uri "$origin/api/admin/reliability-lab/capabilities" -WebSession $session -TimeoutSec 20 -MaximumRedirection 0
    if (-not $capabilities.enabled -or $capabilities.source -ne $ExpectedSource -or
        $plannedPerRun -gt $capabilities.maxCalls -or $MaxConcurrency -gt $capabilities.maxConcurrency) {
        throw 'Server capabilities do not match the reviewed plan. No experiment started.'
    }
    [IO.Directory]::CreateDirectory($outputPath) | Out-Null
    for ($round=1; $round -le $Rounds; $round++) {
        $csrf = Get-LabCsrf
        $headers = @{ $csrf.headerName = $csrf.token }
        $request = @{
            candidateKey=$plainKey; type=$plan.type; endpoint=$Endpoint
            employeeId=if ($Endpoint -eq 'LIST') {$TargetId} else {$null}
            checkId=if ($Endpoint -eq 'DETAIL') {$TargetId} else {$null}
            repeats=$Repeats; concurrency=$MaxConcurrency
            connectTimeoutMs=$ConnectTimeoutMs; requestTimeoutMs=$RequestTimeoutMs
            durationMs=$DurationMs; intervalMs=$IntervalMs; callBudget=$plannedPerRun
            # Required DTO fields, unused by GET. These are not polling policy recommendations.
            pollIntervalMs=1; maxPolls=1; postAcknowledged=$false
        }
        $body = [Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json -Compress))
        $response = Invoke-WebRequest -Uri "$origin/api/admin/reliability-lab/experiments" -Method Post `
            -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body `
            -TimeoutSec ([Math]::Ceiling($DurationMs/1000)+30) -MaximumRedirection 0
        $body=$null; $request=$null
        $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
        $events = @($text -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
        $final = @($events | Where-Object type -eq 'result')
        if ($final.Count -ne 1) {
            Save-LabJson "$batchId-round-$round-partial.json" @{ incomplete=$true; source=$ExpectedSource; events=$events }
            throw 'Missing final event. Partial events saved separately; no next round.'
        }
        $result = $final[0].data
        if ($result.run.source -ne $ExpectedSource -or $result.attempts.Count -gt $plannedPerRun) {
            throw 'Unexpected result source or call count. No next round.'
        }
        $result | Add-Member -NotePropertyName collection -NotePropertyValue @{
            batchId=$batchId; round=$round; plannedRounds=$Rounds; totalApplicationBudget=$totalBudget
            targetGroupAlias='target-1'; roundGapMs=$RoundGapMs; automaticRetry=$false
        }
        Save-LabJson "lab-$($result.run.runId).json" $result
        Write-Output "round=$round source=$ExpectedSource run=$($result.run.runId) attempts=$($result.attempts.Count) state=$($result.run.state)"
        $failed = @($result.attempts | Where-Object { $_.outcome -ne 'HTTP_RESPONSE' -or $_.httpCode -ge 400 -or $_.issues.Count -gt 0 })
        if ($result.run.state -ne 'COMPLETED' -or $failed.Count -gt 0) {
            Write-Output 'Observation saved; an error/stop/contract issue prevents further rounds. No automatic retry.'
            break
        }
        if ($round -lt $Rounds -and $RoundGapMs -gt 0) { Start-Sleep -Milliseconds $RoundGapMs }
    }
} catch {
    # Exceptions may carry request details. Never echo the raw exception, request body or credentials.
    throw 'Measurement stopped. Check the sanitized result files and portal logs. No automatic retry was made.'
} finally {
    $body=$null; $request=$null; $plainKey=$null; $CandidateKey=$null; $Credential=$null
    if ($signedIn) {
        try {
            $csrf=Get-LabCsrf; $headers=@{ $csrf.headerName=$csrf.token }
            Invoke-WebRequest -Uri "$origin/api/auth/logout" -Method Post -WebSession $session -Headers $headers -TimeoutSec 20 -MaximumRedirection 0 | Out-Null
        } catch { Write-Warning 'Could not confirm logout of this script session. No credentials were saved.' }
    }
}
