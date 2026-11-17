# Builds the signed Play Store bundle (.aab) and copies it to Downloads.
# Asks for the keystore password here (hidden); it is only kept for this build.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$secure = Read-Host 'Keystore password' -AsSecureString
$env:MESHRON_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
try {
  & .\gradlew.bat :app:bundleRelease "-PappId=com.xalvxnlab.meshron" -q
  if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally {
  Remove-Item Env:MESHRON_KEY_PASSWORD -ErrorAction SilentlyContinue
}
$ver = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.+)"').Matches[0].Groups[1].Value
$dest = Join-Path $env:USERPROFILE ("Downloads\Meshron " + $ver)
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item app\build\outputs\bundle\release\app-release.aab (Join-Path $dest ("Meshron-" + $ver + "-PlayStore.aab")) -Force
Write-Host ("Saved to " + $dest)
