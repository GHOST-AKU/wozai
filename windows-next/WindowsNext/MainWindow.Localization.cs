using System.Reflection;
using System.Text.Json;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private static readonly Dictionary<string, Dictionary<string,string>> Catalog = LoadCatalog();
    private static Dictionary<string,Dictionary<string,string>> LoadCatalog()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("WoZai.Next.UiCatalog.json")
            ?? throw new InvalidOperationException("Missing native UI language catalog");
        return JsonSerializer.Deserialize<Dictionary<string,Dictionary<string,string>>>(stream)!;
    }
    private string T(string key)
    {
        var language = _state.EffectiveLanguage;
        if (Catalog.TryGetValue(language, out var localized) && localized.TryGetValue(key,out var text) && !text.Contains('{')) return text;
        if (Catalog["en"].TryGetValue(key,out var fallback) && !fallback.Contains('{')) return fallback;
        return key;
    }
    private string StatusName(string state) => T(state switch {
        "idle" => "statusIdle", "ready" => "connected", "handshake" => "completingConnection", "consent" => "requestTitle", "connecting" => "connecting",
        "pending" => "messageSending", "sent" => "sent", "delivered" => "delivered", "read" => "read", "unknown" => "unknown",
        "queued" => "attachmentStateQueued", "preparing" => "attachmentStatePreparing", "offered" => "attachmentStateOffered",
        "transferring" => "attachmentStateTransferring", "verifying" => "attachmentStateVerifying", "checking" => "attachmentStateChecking",
        "awaitingReceipt" => "attachmentStateAwaitingReceipt", "received" => "attachmentStateReceived", "rejected" => "attachmentStateRejected",
        "canceled" => "attachmentStateCanceled", "failed" => "attachmentStateFailed", "interrupted" => "attachmentStateInterrupted",
        "paused" => "attachmentStatePaused", "sourceUnavailable" => "attachmentStateSourceUnavailable", _ => state
    });
}
