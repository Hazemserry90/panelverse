Add-Type -AssemblyName System.Drawing

function Make-Cover($title, $subtitle, $bgR, $bgG, $bgB, $titleR, $titleG, $titleB, $outFile) {
    $bmp = New-Object System.Drawing.Bitmap(400, 600)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias

    # Background
    $bg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($bgR, $bgG, $bgB))
    $g.FillRectangle($bg, 0, 0, 400, 600)

    # Decorative border
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb($titleR, $titleG, $titleB), 4)
    $g.DrawRectangle($pen, 15, 15, 370, 570)

    # Title text
    $titleFont = New-Object System.Drawing.Font("Arial", 32, [System.Drawing.FontStyle]::Bold)
    $titleBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb($titleR, $titleG, $titleB))
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $sf.LineAlignment = [System.Drawing.StringAlignment]::Center
    $titleRect = New-Object System.Drawing.RectangleF(30, 220, 340, 160)
    $g.DrawString($title, $titleFont, $titleBrush, $titleRect, $sf)

    # Subtitle text
    $subFont = New-Object System.Drawing.Font("Arial", 20)
    $subBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(200, 200, 220))
    $subRect = New-Object System.Drawing.RectangleF(30, 400, 340, 80)
    $g.DrawString($subtitle, $subFont, $subBrush, $subRect, $sf)

    $g.Dispose()
    $bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $bmp.Dispose()
    Write-Host "Created: $outFile"
}

$base = "$PSScriptRoot\app\src\main\res\drawable"

# Star Wars: dark navy + gold
Make-Cover "STAR WARS" "Darth Vader" 10 15 40 255 200 50 "$base\cover_starwars.jpg"

# Transformers: dark steel + cyan
Make-Cover "TRANSFORMERS" "" 15 20 30 0 200 220 "$base\cover_transformers.jpg"

# TMNT: dark green + bright green
Make-Cover "TEENAGE MUTANT`nNINJA TURTLES" "" 5 25 10 80 220 80 "$base\cover_tmnt.jpg"

Write-Host "All covers generated."
