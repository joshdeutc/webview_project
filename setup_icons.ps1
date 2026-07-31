$basePath = "c:\Users\jo\Documents\webview_project\app\src\main\res"
$dirs = @("mipmap-mdpi","mipmap-hdpi","mipmap-xhdpi","mipmap-xxhdpi","mipmap-xxxhdpi")
foreach ($d in $dirs) {
    $fullPath = Join-Path $basePath $d
    if (-not (Test-Path $fullPath)) {
        New-Item -ItemType Directory -Path $fullPath -Force | Out-Null
    }
    Write-Output "Created $fullPath"
}

# Copy the generated icon to all mipmap dirs as ic_launcher.png and ic_launcher_round.png
$iconSource = "C:\Users\jo\.gemini\antigravity\brain\7fc36478-8911-4e77-9a28-3f9c36cab2fe\app_icon_1777280772129.png"
foreach ($d in $dirs) {
    $fullPath = Join-Path $basePath $d
    Copy-Item $iconSource (Join-Path $fullPath "ic_launcher.png") -Force
    Copy-Item $iconSource (Join-Path $fullPath "ic_launcher_round.png") -Force
    Write-Output "Copied icon to $d"
}
Write-Output "All done"
