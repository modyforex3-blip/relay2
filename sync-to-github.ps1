# PowerShell Script: Automatic Synchronization with GitHub & Render
param(
    [string]$CommitMessage = ""
)

$gitPath = if (Get-Command git -ErrorAction SilentlyContinue) { "git" } else { "C:\Users\Ahmed\mingit\cmd\git.exe" }

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   🚀 GSM Relay Auto-Sync to GitHub & Render              " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Sync release artifacts
Write-Host "[1/4] Synchronizing release APKs and web packages..." -ForegroundColor Yellow
$latestApk = Get-ChildItem -Filter "GSM-Cloud-Relay-Gateway-v*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$latestZip = Get-ChildItem -Filter "GSM-Cloud-Relay-Gateway-v*.zip" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($latestApk) {
    Copy-Item $latestApk.FullName "web-terminal\$($latestApk.Name)" -Force
    Copy-Item $latestApk.FullName "DEPLOY_TO_RENDER\$($latestApk.Name)" -Force
    Write-Host "  -> Synced $($latestApk.Name)" -ForegroundColor Green
}
if ($latestZip) {
    Copy-Item $latestZip.FullName "web-terminal\$($latestZip.Name)" -Force
    Copy-Item $latestZip.FullName "DEPLOY_TO_RENDER\$($latestZip.Name)" -Force
    Write-Host "  -> Synced $($latestZip.Name)" -ForegroundColor Green
}

# 2. Stage all modifications
Write-Host "[2/4] Staging files for Git..." -ForegroundColor Yellow
& $gitPath add -A

# 3. Check status and commit
$status = & $gitPath status --porcelain
if ($status) {
    if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
        $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        $CommitMessage = "Update & Auto-sync: $timestamp [Release v2.7.1]"
    }
    Write-Host "[3/4] Committing changes: '$CommitMessage'..." -ForegroundColor Yellow
    & $gitPath commit -m "$CommitMessage"
} else {
    Write-Host "[3/4] No changes detected to commit." -ForegroundColor Cyan
}

# 4. Push to GitHub
Write-Host "[4/4] Pushing changes to GitHub (origin main)..." -ForegroundColor Yellow
& $gitPath branch -M main
$pushResult = & $gitPath push -u origin main 2>&1
Write-Host $pushResult

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "   ✅ Synchronization Complete! Render will auto-deploy.  " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
