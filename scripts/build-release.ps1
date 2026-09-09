param([switch]$CreateKey)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
Set-Location $projectDir
$keyFile = Join-Path $projectDir '.signing/release.jks'
$passwordFile = Join-Path $projectDir '.signing/password.dpapi'
if ($CreateKey) {
    if ((Test-Path $keyFile) -or (Test-Path $passwordFile)) { throw 'Signing files already exist. Never overwrite the release key.' }
    New-Item -ItemType Directory -Force .signing | Out-Null
    $randomBytes = [byte[]]::new(32)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $generatedPassword = [Convert]::ToBase64String($randomBytes)
    ConvertTo-SecureString $generatedPassword -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $passwordFile
    $env:SIGNING_STORE_PASSWORD = $generatedPassword
    $env:SIGNING_KEY_PASSWORD = $generatedPassword
    & 'C:\Program Files\Java\jdk-21\bin\keytool.exe' -genkeypair -keystore $keyFile -storetype PKCS12 -alias simple-app -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=ThorSheep, OU=Simple App' -storepass:env SIGNING_STORE_PASSWORD -keypass:env SIGNING_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
}
if (!(Test-Path $keyFile) -or !(Test-Path $passwordFile)) { throw 'Release signing files are missing.' }
$securePassword = (Get-Content $passwordFile -Raw).Trim() | ConvertTo-SecureString
$env:SIGNING_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
$env:SIGNING_KEY_PASSWORD = $env:SIGNING_STORE_PASSWORD
$env:SIGNING_STORE_FILE = $keyFile
$env:SIGNING_KEY_ALIAS = 'simple-app'
try {
    New-Item -ItemType Directory -Force tools/sockets | Out-Null
    $env:GRADLE_USER_HOME = Join-Path $projectDir 'tools/gradle-home'
    $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$projectDir/tools/sockets"
    & ./gradlew.bat --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
    $gradleText = Get-Content (Join-Path $projectDir 'app/build.gradle.kts') -Raw
    $version = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($version)) { throw 'Could not read versionName from app/build.gradle.kts.' }
    New-Item -ItemType Directory -Force dist | Out-Null
    Copy-Item app/build/outputs/apk/release/app-release.apk (Join-Path $projectDir "dist/simple-app-$version.apk") -Force
} finally {
    Remove-Item Env:SIGNING_STORE_PASSWORD,Env:SIGNING_KEY_PASSWORD,Env:SIGNING_STORE_FILE,Env:SIGNING_KEY_ALIAS -ErrorAction SilentlyContinue
    $generatedPassword = $null
}
