using System.Text;

namespace WoZai.Next.Mesh;

public static class MeshWire
{
    public static readonly Guid Service = new("27a34e22-69d1-4d97-93a4-12f231df9ab8");
    public static bool ValidLabel(string value) => value.Length is >= 1 and <= 8 && value.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');
    public static async Task<string> HandshakeAsync(Stream input, Stream output, string local, bool outbound, CancellationToken token)
    {
        if (!ValidLabel(local)) throw new InvalidDataException("label_bound");
        var bytes = Encoding.ASCII.GetBytes(local);
        await output.WriteAsync(new byte[] { 78, 77, 76, 67, 1, (byte)bytes.Length }, token);
        await output.WriteAsync(bytes, token);
        await output.FlushAsync(token);
        var header = new byte[6]; await input.ReadExactlyAsync(header, token);
        if (!header.AsSpan(0, 5).SequenceEqual(new byte[] { 78, 77, 76, 67, 1 }) || header[5] is < 1 or > 8)
            throw new InvalidDataException("preface_invalid");
        var name = new byte[header[5]]; await input.ReadExactlyAsync(name, token);
        if (name.Any(b => b > 127)) throw new InvalidDataException("preface_invalid");
        var remote = Encoding.ASCII.GetString(name);
        if (!ValidLabel(remote) || (outbound ? string.CompareOrdinal(local, remote) >= 0 : string.CompareOrdinal(remote, local) >= 0))
            throw new InvalidDataException("role_or_label");
        await output.WriteAsync(new byte[] { 82 }, token); await output.FlushAsync(token);
        var ready = new byte[1]; await input.ReadExactlyAsync(ready, token);
        if (ready[0] != 82) throw new InvalidDataException("ready_invalid");
        return remote;
    }
    public static async Task PumpAsync(Stream source, Stream destination, CancellationToken token)
    {
        var buffer = new byte[4096];
        while (true)
        {
            int count = await source.ReadAsync(buffer, token);
            if (count == 0) return;
            using var write = CancellationTokenSource.CreateLinkedTokenSource(token);
            write.CancelAfter(TimeSpan.FromSeconds(5));
            await destination.WriteAsync(buffer.AsMemory(0, count), write.Token);
            await destination.FlushAsync(write.Token);
        }
    }
}
