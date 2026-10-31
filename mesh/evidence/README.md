# Evidence provenance

- `source-manifest.json`: implementation commit, actual release binary hash/size, source SHA-256, machine and cgroup metadata.
- `verification.txt`: successful local checks for that implementation; no GitHub Actions success claimed.
- `isolated-release.json`: final isolated-process TCP topology, configured candidates.
- `isolated-discovery-release.json`: same binary, opt-in DNS-SD candidates, separate sequential run.
- `isolated-debug.json` and `isolated-discovery-debug.json`: exploratory runs before final corrections; source manifest was not captured then. Preserved as historical evidence, not final acceptance.

No wireless hardware was attached. All records explicitly say **hardware validation pending**. Read [method and limitations](../../docs/mesh/validation.md) before interpreting resource numbers. Parent RSS does not include discovery workers; no battery or wireless throughput estimate is reported. Evidence includes synthetic topology addresses and process IDs, with no message bodies, private keys or stable public identities.
