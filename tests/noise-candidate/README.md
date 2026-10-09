# Noise library acceptance fixture

This directory contains public vectors, source pins and acceptance tests. The
selected implementation is in `third_party/noise-java/src`; the system AEAD
adapter and root-bound record channel are in the application's `noise` package.
`sh tools/test-noise-candidate.sh` compiles these against Java 8 APIs and runs
with a 32 MiB heap. Client factory wiring is a separate implementation step.

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

Channel checks additionally cover root-proof forgery, static-key/claim binding,
pin changes, limits, bad tags, replay, old-session records and concurrent close.
API26/34 native tests and Linux ARM CPU measurements have CI evidence in issue
#15. These checks do not prove client consent wiring, Android ARM performance,
radio interoperability or production security audit; those requirements remain
tracked in that issue.
