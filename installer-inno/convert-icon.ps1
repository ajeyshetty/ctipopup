Add-Type -AssemblyName System.Drawing

# Load the PNG image
$img = [System.Drawing.Image]::FromFile((Resolve-Path 'icon.png'))

# Convert to icon
$ico = [System.Drawing.Icon]::FromHandle($img.GetHicon())

# Save as ICO file
$stream = [System.IO.FileStream]::new('icon.ico', [System.IO.FileMode]::Create)
$ico.Save($stream)
$stream.Close()
$img.Dispose()

Write-Host "Icon converted successfully!"
