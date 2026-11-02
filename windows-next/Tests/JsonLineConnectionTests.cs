using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using WoZai.Next.Services;
using Xunit;

namespace WoZai.Next.Tests;

public sealed class JsonLineConnectionTests
{
    // Reversing replies must not reverse the results delivered to concurrent callers.
    [Fact(Timeout = 10000)]
    public async Task RepliesAreCorrelatedAndRequestsUseUtf8CamelCase()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2));
        connection.Start();
        var first = connection.CallAsync<string>("draft", new { PeerId = "peer", Text = "你好" });
        var second = connection.CallAsync<string>("snapshot");
        var request1 = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        var request2 = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        Assert.Equal("你好", request1.RootElement.GetProperty("args").GetProperty("text").GetString());
        Assert.Equal("peer", request1.RootElement.GetProperty("args").GetProperty("peerId").GetString());
        Assert.Equal(3, request1.RootElement.EnumerateObject().Count());
        Assert.Equal(JsonValueKind.Object, request2.RootElement.GetProperty("args").ValueKind);
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request2.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"second\"}}");
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request1.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"first\"}}");
        Assert.Equal("first", await first);
        Assert.Equal("second", await second);
    }

    // Closing stdout while calls wait must release all waiters promptly.
    [Fact(Timeout = 10000)]
    public async Task EofFailsEveryPendingRequest()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(10));
        connection.Start();
        var first = connection.CallAsync<string>("snapshot");
        var second = connection.CallAsync<string>("refresh");
        await pair.Reader.ReadLineAsync();
        await pair.Reader.ReadLineAsync();
        await pair.Server.DisposeAsync();
        await Assert.ThrowsAsync<EndOfStreamException>(() => first.WaitAsync(TimeSpan.FromSeconds(2)));
        await Assert.ThrowsAsync<EndOfStreamException>(() => second.WaitAsync(TimeSpan.FromSeconds(2)));
        await Assert.ThrowsAsync<EndOfStreamException>(() => connection.CallAsync<string>("snapshot"));
    }

    // A malicious or broken child must not grow an unbounded line buffer.
    [Fact(Timeout = 10000)]
    public async Task OversizedUnterminatedFrameFailsPendingRequest()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(10), maxLineBytes: 128);
        connection.Start();
        var pending = connection.CallAsync<string>("snapshot");
        await pair.Reader.ReadLineAsync();
        await pair.Writer.WriteAsync(new string('x', 129));
        await pair.Writer.FlushAsync();
        await Assert.ThrowsAsync<InvalidDataException>(() => pending.WaitAsync(TimeSpan.FromSeconds(2)));
    }

    // Oversized local requests must be rejected without damaging the next request.
    [Fact(Timeout = 10000)]
    public async Task OutboundLimitCountsUtf8BytesAndLeavesConnectionUsable()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2), maxLineBytes: 128);
        connection.Start();
        await Assert.ThrowsAsync<InvalidDataException>(() => connection.CallAsync<string>("draft", new { text = new string('界', 60) }));
        var pending = connection.CallAsync<string>("snapshot");
        using var request = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"alive\"}}");
        Assert.Equal("alive", await pending);
    }

    [Fact(Timeout = 10000)]
    public async Task CancelledRequestDoesNotConsumeAnotherRequestsReply()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2));
        connection.Start();
        using var cancel = new CancellationTokenSource();
        var cancelled = connection.CallAsync<string>("refresh", cancellationToken: cancel.Token);
        using var oldRequest = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => cancelled);
        var next = connection.CallAsync<string>("snapshot");
        using var newRequest = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{oldRequest.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"late\"}}");
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{newRequest.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"current\"}}");
        Assert.Equal("current", await next);
    }

    [Fact(Timeout = 10000)]
    public async Task TimeoutRemovesRequestAndAllowsSubsequentReply()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromMilliseconds(150));
        connection.Start();
        var expired = connection.CallAsync<string>("refresh");
        await pair.Reader.ReadLineAsync();
        await Assert.ThrowsAsync<TimeoutException>(() => expired);
        var next = connection.CallAsync<int>("snapshot");
        using var request = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":7}}");
        Assert.Equal(7, await next);
    }

    [Fact(Timeout = 10000)]
    public async Task InterleavedStateAndNoticeEventsDoNotCompleteRequests()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2));
        var state = new TaskCompletionSource<WoZai.Next.ClientState>(TaskCreationOptions.RunContinuationsAsynchronously);
        var notice = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        connection.StateChanged += value => state.TrySetResult(value);
        connection.Notice += value => notice.TrySetResult(value);
        connection.Start();
        var pending = connection.CallAsync<string>("snapshot");
        using var request = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        await pair.Writer.WriteLineAsync("{\"event\":\"state\",\"data\":{\"localId\":\"local\",\"fontSize\":1,\"generation\":\"session-1\",\"messages\":[{\"id\":\"m\",\"body\":\"hello\",\"attachment\":null}]}}");
        await pair.Writer.WriteLineAsync("{\"event\":\"notice\",\"data\":\"ready\"}");
        Assert.Equal("session-1", (await state.Task.WaitAsync(TimeSpan.FromSeconds(2))).Generation);
        Assert.Equal("ready", await notice.Task.WaitAsync(TimeSpan.FromSeconds(2)));
        Assert.False(pending.IsCompleted);
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request.RootElement.GetProperty("id").GetString()}\",\"ok\":false,\"error\":\"Operation unavailable\"}}");
        var error = await Assert.ThrowsAsync<BridgeCommandException>(() => pending);
        Assert.Equal("Operation unavailable", error.Message);
    }

    [Theory(Timeout = 10000)]
    [InlineData("{bad json}\n")]
    [InlineData("{\"id\":\"1\",\"ok\":\"yes\"}\n")]
    [InlineData("{\"event\":\"state\",\"data\":null}\n")]
    public async Task MalformedFramesFailPendingRequest(string frame)
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2));
        connection.Start();
        var pending = connection.CallAsync<string>("snapshot");
        await pair.Reader.ReadLineAsync();
        await pair.Writer.WriteAsync(frame);
        await pair.Writer.FlushAsync();
        await Assert.ThrowsAsync<InvalidDataException>(() => pending);
    }

    [Fact(Timeout = 10000)]
    public async Task PendingRequestLimitRejectsExcessWork()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2), maxPendingRequests: 2);
        connection.Start();
        using var cancel = new CancellationTokenSource();
        var first = connection.CallAsync<string>("snapshot", cancellationToken: cancel.Token);
        var second = connection.CallAsync<string>("refresh", cancellationToken: cancel.Token);
        await pair.Reader.ReadLineAsync();
        await pair.Reader.ReadLineAsync();
        await Assert.ThrowsAsync<InvalidOperationException>(() => connection.CallAsync<string>("snapshot"));
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => first);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => second);
    }

    [Fact(Timeout = 10000)]
    public async Task FailingEventSubscriberCannotBreakPendingReplies()
    {
        await using var pair = await PipePair.CreateAsync();
        await using var connection = new JsonLineConnection(pair.Client, pair.Client, TimeSpan.FromSeconds(2));
        connection.Notice += _ => throw new InvalidOperationException("UI callback failed");
        connection.Start();
        var pending = connection.CallAsync<string>("snapshot");
        using var request = JsonDocument.Parse((await pair.Reader.ReadLineAsync())!);
        await pair.Writer.WriteLineAsync("{\"event\":\"notice\",\"data\":\"ready\"}");
        await pair.Writer.WriteLineAsync($"{{\"id\":\"{request.RootElement.GetProperty("id").GetString()}\",\"ok\":true,\"data\":\"alive\"}}");
        Assert.Equal("alive", await pending);
    }

    private sealed class PipePair : IAsyncDisposable
    {
        public NamedPipeServerStream Server { get; }
        public NamedPipeClientStream Client { get; }
        public StreamReader Reader { get; }
        public StreamWriter Writer { get; }

        private PipePair(NamedPipeServerStream server, NamedPipeClientStream client)
        {
            Server = server;
            Client = client;
            Reader = new StreamReader(server, new UTF8Encoding(false, true), false, 1024, leaveOpen: true);
            Writer = new StreamWriter(server, new UTF8Encoding(false, true), 1024, leaveOpen: true) { AutoFlush = true };
        }

        public static async Task<PipePair> CreateAsync()
        {
            var name = $"wozai-next-tests-{Guid.NewGuid():N}";
            var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
            var client = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
            var connected = server.WaitForConnectionAsync();
            await client.ConnectAsync(2000);
            await connected;
            return new PipePair(server, client);
        }

        public async ValueTask DisposeAsync()
        {
            Reader.Dispose();
            // The server may already have been closed to simulate child EOF.
            try { await Writer.DisposeAsync(); } catch (IOException) { } catch (ObjectDisposedException) { }
            await Client.DisposeAsync();
            await Server.DisposeAsync();
        }
    }
}
