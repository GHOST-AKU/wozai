namespace WoZai.Next.Services;

/// <summary>Bounded ordered carrier. ATT success is not a NIM4 message-delivery receipt.</summary>
internal sealed class BleValueStream : Stream
{
    internal const int BufferLimit = 65536;
    internal const int MaximumWrite = 48 * 1024 + 26;
    internal static readonly Guid Service = new("b326d8e0-8a5c-4cc2-a35f-4b1146fe1001");
    internal static readonly Guid Receive = new("b326d8e0-8a5c-4cc2-a35f-4b1146fe1002");
    internal static readonly Guid Transmit = new("b326d8e0-8a5c-4cc2-a35f-4b1146fe1003");
    private readonly object _gate = new();
    private readonly byte[] _buffer = new byte[BufferLimit];
    private readonly Func<byte[], CancellationToken, Task> _send;
    private readonly Action _closed;
    private readonly int _valueSize;
    private readonly CancellationTokenSource _stop = new();
    private TaskCompletionSource<bool>? _reader;
    private int _offset, _length, _reading, _writing;
    private bool _ended;

    internal BleValueStream(int valueSize, Func<byte[], CancellationToken, Task> send, Action closed)
    { _valueSize = Math.Clamp(valueSize, 20, 512); _send = send; _closed = closed; }

    internal bool ReceiveValue(ReadOnlySpan<byte> value)
    {
        if (value.Length == 1 && value[0] == 1) { End(preserveInput: true); return true; }
        if (value.Length is < 2 or > 512 || value[0] != 0) { Dispose(); return false; }
        TaskCompletionSource<bool>? reader = null;
        bool accepted;
        lock (_gate)
        {
            accepted = !_ended && _length + value.Length - 1 <= BufferLimit;
            if (accepted)
            {
                var bytes = value[1..]; int end = (_offset + _length) % BufferLimit;
                int first = Math.Min(bytes.Length, BufferLimit - end);
                bytes[..first].CopyTo(_buffer.AsSpan(end)); bytes[first..].CopyTo(_buffer);
                _length += bytes.Length; reader = _reader; _reader = null;
            }
        }
        reader?.TrySetResult(true);
        if (!accepted) Dispose();
        return accepted;
    }
    public override async ValueTask<int> ReadAsync(Memory<byte> destination, CancellationToken token = default)
    {
        if (Interlocked.Exchange(ref _reading, 1) != 0) throw new IOException("Concurrent BLE readers");
        using var cancelled = token.Register(Dispose);
        try
        {
            while (true)
            {
                Task wait;
                lock (_gate)
                {
                    token.ThrowIfCancellationRequested();
                    if (destination.Length == 0) return 0;
                    if (_length > 0)
                    {
                        int count = Math.Min(destination.Length, _length), first = Math.Min(count, BufferLimit - _offset);
                        _buffer.AsMemory(_offset, first).CopyTo(destination);
                        _buffer.AsMemory(0, count - first).CopyTo(destination[first..]);
                        _offset = (_offset + count) % BufferLimit; _length -= count; return count;
                    }
                    if (_ended) throw new IOException("BLE stream closed");
                    _reader = new(TaskCreationOptions.RunContinuationsAsynchronously); wait = _reader.Task;
                }
                await wait.ConfigureAwait(false);
            }
        }
        finally { Volatile.Write(ref _reading, 0); }
    }
    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> bytes, CancellationToken token = default)
    {
        if (bytes.Length > MaximumWrite) { Dispose(); throw new IOException("BLE write bound"); }
        if (Interlocked.Exchange(ref _writing, 1) != 0) throw new IOException("Concurrent BLE writers");
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(token, _stop.Token);
        try
        {
            cancellation.Token.ThrowIfCancellationRequested();
            for (int offset = 0; offset < bytes.Length; offset += _valueSize - 1)
            {
                int count = Math.Min(_valueSize - 1, bytes.Length - offset);
                var value = new byte[count + 1]; bytes.Slice(offset, count).CopyTo(value.AsMemory(1));
                await _send(value, cancellation.Token).ConfigureAwait(false);
            }
            cancellation.Token.ThrowIfCancellationRequested();
        }
        catch { Dispose(); throw; }
        finally { Volatile.Write(ref _writing, 0); }
    }
    protected override void Dispose(bool disposing)
    { End(preserveInput: false); base.Dispose(disposing); }
    private void End(bool preserveInput)
    {
        TaskCompletionSource<bool>? reader;
        lock (_gate)
        {
            if (_ended) return; _ended = true;
            if (!preserveInput) { _length = 0; Array.Clear(_buffer); }
            reader = _reader; _reader = null;
        }
        _stop.Cancel(); reader?.TrySetResult(true); _closed();
    }
    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override int Read(byte[] buffer, int offset, int count) => ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
    public override void Write(byte[] buffer, int offset, int count) => WriteAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken token) => ReadAsync(buffer.AsMemory(offset, count), token).AsTask();
    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken token) => WriteAsync(buffer.AsMemory(offset, count), token).AsTask();
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}
