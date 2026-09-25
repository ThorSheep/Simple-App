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

$productionPairCode = $values["PROD_PAIR_CODE"]
if ([string]::IsNullOrWhiteSpace($productionPairCode)) {
    $productionPairCode = [guid]::NewGuid().ToString("N")
}
$original = Get-Content -LiteralPath $settingsPath | Where-Object { $_ -notmatch '^\s*(SYNC_PROD_DOMAIN|PROD_PAIR_CODE)=' }
@($original) + @(
    ""
    "# 僅供 compose.dual.yaml 使用的正式環境設定。"
    "SYNC_PROD_DOMAIN=$ProductionDomain"
    "PROD_PAIR_CODE=$productionPairCode"
) | Set-Content -LiteralPath $settingsPath -Encoding utf8

Write-Host "已建立測試與正式環境設定。正式配對碼已安全寫入 .env。"
