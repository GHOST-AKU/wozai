using System.Net;
using System.Net.Sockets;
using WoZai.Next.Services;

// This substitutes ATT delivery only; it is not Bluetooth hardware evidence.
using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
using var listener = new TcpListener(IPAddress.Loopback, 0);
listener.Start(1);
await File.WriteAllTextAsync(args[1], ((IPEndPoint)listener.LocalEndpoint).Port.ToString());
using var swift = new TcpClient(); await swift.ConnectAsync(IPAddress.Loopback, int.Parse(args[0]), deadline.Token);
using var kernel = await listener.AcceptTcpClientAsync(deadline.Token);
swift.NoDelay = kernel.NoDelay = true;
using var values = new BleValueStream(185, async (value, token) => {
    byte[] framed = [(byte)(value.Length >> 8), (byte)value.Length, ..value];
    await swift.GetStream().WriteAsync(framed, token);
}, () => { });
var receive = Task.Run(async () => {
    try {
        while (true) {
            byte[] header = new byte[2]; await swift.GetStream().ReadExactlyAsync(header, deadline.Token);
            int size = header[0] * 256 + header[1]; if (size is < 1 or > 512) throw new IOException("Value bound");
            var value = new byte[size]; await swift.GetStream().ReadExactlyAsync(value, deadline.Token);
            if (!values.ReceiveValue(value)) throw new IOException("Invalid value");
        }
    } catch (EndOfStreamException) { values.ReceiveValue(new byte[] { 1 }); }
});
async Task Pump(Stream input, Stream output) {
    var buffer = new byte[4096];
    while (true) { int n = await input.ReadAsync(buffer, deadline.Token); if (n == 0) return; await output.WriteAsync(buffer.AsMemory(0, n), deadline.Token); }
}
var upstream = Pump(kernel.GetStream(), values);
var downstream = Pump(values, kernel.GetStream());
await Task.WhenAny(upstream, downstream);
deadline.Cancel(); values.Dispose(); kernel.Dispose(); swift.Dispose();
try { await Task.WhenAll(receive, upstream, downstream); } catch (Exception) { }
Console.WriteLine("BLE value adapter relay exited; the independent Java and Swift peers assert NIM4 results.");
