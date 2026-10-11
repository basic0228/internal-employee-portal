# PowerShell 7. No external API key, account creation or real background-check payload.
[CmdletBinding()]
param(
    [string]$PortalUrl = 'https://internal-employee-portal-production.up.railway.app',
    [PSCredential]$Credential,
    [string]$OutputDirectory,
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 is required.' }
$portalUri = [Uri]$PortalUrl
$production = $portalUri.Scheme -eq 'https' -and $portalUri.Host -eq 'internal-employee-portal-production.up.railway.app' -and $portalUri.Port -eq 443
$localPreview = $portalUri.Scheme -eq 'http' -and $portalUri.Host -in @('127.0.0.1','localhost')
if ((-not $production -and -not $localPreview) -or $portalUri.UserInfo -or $portalUri.Query -or $portalUri.Fragment -or $portalUri.AbsolutePath -ne '/') {
    throw 'Only the deployed portal or an explicit loopback preview is supported.'
}
$origin = $PortalUrl.TrimEnd('/')
if (-not $Execute) { 'DRY RUN: USER login, own-info 200, 12 forbidden probes, CSRF logout proof. No network or credential prompt.'; return }
if (-not $Credential) { $Credential = Get-Credential -Message 'Existing USER account (not saved)' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs/deploy' }
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$results = [Collections.Generic.List[object]]::new()
$runId = [Guid]::NewGuid().ToString()
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
$csrfHeaders = @{}
$loginAttempted = $false; $roleChecked = $false; $complete = $false; $logoutConfirmed = $false
$failureReason = $null
function Send-AccessRequest([string]$Path, [string]$Method = 'GET', $Body = $null, [string]$ContentType = '') {
    $requestArgs = @{ Uri="$origin$Path"; Method=$Method; WebSession=$session; TimeoutSec=20; MaximumRedirection=0; SkipHttpErrorCheck=$true }
    if ($Method -eq 'POST') { $requestArgs.Headers = $csrfHeaders; $requestArgs.Body = $Body }
    if ($ContentType) { $requestArgs.ContentType = $ContentType }
    Invoke-WebRequest @requestArgs
}
function Update-AccessCsrf {
    $response = Send-AccessRequest '/api/auth/csrf'
    if ($response.StatusCode -ne 200) { throw 'CSRF request failed.' }
    $token = $response.Content | ConvertFrom-Json
    if (-not $token.headerName -or -not $token.token) { throw 'CSRF token missing.' }
    $script:csrfHeaders = @{ $token.headerName = $token.token }
}
try {
    Update-AccessCsrf
    $loginAttempted = $true
    $loginBody = @{ username=$Credential.UserName; password=$Credential.GetNetworkCredential().Password }
    $response = Send-AccessRequest '/api/auth/login' 'POST' $loginBody
    $loginBody = $null
    if ($response.StatusCode -ne 204) { $failureReason='LOGIN_FAILED'; throw 'Login failed.' }
    $response = Send-AccessRequest '/api/auth/me'
    if ($response.StatusCode -ne 200) { $failureReason='AUTH_NOT_CONFIRMED'; throw 'Authentication not confirmed.' }
    $identity = $response.Content | ConvertFrom-Json
    if ($identity.role -ne 'USER') { $failureReason='EXPECTED_USER_ROLE'; throw 'A USER account is required.' }
    $ownId = [string]$identity.employeeId
    if ($ownId -notmatch '^EMP-[0-9]{3,}$' -or $ownId.Length -gt 32) { throw 'Unexpected employee identifier.' }
    $identity = $null; $roleChecked = $true
    $response = Send-AccessRequest '/api/me'
    $results.Add(@{scenario='OWN_INFO'; expected=200; status=[int]$response.StatusCode})
    if ($response.StatusCode -ne 200) { $failureReason='OWN_INFO_FAILED'; throw 'Own information unavailable.' }
    Update-AccessCsrf # Authentication rotates CSRF. All probe POSTs use this fresh token.
    $otherId = if ($ownId -eq 'EMP-001') { 'EMP-002' } else { 'EMP-001' }
    foreach ($target in @(@{alias='OWN'; id=$ownId},@{alias='OTHER'; id=$otherId})) {
        $base = "/api/admin/employees/$($target.id)/background-checks"
        $requestProbe = [Guid]::NewGuid().ToString()
        $checkProbe = 'access-probe-' + [Guid]::NewGuid().ToString()
        # Unmapped IDs and invalid create DTO prevent external calls if a permission check regresses.
        $probes = @(
            @{name='HISTORY'; path="$base/history"; method='GET'},
            @{name='DETAIL'; path="$base/$checkProbe"; method='GET'},
            @{name='CANDIDATES'; path="$base/history/$requestProbe/candidates"; method='GET'},
            @{name='CREATE'; path=$base; method='POST'; body='{}'},
            @{name='REFRESH'; path="$base/history/$requestProbe/refresh"; method='POST'; body='{}'},
            @{name='RESOLVE'; path="$base/history/$requestProbe/resolve"; method='POST'; body=(@{checkId=$checkProbe;confirmed=$true}|ConvertTo-Json -Compress)}
        )
        foreach ($probe in $probes) {
            $response = Send-AccessRequest $probe.path $probe.method $probe.body 'application/json'
            $results.Add(@{scenario="$($target.alias)_$($probe.name)"; expected=403; status=[int]$response.StatusCode})
            if ($response.StatusCode -ne 403) { $failureReason='UNEXPECTED_AUTHORIZATION_STATUS'; throw 'Probe failed; stopped.' }
        }
    }
    $complete = $true
} catch {
    # Raw exceptions may include login request data. Save only the controlled reason and status codes.
    if (-not $failureReason) { $failureReason='VERIFICATION_INTERRUPTED' }
} finally {
    $loginBody = $null; $Credential = $null
    if ($loginAttempted) {
        try {
            Update-AccessCsrf
            $response = Send-AccessRequest '/api/auth/logout' 'POST' ''
            $logoutConfirmed = $response.StatusCode -eq 204
        } catch { $logoutConfirmed = $false }
    }
    $session = $null; $response = $null; $identity = $null; $csrfHeaders = $null
}
$report = [ordered]@{
    source=if ($production) {'RAILWAY'} else {'LOCAL_PREVIEW'}; runId=$runId
    startedAt=$startedAt; endedAt=[DateTimeOffset]::UtcNow.ToString('o')
    userRoleConfirmed=$roleChecked; csrfLogoutConfirmed=$logoutConfirmed
    completed=$complete; passed=($complete -and $roleChecked -and $logoutConfirmed)
    failureReason=$failureReason; observations=@($results.ToArray())
}
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)) | Out-Null
$destination = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) "user-access-$runId.json"
[IO.File]::WriteAllText($destination, ($report|ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output "source=$($report.source) passed=$($report.passed) completed=$complete observations=$($results.Count) csrfProof=$logoutConfirmed"
Write-Output $destination
if (-not $report.passed) { throw 'USER verification was incomplete or failed. See the sanitized report; do not treat it as passed.' }
