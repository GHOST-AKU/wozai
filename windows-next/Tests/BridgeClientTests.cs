using System.Diagnostics;
using WoZai.Next.Services;
using Xunit;

namespace WoZai.Next.Tests;

public sealed class BridgeClientTests
{
    // Stop must close the child owned by this client, while another client stays alive.
    [Fact(Timeout = 10000)]
    public async Task StartupSnapshotsAndStopOwnsOnlyItsChild()
    {
        await using var first = CreateClient("normal");
        await using var second = CreateClient("normal");
        var profile = Path.Combine(Path.GetTempPath(), "wozai-next-test-profile");
        await first.StartAsync(profile);
        await second.StartAsync(profile);
        Assert.Equal("fixture", first.Current?.LocalId);
        Assert.Equal(Path.GetFullPath(profile), first.Current?.DataPath);
        var firstPid = await first.CallAsync<int>("pid");
        var secondPid = await second.CallAsync<int>("pid");
        Assert.NotEqual(firstPid, secondPid);
        await first.StopAsync();
        Assert.False(IsAlive(firstPid));
        Assert.True(IsAlive(secondPid));
        Assert.Equal("ok", await second.CallAsync<string>("refresh"));
        Assert.Null(first.Current);
    }

    [Fact(Timeout = 10000)]
    public async Task EarlyChildExitFailsStartupAndLeavesClientStopped()
    {
        await using var client = CreateClient("startup-eof");
        var notices = new List<string>();
        client.Failed += value => { lock (notices) notices.Add(value); };
        await Assert.ThrowsAsync<IOException>(() => client.StartAsync());
        Assert.Null(client.Current);
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.CallAsync("snapshot"));
        Assert.NotEmpty(notices);
    }

    [Fact(Timeout = 10000)]
    public async Task ChildCrashFailsCallAndPublishesReadableFailure()
    {
        await using var client = CreateClient("normal");
        var failure = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        client.Failed += value => failure.TrySetResult(value);
        await client.StartAsync();
        await Assert.ThrowsAnyAsync<IOException>(() => client.CallAsync("crash"));
        Assert.Contains("kernel", await failure.Task.WaitAsync(TimeSpan.FromSeconds(2)), StringComparison.OrdinalIgnoreCase);
        Assert.Null(client.Current);
    }

    [Fact(Timeout = 10000)]
    public async Task StopClosesStdinWhenChildIgnoresQuit()
    {
        await using var client = CreateClient("ignore-quit");
        await client.StartAsync();
        var pid = await client.CallAsync<int>("pid");
        await client.StopAsync().WaitAsync(TimeSpan.FromSeconds(3));
        Assert.False(IsAlive(pid));
    }

    [Fact(Timeout = 10000)]
    public async Task StopTerminatesOwnedChildWhenItIgnoresQuitAndEof()
    {
        await using var client = CreateClient("stubborn");
        await client.StartAsync();
        var pid = await client.CallAsync<int>("pid");
        await client.StopAsync().WaitAsync(TimeSpan.FromSeconds(3));
        Assert.False(IsAlive(pid));
    }

    private static BridgeClient CreateClient(string mode) => new(profile =>
    {
        var configuration = new DirectoryInfo(AppContext.BaseDirectory).Parent!.Name;
        var fixture = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../Fixture/bin", configuration, "net8.0/BridgeFixture.dll"));
        var start = new ProcessStartInfo(Environment.GetEnvironmentVariable("DOTNET_HOST_PATH") ?? "dotnet");
        start.ArgumentList.Add(fixture);
        start.ArgumentList.Add(mode);
        start.ArgumentList.Add(profile);
        return start;
    }, TimeSpan.FromSeconds(2), TimeSpan.FromMilliseconds(500));

    private static bool IsAlive(int pid)
    {
        try { using var process = Process.GetProcessById(pid); return !process.HasExited; }
        catch (ArgumentException) { return false; }
    }
}
