using WoZai.Next.Services;
using Xunit;

public sealed class BleValueStreamTests
{
    [Theory]
    [InlineData(20)]
    [InlineData(185)]
    [InlineData(512)]
    public async Task PartialValuesPreserveLargePayloadAndRingWrap(int mtu)
    {
        using var receiver = new BleValueStream(mtu, (_, _) => Task.CompletedTask, () => { });
        using var sender = new BleValueStream(mtu, (value, _) => {
            Assert.InRange(value.Length, 2, mtu); Assert.Equal(0, value[0]);
            Assert.True(receiver.ReceiveValue(value)); return Task.CompletedTask;
        }, () => { });
        var bytes = Enumerable.Range(0, 48000).Select(i => (byte)i).ToArray();
        for (int round = 0; round < 3; round++)
        {
            await sender.WriteAsync(bytes);
            var read = new byte[bytes.Length]; await receiver.ReadExactlyAsync(read);
            Assert.Equal(bytes, read);
        }
    }
    [Fact]
    public async Task RemoteCloseDrainsEarlierValues()
    {
        using var stream = new BleValueStream(20, (_, _) => Task.CompletedTask, () => { });
        Assert.True(stream.ReceiveValue(new byte[] { 0, 42, 43 }));
        Assert.True(stream.ReceiveValue(new byte[] { 1 }));
        var bytes = new byte[2]; await stream.ReadExactlyAsync(bytes); Assert.Equal(new byte[] { 42, 43 }, bytes);
        await Assert.ThrowsAsync<IOException>(async () => await stream.ReadAsync(bytes));
    }
    [Fact]
    public async Task MalformedOrOverflowInputClosesWithoutUnboundedGrowth()
    {
        foreach (var value in new[] { Array.Empty<byte>(), new byte[] { 0 }, new byte[] { 2, 3 }, new byte[513] })
        {
            using var stream = new BleValueStream(20, (_, _) => Task.CompletedTask, () => { });
            Assert.False(stream.ReceiveValue(value));
            await Assert.ThrowsAsync<IOException>(async () => await stream.ReadAsync(new byte[1]));
        }
        using var full = new BleValueStream(20, (_, _) => Task.CompletedTask, () => { });
        for (int i = 0; i < 128; i++) Assert.True(full.ReceiveValue(new byte[512]));
        Assert.False(full.ReceiveValue(new byte[512]));
        await Assert.ThrowsAnyAsync<Exception>(async () => await full.WriteAsync(new byte[1]));
    }
    [Fact]
    public async Task CancellationRemoteCloseAndFailureWakeReadersAndRejectWrites()
    {
        using var stream = new BleValueStream(20, (_, _) => Task.CompletedTask, () => { });
        using var stop = new CancellationTokenSource();
        var read = stream.ReadAsync(new byte[1], stop.Token).AsTask(); stop.Cancel();
        await Assert.ThrowsAnyAsync<Exception>(() => read.WaitAsync(TimeSpan.FromSeconds(1)));
        await Assert.ThrowsAnyAsync<Exception>(async () => await stream.WriteAsync(new byte[1]));
        using var remote = new BleValueStream(20, (_, _) => Task.CompletedTask, () => { });
        var pending = remote.ReadAsync(new byte[1]).AsTask(); Assert.True(remote.ReceiveValue(new byte[] { 1 }));
        await Assert.ThrowsAsync<IOException>(() => pending.WaitAsync(TimeSpan.FromSeconds(1)));
    }
}
