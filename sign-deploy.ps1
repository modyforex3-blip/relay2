Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$sourceApk = "GsmRelayApp\app\build\outputs\apk\release\app-release.apk"
$cleanApk = "GsmRelayApp\app\build\outputs\apk\release\clean-unsigned.apk"
$alignedApk = "GsmRelayApp\app\build\outputs\apk\release\aligned.apk"
$signedApk = "GSM-Cloud-Relay-Gateway-v2.7.1.apk"
$zipFile = "GSM-Cloud-Relay-Gateway-v2.7.1.zip"

Write-Host "Creating clean unsigned apk..."
Copy-Item $sourceApk $cleanApk -Force
$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path $cleanApk).Path, [System.IO.Compression.ZipArchiveMode]::Update)
$entriesToRemove = @()
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -like "META-INF/*" -and -not ($entry.FullName -like "META-INF/services/*")) {
        $entriesToRemove += $entry
    }
}
foreach ($entry in $entriesToRemove) {
    $entry.Delete()
}
$zip.Dispose()

Write-Host "Zipaligning..."
$zipalign = "C:\Users\Ahmed\AppData\Local\Android\Sdk\build-tools\35.0.0\zipalign.exe"
if (Test-Path $alignedApk) { Remove-Item $alignedApk -Force }
& $zipalign -p -f 4 $cleanApk $alignedApk

Write-Host "Signing with apksigner (v1+v2+v3)..."
$apksigner = "C:\Users\Ahmed\AppData\Local\Android\Sdk\build-tools\35.0.0\apksigner.bat"
$keystore = (Resolve-Path "GsmRelayApp\voxlink-release.keystore").Path
$alignedPath = (Resolve-Path $alignedApk).Path
$outPath = Join-Path (Get-Location).Path $signedApk

$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:PATH = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin;" + $env:PATH

cmd /c "`"$apksigner`" sign --ks `"$keystore`" --ks-pass pass:gsmrelay12345 --ks-key-alias voxlink --key-pass pass:gsmrelay12345 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --min-sdk-version 24 --out `"$outPath`" `"$alignedPath`""

Write-Host "Verifying signature..."
cmd /c "`"$apksigner`" verify --verbose `"$outPath`""

Write-Host "Creating zip package..."
if (Test-Path $zipFile) { Remove-Item $zipFile -Force }
Compress-Archive -Path $signedApk -DestinationPath $zipFile -Force

Write-Host "Copying to web-terminal and DEPLOY_TO_RENDER..."
Copy-Item $signedApk "web-terminal\$signedApk" -Force
Copy-Item $zipFile "web-terminal\$zipFile" -Force
Copy-Item $signedApk "DEPLOY_TO_RENDER\$signedApk" -Force
Copy-Item $zipFile "DEPLOY_TO_RENDER\$zipFile" -Force

Write-Host "All tasks completed successfully!"
