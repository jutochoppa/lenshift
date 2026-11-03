# Centers the first visible top-level window whose title contains -Match on its monitor.
param([string]$Match = "Android Emulator")

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinPos {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
}
"@
Add-Type -AssemblyName System.Windows.Forms

$p = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "*$Match*" } | Select-Object -First 1
if (-not $p) { Write-Output "No window matching '$Match'"; exit 1 }
$h = $p.MainWindowHandle
[WinPos]::ShowWindow($h, 9) | Out-Null   # restore if minimised
$r = New-Object WinPos+RECT
[WinPos]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.R - $r.L; $ht = $r.B - $r.T
$screen = [System.Windows.Forms.Screen]::FromHandle($h).WorkingArea
# Too tall for the screen: shrink to 94% of the work-area height, keeping the aspect ratio.
if ($ht -gt $screen.Height) {
  $scale = ($screen.Height * 0.94) / $ht
  $w = [int]($w * $scale); $ht = [int]($ht * $scale)
}
$x = $screen.X + [int](($screen.Width - $w) / 2)
$y = $screen.Y + [int](($screen.Height - $ht) / 2)
[WinPos]::SetWindowPos($h, [IntPtr]::Zero, $x, $y, $w, $ht, 0x0004) | Out-Null  # SWP_NOZORDER
[WinPos]::SetForegroundWindow($h) | Out-Null
Write-Output ("Moved '{0}' ({1}x{2}) to {3},{4} on a {5}x{6} work area" -f $p.MainWindowTitle, $w, $ht, $x, $y, $screen.Width, $screen.Height)
