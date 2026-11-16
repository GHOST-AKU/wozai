using WoZai.Next.Services;
using Xunit;

public sealed class NativeOperationBudgetTests
{
    [Fact]
    public async Task IgnoredCancellationCannotStrandCallerOrAccumulateNativeOperations()
    {
        var budget = new NativeOperationBudget();
        var first = new TaskCompletionSource<int>();
        var second = new TaskCompletionSource<int>();
        using var cancel = new CancellationTokenSource();
        int discarded = 0;
        var one = budget.Run(() => first.Task, cancel.Token, value => Interlocked.Add(ref discarded, value));
        var two = budget.Run(() => second.Task, cancel.Token, value => Interlocked.Add(ref discarded, value));
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => one.WaitAsync(TimeSpan.FromSeconds(1)));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => two.WaitAsync(TimeSpan.FromSeconds(1)));
        bool invoked = false;
        await Assert.ThrowsAsync<IOException>(() => budget.Run(() => { invoked = true; return Task.FromResult(0); }, CancellationToken.None));
        Assert.False(invoked);
        first.SetResult(1); second.SetResult(2); Assert.Equal(3, discarded);
        Assert.Equal(42, await budget.Run(() => Task.FromResult(42), CancellationToken.None));
    }
    [Fact]
    public async Task ImmediateFailureReleasesCapacityAndSuccessKeepsOwnership()
    {
        var budget = new NativeOperationBudget();
        for (int i = 0; i < 4; i++)
            await Assert.ThrowsAsync<IOException>(() => budget.Run<int>(() => throw new IOException(), CancellationToken.None));
        bool disposed = false;
        Assert.Equal(1, await budget.Run(() => Task.FromResult(1), CancellationToken.None, _ => disposed = true));
        Assert.False(disposed);
    }
}
