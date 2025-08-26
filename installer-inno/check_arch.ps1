param($FilePath)

try {
    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    $peOffset = [BitConverter]::ToInt32($bytes, 60)
    $machine = [BitConverter]::ToInt16($bytes, $peOffset + 4)
    
    switch ($machine) {
        0x014c { Write-Output "32-bit (x86)" }
        0x8664 { Write-Output "64-bit (x64)" }
        34404 { Write-Output "64-bit (x64)" }  # 0x8664 in decimal
        default { Write-Output "Unknown architecture (0x$($machine.ToString('X')))" }
    }
} catch {
    Write-Output "Error reading file: $($_.Exception.Message)"
}
