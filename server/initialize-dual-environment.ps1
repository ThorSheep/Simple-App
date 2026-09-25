param(
    [Parameter(Mandatory = $true)]
    [string]$ProductionDomain
)

$settingsPath = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw "Cannot find $settingsPath. Create the single-server configuration first."
}

$values = @{}
Get-Content -LiteralPath $settingsPath | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        $values[$matches[1].Trim()] = $matches[2]
    }
}

$testDomain = $values["SYNC_DOMAIN"]
$testPairCode = $values["PAIR_CODE"]
if ([string]::IsNullOrWhiteSpace($testDomain) -or [string]::IsNullOrWhiteSpace($testPairCode)) {
    throw "The existing .env must contain SYNC_DOMAIN and PAIR_CODE."
}

$productionPairCode = $values["PROD_PAIR_CODE"]
if ([string]::IsNullOrWhiteSpace($productionPairCode)) {
    $productionPairCode = [guid]::NewGuid().ToString("N")
}
$original = Get-Content -LiteralPath $settingsPath | Where-Object { $_ -notmatch '^\s*(SYNC_PROD_DOMAIN|PROD_PAIR_CODE)=' }
@($original) + @(
    ""
    "# Production settings used only by compose.dual.yaml."
    "SYNC_PROD_DOMAIN=$ProductionDomain"
    "PROD_PAIR_CODE=$productionPairCode"
) | Set-Content -LiteralPath $settingsPath -Encoding ascii

Write-Host "Test and production settings are ready. The production pairing code is stored in .env."
