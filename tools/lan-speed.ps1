# Random-data LAN baseline. Windows PowerShell 5.1; no installation or settings changes.
# Stop with Ctrl+C. Only synthetic bytes are transferred, over ordinary HTTP.
param([string]$ListenAddress = '', [int]$Port = 0, [int]$MaxRequests = 0)
$ErrorActionPreference = 'Stop'
function Test-LocalAddress([System.Net.IPAddress]$Address) {
    $a = $Address.GetAddressBytes()
    return $a.Length -eq 4 -and ($a[0] -eq 127 -or $a[0] -eq 10 -or
        ($a[0] -eq 172 -and $a[1] -ge 16 -and $a[1] -le 31) -or
        ($a[0] -eq 192 -and $a[1] -eq 168))
}
if (!$ListenAddress) {
    $candidates = @(Get-NetIPConfiguration | Where-Object {
        $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up'
    } | ForEach-Object { $_.IPv4Address.IPAddress } | Where-Object {
        Test-LocalAddress ([System.Net.IPAddress]::Parse($_))
    } | Select-Object -Unique)
    if ($candidates.Count -eq 0) { throw 'No active private IPv4 connection found.' }
    if ($candidates.Count -eq 1) { $ListenAddress = $candidates[0] }
    else {
        for ($i = 0; $i -lt $candidates.Count; $i++) { Write-Host "$($i + 1): $($candidates[$i])" }
        $choice = [int](Read-Host 'Choose your router Wi-Fi address number')
        if ($choice -lt 1 -or $choice -gt $candidates.Count) { throw 'Invalid selection.' }
        $ListenAddress = $candidates[$choice - 1]
    }
}
$ip = [System.Net.IPAddress]::Parse($ListenAddress)
if (!(Test-LocalAddress $ip)) { throw 'Only private IPv4 or loopback addresses are supported.' }
if ($Port -lt 0 -or $Port -gt 65535 -or $MaxRequests -lt 0) { throw 'Invalid server parameters.' }
$size = 64 * 1024 * 1024
$block = New-Object byte[] (128 * 1024)
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($block) } finally { $rng.Dispose() }
$token = [Guid]::NewGuid().ToString('N')
$prefix = '/' + $token
$html = @'
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>局域网测速</title><style>body{font:17px system-ui;max-width:540px;margin:35px auto;padding:0 20px;color:#183b30}button{padding:14px;margin:8px 0;background:#216c50;color:white;border:0;border-radius:12px;font-size:17px;width:100%}button:disabled{opacity:.5}pre{white-space:pre-wrap;background:#eef5f0;padding:16px;border-radius:12px}small{line-height:1.6;display:block;color:#54675e}</style>
<h1>局域网测速</h1><p>每次 64 MiB，请保持页面在前台。</p><button id="down">测电脑 → 手机</button><button id="up">测手机 → 电脑</button><pre id="result">准备就绪</pre><small>只传随机测试数据，不读写个人文件。此处测普通 HTTP；“我在”的加密状态请看 App 诊断。完成后关闭电脑 PowerShell 窗口。</small>
<script>
const size=64*1024*1024, prefix=location.pathname.replace(/\/$/,''), results=[];
const box=document.getElementById('result'), buttons=[document.getElementById('down'),document.getElementById('up')];
function status(s){box.textContent=results.concat(s).join('\n\n');}
async function run(kind){
 buttons.forEach(b=>b.disabled=true);let timer,controller=new AbortController();
 try{
  let payload;
  if(kind==='up'){
   status('正在准备测试数据…');payload=new Uint8Array(size);const seed=new Uint8Array(65536);crypto.getRandomValues(seed);
   for(let i=0;i<size;i+=seed.length)payload.set(seed,i);
  }
  status('正在测速…');const start=performance.now();let bytes=0;
  timer=setInterval(()=>status((kind==='down'?'电脑 → 手机':'手机 → 电脑')+'：'+(bytes/1048576).toFixed(1)+' / 64 MiB'),400);
  const deadline=setTimeout(()=>controller.abort(),600000);
  try{
   if(kind==='down'){
    const response=await fetch(prefix+'/download',{cache:'no-store',signal:controller.signal});
    if(!response.ok)throw new Error('HTTP '+response.status);
    const reader=response.body.getReader();while(true){const r=await reader.read();if(r.done)break;bytes+=r.value.byteLength;}
    if(bytes!==size)throw new Error('数据未传完整');
   }else{
    await new Promise((resolve,reject)=>{
     const xhr=new XMLHttpRequest();xhr.open('POST',prefix+'/upload');xhr.timeout=600000;
     xhr.upload.onprogress=e=>{bytes=e.loaded};xhr.onerror=()=>reject(new Error('网络连接失败'));
     xhr.ontimeout=()=>reject(new Error('测速超时'));xhr.onabort=()=>reject(new Error('测速已中止'));
     xhr.onload=()=>{try{if(xhr.status!==200)throw new Error('HTTP '+xhr.status);const r=JSON.parse(xhr.responseText);if(r.bytes!==size)throw new Error('数据未传完整');bytes=r.bytes;resolve();}catch(e){reject(e)}};
     xhr.send(payload);
    });
   }
   const seconds=(performance.now()-start)/1000, label=kind==='down'?'电脑 → 手机':'手机 → 电脑';
   results.push(label+'：'+(64/seconds).toFixed(2)+' MiB/s\n64 MiB 完整传输，'+seconds.toFixed(2)+' 秒');status('');
  }finally{clearTimeout(deadline)}
 }catch(e){status('失败：'+e.message+'。请确认同一 Wi-Fi、页面保持前台；重试时重新打开页面。')}
 finally{clearInterval(timer);buttons.forEach(b=>b.disabled=false)}
}
buttons[0].onclick=()=>run('down');buttons[1].onclick=()=>run('up');
</script></html>
'@
$page = [System.Text.Encoding]::UTF8.GetBytes($html)
function Write-Headers($Stream, [int]$Code, [string]$Type, [long]$Length) {
    $reason = if ($Code -eq 200) { 'OK' } else { 'Bad Request' }
    $headers = "HTTP/1.1 $Code $reason`r`nContent-Type: $Type`r`nContent-Length: $Length`r`nCache-Control: no-store`r`nConnection: close`r`nX-Content-Type-Options: nosniff`r`n`r`n"
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($headers)
    $Stream.Write($bytes, 0, $bytes.Length)
}
$listener = New-Object System.Net.Sockets.TcpListener($ip, $Port)
$listener.Start(4)
$actualPort = $listener.LocalEndpoint.Port
$expires = [DateTime]::UtcNow.AddMinutes(20)
$requests = 0
Write-Host "Open this address in your phone browser: http://${ListenAddress}:${actualPort}${prefix}/"
Write-Host 'Random data only. Close this window or press Ctrl+C when finished. Expires in 20 minutes.'
try {
    while ([DateTime]::UtcNow -lt $expires -and ($MaxRequests -eq 0 -or $requests -lt $MaxRequests)) {
        if (!$listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
        $client = $listener.AcceptTcpClient()
        $requests++
        try {
            if (!(Test-LocalAddress $client.Client.RemoteEndPoint.Address)) { continue }
            $client.NoDelay = $true
            $stream = $client.GetStream()
            $stream.ReadTimeout = 15000
            $stream.WriteTimeout = 15000
            $header = New-Object System.Collections.Generic.List[byte]
            while ($true) {
                $b = $stream.ReadByte()
                if ($b -lt 0) { throw 'Incomplete HTTP headers.' }
                $header.Add([byte]$b)
                $n = $header.Count
                if ($n -gt 8192) { throw 'HTTP headers too large.' }
                if ($n -ge 4 -and $header[$n-4] -eq 13 -and $header[$n-3] -eq 10 -and $header[$n-2] -eq 13 -and $header[$n-1] -eq 10) { break }
            }
            $lines = [System.Text.Encoding]::ASCII.GetString($header.ToArray()) -split "`r`n"
            $request = $lines[0] -split ' '
            if ($request.Count -ne 3 -or $request[2] -ne 'HTTP/1.1') { throw 'Invalid request.' }
            $method = $request[0]; $path = $request[1]
            $length = -1L; $lengthSeen = $false
            foreach ($line in $lines | Select-Object -Skip 1) {
                if ($line -match '^Content-Length:\s*(\d+)\s*$') {
                    if ($lengthSeen) { throw 'Duplicate Content-Length.' }
                    $length = [long]$Matches[1]; $lengthSeen = $true
                }
                elseif ($line -match '^Transfer-Encoding:') { throw 'Chunked requests are unsupported.' }
            }
            if ($method -eq 'GET' -and $path -eq "$prefix/") {
                Write-Headers $stream 200 'text/html; charset=utf-8' $page.Length
                $stream.Write($page, 0, $page.Length)
            }
            elseif ($method -eq 'GET' -and $path -eq "$prefix/download") {
                Write-Headers $stream 200 'application/octet-stream' $size
                $watch = [System.Diagnostics.Stopwatch]::StartNew()
                for ($sent = 0; $sent -lt $size; $sent += $block.Length) { $stream.Write($block, 0, $block.Length) }
                $watch.Stop()
                Write-Host 'Download bytes queued; use the PHONE result for completed-transfer speed.'
            }
            elseif ($method -eq 'POST' -and $path -eq "$prefix/upload" -and $length -eq $size) {
                $watch = [System.Diagnostics.Stopwatch]::StartNew()
                $received = 0L
                while ($received -lt $size) {
                    $n = $stream.Read($block, 0, [int][Math]::Min($block.Length, $size - $received))
                    if ($n -le 0) { throw 'Incomplete upload.' }
                    $received += $n
                }
                $watch.Stop()
                $json = [System.Text.Encoding]::UTF8.GetBytes('{"bytes":' + $received + '}')
                Write-Headers $stream 200 'application/json' $json.Length
                $stream.Write($json, 0, $json.Length)
                Write-Host 'Upload fully received; use the PHONE result for completed-transfer speed.'
            }
            else { Write-Headers $stream 400 'text/plain' 0 }
        }
        catch {
            Write-Host ('Request stopped: ' + $_.Exception.Message)
            try { Write-Headers $stream 400 'text/plain' 0 } catch { }
        }
        finally { $client.Close() }
    }
}
finally { $listener.Stop() }
