using WoZai.Next.Services;
using Xunit;
namespace WoZai.Next.Tests;
public sealed class TextInputTests
{
    [Fact] public void PendingImeSurrogateCannotBecomeReplacementTextOnTheWire()
    { Assert.False(TextInput.CanSend("unfinished\uD83D",8192)); Assert.False(TextInput.CanSend("\uDC00",8192)); }
    [Fact] public void NegotiatedLimitsCountUtf8BytesAndPreserveWhitespace()
    { Assert.True(TextInput.CanSend(string.Concat(Enumerable.Repeat("你",2730)),8192)); Assert.False(TextInput.CanSend(string.Concat(Enumerable.Repeat("你",2731)),8192)); Assert.True(TextInput.CanSend(" hello\n",8192)); Assert.False(TextInput.CanSend(" \n ",8192)); }
}
