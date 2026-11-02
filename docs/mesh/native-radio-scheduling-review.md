# BLE propagation scheduling review (2026-11-02)

The Android Next preview shares a single outstanding GATT server indication across
neighbors. Previously every acknowledgement restarted scanning the same map order.
A busy first neighbor could repeatedly win while another neighbor's queued operation
approached its five-second deadline. Increasing queue capacity would not fix this.

The scheduler now rotates after the last submitted address. With a stable set of
at most four eligible neighbors, a continuously waiting peer gets a turn within
four successful indication slots. Empty queues and disconnected peers are skipped.
Only one indication remains outstanding; its original callback owner is retained
across disconnects. Expiry cleanup can reenter scheduling, so the existing post-cleanup
in-flight check remains essential. This is fair per ATT fragment, not per message
or per byte: different negotiated MTUs can have different throughput.

## Reproduction and verification

`bash mesh/android-radio/test-ble-host.sh` runs the production adapter using narrow
Android doubles. The new regression puts A in flight, queues B and eight more A
operations. Before the fix the first two submissions are A,A; after the fix they
are A,B, followed by A after B's own acknowledgement. This is a deterministic
scheduling improvement, not a measured wireless latency or throughput percentage.
All five BLE scheduler cases pass, including expiry reentrancy, stale ACK ownership
and public candidate expiry. `bash mesh/android-radio/test.sh` also passes all seven
host programs. Independent Android API/DEX compilation belongs to the radio CI.

## Remaining bottlenecks and choices

- Connection recovery: BLE exhaustion now starts a 60–65 second cooldown (bounded
  per-pair jitter). Only a fresh advertisement after the deadline opens another
  four-attempt window. Disconnected candidates are retained through cooldown so
  the ordinary 30-second expiry cannot bypass the budget. No periodic retry task
  is created for exhausted peers. The host regression verifies silence, rediscovery
  before/at deadline, and expiry bypass prevention; previously it failed because
  exhaustion had no recovery deadline. Aware still has a finite failure budget
  requiring its own recovery/lifecycle analysis. This is not complete Sybil defense:
  rotating labels or restarting the app can reset per-peer state.
- BLE setup: missing MTU callbacks can consume the 15-second setup deadline. Starting
  discovery concurrently with an unresolved GATT operation is not a safe shortcut.
- Core authentication currently expires after three seconds. Low-MTU BLE can spend
  significant time fragmenting authenticated frames. Transport-aware bounded setup
  budgets require actual timing traces and explicit slow-peer admission limits.
- Core has a 5ms event-loop sleep (not the 10ms stated in earlier preview notes),
  three-second pings and ten-second checkpoints. No phone battery measurement exists.
  An event-driven core is a separate change requiring wakeup/reconnect regression
  tests; removing sleeps would increase CPU consumption.
- Aware already binds sockets to the selected Network; this review does not replace
  its lifecycle ownership with parallel unbounded workers.

The user's screenshot reports one BLE neighbor and verified one-hop incoming
messages. It is useful device feedback, not proof of isolated three-device routing,
round-trip delivery, or a latency baseline. Hardware validation remains pending.
This change does not alter the previously delivered preview.1 APK bytes; a future
installation artifact must have a new version code and pass persistent-signature CI.
