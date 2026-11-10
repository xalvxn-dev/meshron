# Builds the signed Meshron release: the Google Play bundle (.aab) and the APKs.
# Asks for the signing-key password; it is only kept in memory for this build.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$secure = Read-Host 'Signing key password' -AsSecureString
$env:MESHRON_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
try {
    # The bundle and the per-phone APKs must be built one after the other (split APKs confuse the bundle).
    Remove-Item -Recurse -Force app\build\intermediates\shrunk_resources_proto_format -ErrorAction SilentlyContinue
    .\gradlew.bat :app:bundleRelease
    if ($LASTEXITCODE -eq 0) { .\gradlew.bat :app:assembleRelease }
    if ($LASTEXITCODE -eq 0) {
        Write-Host ''
        Write-Host 'Done. Signed files:' -ForegroundColor Green
        Write-Host '  Google Play:  app\build\outputs\bundle\release\app-release.aab'
        Get-ChildItem app\build\outputs\apk\release\*.apk | ForEach-Object { Write-Host ('  APK:          ' + $_.FullName) }
    } else {
        Write-Host 'Build failed. If it mentions the keystore or password, the password was wrong: run this again.' -ForegroundColor Red
    }
} finally {
    Remove-Item Env:\MESHRON_KEY_PASSWORD -ErrorAction SilentlyContinue
}
