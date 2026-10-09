# Noise library acceptance fixture

This directory is test code. It does not change the clients' session protocol or
add a production dependency. `sh tools/test-noise-candidate.sh` compiles against
Java 8 APIs and runs with a 32 MiB heap.

The 29 MIT-licensed upstream sources are pinned to rweather/noise-java commit
`49377b6dfc6a1e75740bce2318118291a57c0d6e`. `UPSTREAM_SHA256.json` records their
unmodified contents. `cipher-injection.patch` adds an explicit per-handshake
CipherState argument and rejects all-zero DH results (including nonzero
low-order public keys). A third file masks the X25519 input high bit as required
by RFC 7748, without changing transcript bytes; section 5.2 known-answer vectors
and high-bit variants cover this correction. `LOCAL_SHA256.json` checks the
patched tree. No curve algorithm is replaced or added.
The local adapter uses the system `AES/GCM/NoPadding` implementation. It contains
no AES/GHASH algorithm and provides no fallback if the system cipher is missing.

The public known-answer XX/25519/AESGCM/SHA256 vector comes from cacophony commit
`8ee9d41e34a1a596cfa3ab12aa4069ff87dc1247`. `SOURCE.json` pins the original vector
file SHA-256; `vectors-selected.json` and `VectorFixture.java` retain the selected
data. Cacophony is dedicated to the public domain under the included Unlicense.
The test compares all handshake bytes, the transcript hash and transport bytes,
and also crosses the JCA adapter with the unchanged upstream cipher in both
roles. Other checks cover bad tags, replay, nonce exhaustion and destruction.

These are library checks. Application identity binding, consent, record framing,
Android/ARM performance, radio interoperability and production security review
are separate acceptance requirements tracked in GitHub issue #15.
