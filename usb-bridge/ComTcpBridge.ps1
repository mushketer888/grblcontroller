<#
.SYNOPSIS
    Bridges a Windows COM port to a TCP socket for Android emulator access.
.DESCRIPTION
    Connects to a real COM port (e.g., Arduino on COM8) and exposes it as a TCP server.
    Android emulator can connect to 10.0.2.2:<Port> to access the serial device.
.NOTES
    Run as Administrator. Close Arduino IDE / serial monitors first.
    Requires .NET 4.7+ (standard on Windows 10).
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$ComPort = 'COM8',
    
    [Parameter()]
    [int]$BaudRate = 115200,
    
    [Parameter()]
    [int]$TcpPort = 12345,
    
    [Parameter()]
    [int]$ReadTimeout = 500,
    
    [Parameter()]
    [int]$WriteTimeout = 500,

    [Parameter()]
    [switch]$LogTraffic
)

$trafficLogPath = Join-Path $PSScriptRoot 'ComTcpBridge.log'
if ($LogTraffic) {
    Set-Content -LiteralPath $trafficLogPath -Value "# COM/TCP traffic log $(Get-Date -Format o)" -Encoding UTF8
}

function Write-Traffic {
    param(
        [string]$Direction,
        [byte[]]$Buffer,
        [int]$Count
    )

    if ($Count -le 0) { return }
    $text = [System.Text.Encoding]::ASCII.GetString($Buffer, 0, $Count)
    $prefix = if ($Direction -eq 'TCP -> Arduino') { '>' } else { '<' }
    foreach ($line in ($text -split "`r?`n")) {
        if ($line.Length -eq 0) { continue }
        $entry = "$prefix $line"
        if ($LogTraffic) { Add-Content -LiteralPath $trafficLogPath -Value $entry -Encoding UTF8 }
        if ($LogTraffic) { Write-Host $entry }
    }
}

# Configure serial port
$serialPort = New-Object System.IO.Ports.SerialPort
$serialPort.PortName = $ComPort
$serialPort.BaudRate = $BaudRate
$serialPort.DataBits = 8
$serialPort.Parity = [System.IO.Ports.Parity]::None
$serialPort.StopBits = [System.IO.Ports.StopBits]::One
$serialPort.Handshake = [System.IO.Ports.Handshake]::None
$serialPort.ReadTimeout = $ReadTimeout
$serialPort.WriteTimeout = $WriteTimeout
$serialPort.DtrEnable = $true
$serialPort.RtsEnable = $true

try {
    $serialPort.Open()
    Write-Host "Opened $ComPort at $BaudRate baud"
} catch {
    $err = $Error[0]
    Write-Error ("Failed to open " + $ComPort + ": " + $err)
    exit 1
}

# TCP listener
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $TcpPort)
$listener.Start()
Write-Host "TCP server listening on 0.0.0.0:$TcpPort"
Write-Host "Android emulator connects to: 10.0.2.2:$TcpPort"
Write-Host "Press Ctrl+C to stop"

try {
    while ($true) {
        Write-Host "Waiting for emulator connection..."
        $client = $listener.AcceptTcpClient()
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        Write-Host "Client connected: $($client.Client.RemoteEndPoint)"
        
        $stream = $client.GetStream()
        $comBuffer = New-Object byte[] 4096
        $tcpBuffer = New-Object byte[] 4096
        
        $stream.ReadTimeout = 50
        $serialPort.ReadTimeout = 50
        $tcpSocket = $client.Client

        while ($serialPort.IsOpen -and $client.Connected) {
            if ($tcpSocket.Poll(1, [System.Net.Sockets.SelectMode]::SelectRead) -and $tcpSocket.Available -eq 0) {
                break
            }

            try {
                $bytesRead = $serialPort.Read($comBuffer, 0, $comBuffer.Length)
                if ($bytesRead -gt 0) {
                    $stream.Write($comBuffer, 0, $bytesRead)
                    $stream.Flush()
                    Write-Traffic 'Arduino -> TCP' $comBuffer $bytesRead
                }
            } catch [System.TimeoutException] {
            } catch {
                break
            }

            if ($stream.DataAvailable) {
                try {
                    $bytesRead = $stream.Read($tcpBuffer, 0, $tcpBuffer.Length)
                    if ($bytesRead -eq 0) { break }
                    $serialPort.Write($tcpBuffer, 0, $bytesRead)
                    Write-Traffic 'TCP -> Arduino' $tcpBuffer $bytesRead
                } catch {
                    break
                }
            }

            [System.Threading.Thread]::Sleep(5)
        }
        
        Write-Host "Connection closed"
        $client.Close()
        [System.Threading.Thread]::Sleep(500)
    }
} finally {
    $listener.Stop()
    if ($serialPort.IsOpen) { $serialPort.Close() }
    Write-Host "Bridge stopped"
}
