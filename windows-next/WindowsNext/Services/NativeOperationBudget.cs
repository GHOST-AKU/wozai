namespace WoZai.Next.Services;

/// <summary>Bound managed waits even when a driver ignores cancellation. Late work retains its slot.</summary>
internal sealed class NativeOperationBudget
{
    private readonly SemaphoreSlim _slots = new(2, 2);
    internal async Task<T> Run<T>(Func<Task<T>> start, CancellationToken token, Action<T>? discard = null)
    {
        token.ThrowIfCancellationRequested();
        if (!_slots.Wait(0)) throw new IOException("bleBusy");
        Task<T> operation;
        try { operation = start(); }
        catch { _slots.Release(); throw; }
        _ = operation.ContinueWith(completed => {
            _ = completed.Exception; _slots.Release();
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
        try { return await operation.WaitAsync(token).ConfigureAwait(false); }
        catch
        {
            // A successful native result arriving after its managed owner was cancelled must be released.
            _ = operation.ContinueWith(completed => {
                if (completed.Status == TaskStatus.RanToCompletion) {
                    try { discard?.Invoke(completed.Result); } catch (Exception) { }
                }
            }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            throw;
        }
    }
}
