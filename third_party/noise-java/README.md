# Pinned Noise implementation

MIT-licensed rweather/noise-java at `49377b6dfc6a1e75740bce2318118291a57c0d6e`.
The exact 29 original source hashes and the patched source manifest are in
`tests/noise-candidate/{UPSTREAM_SHA256,LOCAL_SHA256}.json`. That directory also
contains the explicit three-file patch: system CipherState injection, all-zero
DH rejection, and RFC 7748 input high-bit masking. The curve algorithm is not
replaced. `tools/test-noise-candidate.sh` verifies exact patched bytes before
compiling and running independent vectors and channel failure checks.

The application selects XX / X25519 / system AES-GCM / SHA-256. Unused upstream
algorithms remain for source provenance and reference interoperability tests;
Android release R8 removes unreachable code. See issue #15 for compatibility,
measurement evidence, patch risk and outstanding physical-device acceptance.
