param(
    [string]$JavaPath = 'C:\Program Files\Android\Android Studio1\jbr\bin\java.exe',
    [string]$ApkSignerPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\36.1.0\lib\apksigner.jar'),
    [string]$UnsignedApk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release-unsigned.apk'),
    [string]$OutputApk,
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '..\release-signing')
)
$ErrorActionPreference = 'Stop'
if (-not $OutputApk) {
    $apk = Get-Item -LiteralPath $UnsignedApk
    $metadataPath = Join-Path $apk.DirectoryName 'output-metadata.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $entry = $metadata.elements | Where-Object { $_.outputFile -eq $apk.Name } | Select-Object -First 1
    if ($entry.versionName -notmatch '^\d+\.\d+\.\d+$') {
        throw 'Build a release APK with a major.minor.patch version, or pass -OutputApk explicitly.'
    }
    $OutputApk = Join-Path $apk.DirectoryName "AIDefend-ver_$($entry.versionName)-release.apk"
}
Add-Type -AssemblyName System.Security
$signingPath = (Get-Item -LiteralPath $SigningDirectory).FullName
$releaseEncrypted = [Convert]::FromBase64String(
    [System.IO.File]::ReadAllText((Join-Path $signingPath 'password.dpapi')).Trim())
$releaseSecret = [System.Security.Cryptography.ProtectedData]::Unprotect(
    $releaseEncrypted, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
$previousSigningPassword = $env:GLASSES_RELEASE_KEY_PASSWORD
try {
    $env:GLASSES_RELEASE_KEY_PASSWORD = [System.Text.Encoding]::UTF8.GetString($releaseSecret)
    & $JavaPath --enable-native-access=ALL-UNNAMED -jar $ApkSignerPath sign --ks (Join-Path $signingPath 'GlassesScanner-release.p12') --ks-key-alias glassesscanner-release --ks-pass env:GLASSES_RELEASE_KEY_PASSWORD --key-pass env:GLASSES_RELEASE_KEY_PASSWORD --out $OutputApk $UnsignedApk
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signing failed.' }
    & $JavaPath --enable-native-access=ALL-UNNAMED -jar $ApkSignerPath verify --verbose $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Release APK verification failed.' }
    Write-Host "Signed APK: $OutputApk"
} finally {
    $env:GLASSES_RELEASE_KEY_PASSWORD = $previousSigningPassword
    [Array]::Clear($releaseSecret, 0, $releaseSecret.Length)
}
