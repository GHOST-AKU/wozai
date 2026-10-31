# Windows Next private process protocol v1

The WinUI application owns a Java child over redirected UTF-8 stdin/stdout. No HTTP/TCP control listener. Standard error is diagnostic-only. Lines are JSON, maximum 2 MiB; request IDs correlate replies; unsolicited events are allowed. EOF shuts down the child. Never log request bodies or identity secrets.

Request: `{"id":"1","op":"snapshot","args":{}}`. Reply: `{"id":"1","ok":true,"data":...}` or `{"id":"1","ok":false,"error":"localized human-readable error"}`. Event: `{"event":"state","data":SNAPSHOT}` or `{"event":"notice","data":"message"}`. All property names camelCase, nulls allowed. Serial processing of UI commands; the existing DesktopClient still owns its model executor.

Snapshot fields: `localId,nickname,version,phase,connectedPeerId,transport,lanListening,bluetoothListening,endpoints` (string[]), `selectedPeerId`, `history` (Peer[]), `nearby` (Peer[]), `messages` (Message[]), `draft,canSend,canAttach,textLimit,readReceipts,theme,language,fontSize,quota,verification` (Verification|null), `approval` (Approval|null).

Peer: `id,name,endpoint,publicKey,preview,time,trusted,transport`. Discovery IDs can be absent; discovery is not authentication.

Message: `id,body,time,outgoing,status,attachment` (Attachment|null).

Attachment: `id,name,mime,size,transferred,state,version,route,path` (path only for a complete, validated local file; otherwise null), `outgoing`.

Verification: `peerId,display,qrPayload,verified,generation` (opaque snapshot token). Approval: `token,name,id,fingerprint`.

Operations (args keys):
- `snapshot`, `refresh`, `quit`.
- `select` {peerId}; `draft` {peerId,text}; `send` {peerId,text} returns only after durable save.
- `listen` {mode:"lan"|"bluetooth",enabled:bool}; `search` {mode}; `stopSearch` {mode}; `connect` {endpoint,expectedId?}; `disconnect`.
- `approve` {token,remember}; `reject` {token}; `revoke` {peerId}; `clear` {peerId}.
- `settings` {nickname?,theme?,language?,fontSize?,readReceipts?,quota?}.
- `files` {peerId,generation,paths:string[]}; `attachment` {peerId,id,outgoing,action:"accept"|"reject"|"pause"|"resume"|"cancel"|"retry"|"retryBatch",generation?}; `retryFile` {peerId,generation,id,path}; `batch` {peerId,action:"pause"|"resume"|"cancel"}.
- `export` {peerId,id,outgoing,destination}; `openPath` {peerId,id,outgoing} returns safe local path; frontend follows existing attachment type restrictions before external launch.
- `verification` {peerId} returns Verification; `verify` {peerId,generation,scanned?} checks current exact session. `qrImage` {peerId} returns base64 PNG of current code; `readQr` {path} returns QR payload, then UI explicitly calls verify after match.
- `avatar` {peerId?} returns {png:base64,revision:long}; `saveAvatar` {revision,png:base64}; `resetAvatar` {revision}.
- `observed` {peerId,ids:string[]} only from a focused, unlocked, foreground WinUI chat whose incoming text bodies have dwelled visibly; bridge rejects another peer.

No receive/discovery at startup; no automatic unknown-peer approval. Reconnect uses remembered identity, never guessed hostname identity. File selections and verification bind to current generation. UI mutations and filesystem failures must yield a reply, never hang. UI reads profiles only through the child; profile path provided on launch is independent from original desktop data.
