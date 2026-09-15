param(
    [string]$Source = 'C:\Users\tomas\Pictures\LogoAerothink.png',
    [string]$ProjectRoot = 'C:\Soft\gcs-DRON\dron15'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function New-Canvas([int]$Size, [bool]$Transparent) {
    $bitmap = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    if ($Transparent) {
        $graphics.Clear([System.Drawing.Color]::Transparent)
    } else {
        $graphics.Clear([System.Drawing.Color]::FromArgb(255, 2, 8, 18))
    }
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    return @($bitmap, $graphics)
}

function Draw-CenteredMark(
    [System.Drawing.Graphics]$Graphics,
    [System.Drawing.Image]$Image,
    [int]$CanvasSize,
    [int]$TargetWidth
) {
    # Oryginalny znak (bez napisu AEROTHINK) zajmuje gorny fragment grafiki.
    $sourceRect = New-Object System.Drawing.Rectangle(90, 20, 620, 455)
    $targetHeight = [int][Math]::Round($TargetWidth * $sourceRect.Height / $sourceRect.Width)
    $targetX = [int][Math]::Round(($CanvasSize - $TargetWidth) / 2)
    $targetY = [int][Math]::Round(($CanvasSize - $targetHeight) / 2)
    $targetRect = New-Object System.Drawing.Rectangle($targetX, $targetY, $TargetWidth, $targetHeight)
    $Graphics.DrawImage($Image, $targetRect, $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
}

function Save-Png([System.Drawing.Bitmap]$Bitmap, [string]$Path) {
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

$sourceImage = [System.Drawing.Image]::FromFile($Source)
try {
    $graphicsRoot = Join-Path $ProjectRoot 'grafika'
    $resourceRoot = Join-Path $ProjectRoot 'mk32app\app\cockpit\src\main\res'

    # Wersja glowna i podglad: firmowy znak na ciemnym tle.
    $masterParts = New-Canvas 1024 $false
    $master = $masterParts[0]
    $masterGraphics = $masterParts[1]
    try {
        Draw-CenteredMark $masterGraphics $sourceImage 1024 790
        Save-Png $master (Join-Path $graphicsRoot 'APilot_icon_master.png')
    } finally {
        $masterGraphics.Dispose()
    }

    $previewParts = New-Canvas 512 $false
    $preview = $previewParts[0]
    $previewGraphics = $previewParts[1]
    try {
        Draw-CenteredMark $previewGraphics $sourceImage 512 395
        Save-Png $preview (Join-Path $graphicsRoot 'APilot_icon_512.png')
    } finally {
        $previewGraphics.Dispose()
        $preview.Dispose()
    }

    # Warstwa pierwszego planu ikony adaptacyjnej musi miec bezpieczny margines.
    $foregroundParts = New-Canvas 1024 $true
    $foreground = $foregroundParts[0]
    $foregroundGraphics = $foregroundParts[1]
    try {
        Draw-CenteredMark $foregroundGraphics $sourceImage 1024 650
        Save-Png $foreground (Join-Path $resourceRoot 'drawable-nodpi\apilot_icon_art.png')
    } finally {
        $foregroundGraphics.Dispose()
        $foreground.Dispose()
    }

    $legacySizes = [ordered]@{
        'mipmap-mdpi' = 48
        'mipmap-hdpi' = 72
        'mipmap-xhdpi' = 96
        'mipmap-xxhdpi' = 144
        'mipmap-xxxhdpi' = 192
    }

    foreach ($entry in $legacySizes.GetEnumerator()) {
        $size = [int]$entry.Value
        $parts = New-Canvas $size $false
        $icon = $parts[0]
        $iconGraphics = $parts[1]
        try {
            Draw-CenteredMark $iconGraphics $sourceImage $size ([int][Math]::Round($size * 0.77))
            $folder = Join-Path $resourceRoot $entry.Key
            Save-Png $icon (Join-Path $folder 'ic_launcher.png')
            Save-Png $icon (Join-Path $folder 'ic_launcher_round.png')
        } finally {
            $iconGraphics.Dispose()
            $icon.Dispose()
        }
    }

    $master.Dispose()
} finally {
    $sourceImage.Dispose()
}

Write-Output 'APilot icon assets generated from the original Aerothink logo.'
