using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using WoZai.Next.Mesh;
using Xunit;

namespace WoZai.Next.Tests;

// The external Core/PowerShell fixtures must not starve unrelated short-deadline
// pipe tests on small Windows runners. Request concurrency inside each test remains.
[CollectionDefinition("Mesh process fixtures", DisableParallelization = true)]
public sealed class MeshProcessCollection { }

[Collection("Mesh process fixtures")]
public sealed class MeshWireTests
{
    [Fact]
    public async Task ClosingJobTerminatesOnlyTheOwnedChild()
    {
        if (!OperatingSystem.IsWindows()) { Assert.Throws<PlatformNotSupportedException>(()=>CoreJob.Attach(Process.GetCurrentProcess())); return; }
        var path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe");
        using var child=Process.Start(new ProcessStartInfo(path,"-NoProfile -NonInteractive -Command Start-Sleep -Seconds 60"){UseShellExecute=false,CreateNoWindow=true})!;
        try { var job=CoreJob.Attach(child); job.Dispose(); await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5)); Assert.True(child.HasExited); }
        finally { if(!child.HasExited)child.Kill(true); }
    }
    [Theory]
    [InlineData("NOPE\u0001\babcdef12R", "preface_invalid")]
    [InlineData("NMLC\u0002\babcdef12R", "preface_invalid")]
    [InlineData("NMLC\u0001\0", "preface_invalid")]
    [InlineData("NMLC\u0001\babcdef12X", "ready_invalid")]
    [InlineData("NMLC\u0001\b-0000000R", "role_or_label")]
    public async Task MalformedOrWrongRolePrefaceFailsClosed(string wire, string reason)
    {
        using var input=new MemoryStream(Encoding.ASCII.GetBytes(wire)); using var output=new MemoryStream();
        var error = await Assert.ThrowsAsync<InvalidDataException>(()=>MeshWire.HandshakeAsync(input,output,"-aaaaaa",true,CancellationToken.None));
        Assert.Equal(reason,error.Message);
    }
    [Fact]
    public async Task FragmentedAndroidPrefaceLeavesPayloadUntouched()
    {
        // Independent Android NMLC/version/8-byte label/READY golden vector.
        using var input=new FragmentedStream(Convert.FromHexString("4E4D4C430108616263646566313252010203"));
        using var output=new MemoryStream();
        Assert.Equal("abcdef12",await MeshWire.HandshakeAsync(input,output,"-123456",true,CancellationToken.None));
        var tail=new byte[3]; await input.ReadExactlyAsync(tail);
        Assert.Equal(new byte[]{1,2,3},tail);
        Assert.Equal("4E4D4C4301072D31323334353652",Convert.ToHexString(output.ToArray()));
    }
    private sealed class FragmentedStream(byte[] bytes) : MemoryStream(bytes)
    {
        public override ValueTask<int> ReadAsync(Memory<byte> buffer,CancellationToken token=default)=>base.ReadAsync(buffer[..Math.Min(1,buffer.Length)],token);
    }
    [Fact]
    public async Task ThreeNativeCoresUseProductionStreamPumpsAndRelayOffExpires()
    {
        var root=Path.Combine(Path.GetTempPath(),"mesh-wire-"+Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try {
            await using var a=await Node.Start(root,"a"); await using var b=await Node.Start(root,"b",true); await using var c=await Node.Start(root,"c");
            using var stop=new CancellationTokenSource(TimeSpan.FromSeconds(30));
            var ab=Bridge(a.Port,b.Port,"-aaaaaa","bbbbbbbb",stop.Token); var cb=Bridge(c.Port,b.Port,"-cccccc","bbbbbbbb",stop.Token);
            try {
            await a.Wait("neighbor_up"); await c.Wait("neighbor_up");
            await a.Send(new{cmd="send",text="Windows 中文 🌐",ttl=30,hops=4}); var queued=await a.Wait("queued");
            var received=await c.Wait("message"); Assert.Equal(queued.GetProperty("id").GetString(),received.GetProperty("id").GetString());
            Assert.Equal("Windows 中文 🌐",received.GetProperty("body").GetString()); Assert.Equal(3,received.GetProperty("path").GetArrayLength());
            await c.Send(new{cmd="send",text="reverse",ttl=30,hops=4}); var reverse=await c.Wait("queued");
            Assert.Equal(reverse.GetProperty("id").GetString(),(await a.Wait("message")).GetProperty("id").GetString());
            await b.Send(new{cmd="relay",enabled=false}); await b.Wait("relay");
            await a.Send(new{cmd="send",text="blocked",ttl=1,hops=4}); var blocked=(await a.Wait("queued")).GetProperty("id").GetString();
            await b.Wait("message", e=>e.GetProperty("id").GetString()==blocked);
            using(var absent=new CancellationTokenSource(TimeSpan.FromSeconds(1.5))) {
                try { while(true) { var e=await c.Events.Reader.ReadAsync(absent.Token); Assert.False(e.GetProperty("event").GetString()=="message"&&e.GetProperty("id").GetString()==blocked); } }
                catch(OperationCanceledException) when(absent.IsCancellationRequested) { }
            }
            Assert.Equal(blocked,(await a.Wait("expired")).GetProperty("id").GetString());
            } finally { stop.Cancel(); await Task.WhenAll(ab,cb); }
        } finally { Directory.Delete(root,true); }
    }
    private static async Task Bridge(int leftPort,int rightPort,string leftLabel,string rightLabel,CancellationToken token)
    {
        using var listener=new TcpListener(IPAddress.Loopback,0); listener.Start();
        using var left=new TcpClient(); var connect=left.ConnectAsync(IPAddress.Loopback,((IPEndPoint)listener.LocalEndpoint).Port);
        using var right=await listener.AcceptTcpClientAsync(token); await connect; listener.Stop();
        await Task.WhenAll(MeshWire.HandshakeAsync(left.GetStream(),left.GetStream(),leftLabel,true,token),MeshWire.HandshakeAsync(right.GetStream(),right.GetStream(),rightLabel,false,token));
        using var lc=new TcpClient(); using var rc=new TcpClient();
        await lc.ConnectAsync(IPAddress.Loopback,leftPort,token); await rc.ConnectAsync(IPAddress.Loopback,rightPort,token);
        try { await Task.WhenAll(MeshWire.PumpAsync(lc.GetStream(),left.GetStream(),token),MeshWire.PumpAsync(left.GetStream(),lc.GetStream(),token),MeshWire.PumpAsync(rc.GetStream(),right.GetStream(),token),MeshWire.PumpAsync(right.GetStream(),rc.GetStream(),token)); }
        catch(Exception e) when(token.IsCancellationRequested && e is OperationCanceledException or IOException or SocketException) { }
    }
    private sealed class Node : IAsyncDisposable
    {
        private Process _process=null!; private Task _reader=Task.CompletedTask,_stderr=Task.CompletedTask;
        public readonly Channel<JsonElement> Events=Channel.CreateBounded<JsonElement>(128);
        public int Port {get;private set;}
        public static async Task<Node> Start(string root,string name,bool relay=false)
        {
            string path=Environment.GetEnvironmentVariable("WOZAI_MESH_CORE")??throw new InvalidOperationException("Build with windows-next/tools/build.ps1 -Test to provide the native Mesh Core");
            var start=new ProcessStartInfo(path){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=new UTF8Encoding(false,true),StandardInputEncoding=new UTF8Encoding(false,true)};
            foreach(var arg in new[]{"run","--state",Path.Combine(root,name),"--listen","127.0.0.1:0"})start.ArgumentList.Add(arg);
            if(relay)start.ArgumentList.Add("--relay");
            var node=new Node{_process=Process.Start(start)!};
            node._reader=Task.Run(async()=>{try{while(await node._process.StandardOutput.ReadLineAsync() is {} line){using var j=JsonDocument.Parse(line);await node.Events.Writer.WriteAsync(j.RootElement.Clone());}}finally{node.Events.Writer.TryComplete();}});
            node._stderr=node._process.StandardError.ReadToEndAsync();
            var ready=await node.Wait("ready");node.Port=IPEndPoint.Parse(ready.GetProperty("address").GetString()!).Port;return node;
        }
        public async Task Send(object command){await _process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(command));await _process.StandardInput.FlushAsync();}
        public async Task<JsonElement> Wait(string type,Func<JsonElement,bool>? predicate=null){using var stop=new CancellationTokenSource(TimeSpan.FromSeconds(12));while(true){var value=await Events.Reader.ReadAsync(stop.Token);if(value.GetProperty("event").GetString()==type && (predicate is null || predicate(value)))return value;}}
        public async ValueTask DisposeAsync(){try{await Send(new{cmd="quit"});await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3));}catch{if(!_process.HasExited){_process.Kill(true);await _process.WaitForExitAsync();}}await Task.WhenAll(_reader,_stderr);_process.Dispose();}
    }
}
