namespace WoZai.Next;

public sealed class ClientState
{
    public string DataPath { get; init; } = "";
    public string LocalId { get; init; } = "";
    public string Nickname { get; init; } = "";
    public string Version { get; init; } = "";
    public string Phase { get; init; } = "idle";
    public string? ConnectedPeerId { get; init; }
    public string Transport { get; init; } = "";
    public int ListenPort { get; init; }
    public bool LanListening { get; init; }
    public bool BluetoothListening { get; init; }
    public bool BluetoothSupported { get; init; }
    public bool BluetoothAvailable { get; init; }
    public string BluetoothDetail { get; init; } = "";
    public string[] Endpoints { get; init; } = [];
    public string? SelectedPeerId { get; init; }
    public Peer[] History { get; init; } = [];
    public Peer[] Nearby { get; init; } = [];
    public Message[] Messages { get; init; } = [];
    public bool MessagesHasMore { get; init; }
    public string? MessagesBefore { get; init; }
    public string Draft { get; init; } = "";
    public bool CanSend { get; init; }
    public bool CanAttach { get; init; }
    public int TextLimit { get; init; }
    public bool ReadReceipts { get; init; }
    public bool CanObserve { get; init; }
    public string Theme { get; init; } = "light";
    public string Language { get; init; } = "system";
    public int FontSize { get; init; }
    public long Quota { get; init; }
    public string? Generation { get; init; }
    public Verification? Verification { get; init; }
    public Approval? Approval { get; init; }
    public Dictionary<string, string> Strings { get; init; } = new();
    public LanguageOption[] Languages { get; init; } = [];
    public string EffectiveLanguage { get; init; } = "en";
    public bool Rtl { get; init; }
    public BatchItem[] BatchItems { get; init; } = [];
    public string Diagnostics { get; init; } = "";
    public bool SearchingLan { get; init; }
    public bool SearchingBluetooth { get; init; }
}

public sealed class Peer
{
    public string? Id { get; init; }
    public string Name { get; init; } = "";
    public string Endpoint { get; init; } = "";
    public string PublicKey { get; init; } = "";
    public string Preview { get; init; } = "";
    public long Time { get; init; }
    public bool Trusted { get; init; }
    public string Transport { get; init; } = "";
}

public sealed class Message
{
    public string Id { get; init; } = "";
    public string Body { get; init; } = "";
    public long Time { get; init; }
    public bool Outgoing { get; init; }
    public string Status { get; init; } = "";
    public Attachment? Attachment { get; init; }
}

public sealed class Attachment
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Mime { get; init; } = "";
    public long Size { get; init; }
    public long Transferred { get; init; }
    public string State { get; init; } = "";
    public long Version { get; init; }
    public string Route { get; init; } = "";
    public string PathKey { get; init; } = "";
    public string? Path { get; init; }
    public bool Outgoing { get; init; }
    public string RetryState { get; init; } = "";
    public bool HasRetryableBatch { get; init; }
    public bool SafeToOpen { get; init; }
}

public sealed class Verification
{
    public string PeerId { get; init; } = "";
    public string Display { get; init; } = "";
    public string QrPayload { get; init; } = "";
    public bool Verified { get; init; }
    public string Generation { get; init; } = "";
}

public sealed class Approval
{
    public string Token { get; init; } = "";
    public string Name { get; init; } = "";
    public string Id { get; init; } = "";
    public string Fingerprint { get; init; } = "";
}

public sealed class Avatar
{
    public string Png { get; init; } = "";
    public long Revision { get; init; }
}

public sealed class LanguageOption
{
    public string Tag { get; init; } = "";
    public string NativeName { get; init; } = "";
    public bool Rtl { get; init; }
}

public sealed class BatchItem
{
    public string BatchId { get; init; } = "";
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public string Mime { get; init; } = "";
    public long Size { get; init; }
    public string State { get; init; } = "";
    public long Transferred { get; init; }
    public string RetryOf { get; init; } = "";
    public string RetryState { get; init; } = "";
}

public sealed class MessagesPage
{
    public Message[] Messages { get; init; } = [];
    public bool HasMore { get; init; }
    public string? NextBefore { get; init; }
}
