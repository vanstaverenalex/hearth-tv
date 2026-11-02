# Hearth TV: take clean screenshots of every screen in light and dark mode. Saves PNGs in hearth-tv-setup\shots.
# The TV's backlight and your saved light/dark setting are left alone.
param([string]$TvIp = "192.168.0.32")
$ErrorActionPreference = "Continue"
$dir = Join-Path $env:USERPROFILE "Downloads\hearth-tv-setup"
$out = Join-Path $dir "shots"
New-Item -ItemType Directory -Force -Path $out | Out-Null
Start-Transcript -Path (Join-Path $dir "screenshots-log.txt") -Force | Out-Null
$adb = Join-Path $dir "platform-tools\adb.exe"
$target = "$TvIp`:5555"
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
    & $adb connect $target | Out-Host
    if ((& $adb -s $target get-state 2>$null) -match "device") { $ok = $true; break }
    Start-Sleep -Seconds 3
}
if (-not $ok) { Write-Host "Couldn't connect to the TV." -ForegroundColor Red; Stop-Transcript | Out-Null; Read-Host "Press Enter to close"; exit 1 }
function Shell([string]$cmd) { & $adb -s $target shell $cmd 2>&1 | Out-String }

Write-Host "Installing the latest Hearth TV..."
$apk = Join-Path $dir "HearthTV.apk"
Invoke-WebRequest -UseBasicParsing "https://github.com/vanstaverenalex/hearth-tv/releases/latest/download/HearthTV.apk" -OutFile $apk
& $adb -s $target install -r $apk | Out-Host

$app = "com.vanstaveren.hearthtv/.MainActivity"
function Snap([string]$name) {
    Shell "screencap -p /sdcard/hearth-shot.png" | Out-Null
    & $adb -s $target pull /sdcard/hearth-shot.png (Join-Path $out "$name.png") | Out-Null
    Shell "rm /sdcard/hearth-shot.png" | Out-Null
    Write-Host "  saved $name.png"
}
function Key([string]$k, [int]$n = 1) { for ($i = 0; $i -lt $n; $i++) { Shell "input keyevent $k" | Out-Null; Start-Sleep -Milliseconds 450 } }

foreach ($mode in "dark", "light") {
    Write-Host "--- $mode" -ForegroundColor Cyan
    Shell "am start -n $app --es hearth_shot $mode --es hearth_open home" | Out-Null
    Start-Sleep -Seconds 12; Snap "$mode-1-home"
    Key "KEYCODE_DPAD_LEFT" 2; Start-Sleep -Seconds 2; Snap "$mode-2-home-app"
    Shell "am start -n $app --es hearth_shot $mode --es hearth_open guide" | Out-Null
    Start-Sleep -Seconds 12; Snap "$mode-3-guide"
    Key "KEYCODE_DPAD_DOWN" 3; Start-Sleep -Seconds 4; Snap "$mode-4-guide-scrolled"
    Shell "am start -n $app --es hearth_shot $mode --es hearth_open apps" | Out-Null
    Start-Sleep -Seconds 8; Snap "$mode-5-apps"
    Key "KEYCODE_DPAD_RIGHT" 2; Key "KEYCODE_DPAD_DOWN" 1; Start-Sleep -Seconds 2; Snap "$mode-6-apps-browse"
}
Shell "am start -n $app --es hearth_shot clear --es hearth_open home" | Out-Null
Write-Host ""; Write-Host "Done - $((Get-ChildItem $out -Filter *.png).Count) screenshots in $out. Tell Claude it's finished." -ForegroundColor Green
Stop-Transcript | Out-Null
Start-Sleep -Seconds 5
