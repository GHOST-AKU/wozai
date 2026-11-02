using System.Text;
using System.Text.Json;

Console.InputEncoding = new UTF8Encoding(false);
Console.OutputEncoding = new UTF8Encoding(false);
var mode = args[0];
// Startup failure exercises the same redirected-stdio EOF boundary as a missing JVM dependency.
if (mode == "startup-eof") return;
while (await Console.In.ReadLineAsync() is { } line)
{
    using var document = JsonDocument.Parse(line);
    var request = document.RootElement;
    var id = request.GetProperty("id").GetString();
    var op = request.GetProperty("op").GetString();
    if (op == "snapshot")
    {
        var profile = args[1];
        await Console.Out.WriteLineAsync(JsonSerializer.Serialize(new { id, ok = true, data = new { localId = "fixture", nickname = "Fixture", dataPath = profile } }));
    }
    else if (op == "pid")
    {
        await Console.Out.WriteLineAsync(JsonSerializer.Serialize(new { id, ok = true, data = Environment.ProcessId }));
    }
    else if (op == "crash") return;
    else if (op == "quit")
    {
        await Console.Out.WriteLineAsync(JsonSerializer.Serialize(new { id, ok = true, data = (object?)null }));
        if (mode != "ignore-quit" && mode != "stubborn") return;
    }
    else
    {
        await Console.Out.WriteLineAsync(JsonSerializer.Serialize(new { id, ok = true, data = "ok" }));
    }
}

// A stalled shutdown proves the parent has a bounded, owned-process termination fallback.
if (mode == "stubborn") await Task.Delay(Timeout.InfiniteTimeSpan);
