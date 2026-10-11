param([string]$PortalUrl = 'http://127.0.0.1:8080', [string]$OutputFile)
$ErrorActionPreference = 'Stop'
$portalUri = [Uri]$PortalUrl
if ($portalUri.Scheme -ne 'http' -or $portalUri.Host -notin @('127.0.0.1', 'localhost') -or
    $portalUri.AbsolutePath -ne '/' -or $portalUri.Query -or $portalUri.UserInfo) {
    throw 'This smoke script only supports a loopback preview server.'
}
$portalOrigin = $PortalUrl.TrimEnd('/')
$portalSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Get-PortalCsrf {
    Invoke-RestMethod -Uri "$portalOrigin/api/auth/csrf" -WebSession $portalSession
}
$portalCsrf = Get-PortalCsrf
$portalHeaders = @{}
$portalHeaders[$portalCsrf.headerName] = $portalCsrf.token
Invoke-WebRequest -UseBasicParsing -Uri "$portalOrigin/api/auth/login" -Method Post -WebSession $portalSession -Headers $portalHeaders `
    -Body @{ username = 'preview-admin'; password = 'PreviewPassword1' } | Out-Null
try {
    $portalCaps = Invoke-RestMethod -Uri "$portalOrigin/api/admin/reliability-lab/capabilities" -WebSession $portalSession
    if ($portalCaps.source -ne 'LOCAL_MOCK' -or -not $portalCaps.enabled) {
        throw 'A LOCAL_MOCK preview is required. No experiment was started.'
    }
    $portalCsrf = Get-PortalCsrf
    $portalHeaders = @{}
    $portalHeaders[$portalCsrf.headerName] = $portalCsrf.token
    # Synthetic input only. External measurements must use an approved plan in the administrator UI.
    $portalBody = @{
        candidateKey = 'preview-test-key'; type = 'GET_BASELINE'; endpoint = 'DETAIL'
        employeeId = 'EMP-001'; checkId = 'CHK-known'; repeats = 3; concurrency = 1
        connectTimeoutMs = 1000; requestTimeoutMs = 3000; durationMs = 10000
        intervalMs = 0; pollIntervalMs = 100; maxPolls = 2; callBudget = 3
        postAcknowledged = $false
    } | ConvertTo-Json
    $portalResponse = Invoke-WebRequest -UseBasicParsing -Uri "$portalOrigin/api/admin/reliability-lab/experiments" `
        -Method Post -ContentType 'application/json' -Body $portalBody -WebSession $portalSession -Headers $portalHeaders
    # PowerShell may treat application/x-ndjson without a charset as binary content.
    $portalText = if ($portalResponse.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($portalResponse.Content)
    } else { [string]$portalResponse.Content }
    $portalEvents = @($portalText -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    $portalFinal = @($portalEvents | Where-Object { $_.type -eq 'result' })
    if ($portalFinal.Count -ne 1 -or $portalFinal[0].data.run.source -ne 'LOCAL_MOCK' -or
        $portalFinal[0].data.run.state -ne 'COMPLETED' -or $portalFinal[0].data.attempts.Count -ne 3) {
        throw 'Unexpected smoke result. Keep partial observations separate from a completed run.'
    }
    $portalData = $portalFinal[0].data
    if ($OutputFile) {
        $portalData | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $OutputFile -Encoding UTF8
    }
    Write-Output "LOCAL_MOCK run=$($portalData.run.runId), attempts=$($portalData.attempts.Count), state=$($portalData.run.state). Not external API measurements."
} finally {
    $portalCsrf = Get-PortalCsrf
    $portalHeaders = @{}
    $portalHeaders[$portalCsrf.headerName] = $portalCsrf.token
    Invoke-WebRequest -UseBasicParsing -Uri "$portalOrigin/api/auth/logout" -Method Post -WebSession $portalSession -Headers $portalHeaders | Out-Null
}
