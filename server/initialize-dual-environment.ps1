param(
    [Parameter(Mandatory = $true)]
    [string]$ProductionDomain
)

$settingsPath = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path -LiteralPath $settingsPath)) {
    throw "找不到 $settingsPath。請先依照 README 建立單一伺服器設定。"
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
    throw "現有 .env 必須包含 SYNC_DOMAIN 與 PAIR_CODE。"
}

$productionPairCode = [guid]::NewGuid().ToString("N")
@(
    "SYNC_TEST_DOMAIN=$testDomain"
    "SYNC_PROD_DOMAIN=$ProductionDomain"
    "TEST_PAIR_CODE=$testPairCode"
    "PROD_PAIR_CODE=$productionPairCode"
) | Set-Content -LiteralPath $settingsPath -Encoding utf8

Write-Host "已建立測試與正式環境設定。正式配對碼已安全寫入 .env。"
