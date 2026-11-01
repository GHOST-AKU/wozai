# Nearby Mesh Android capability probe

Independent, headless Android Instrumentation source. It observes actual native API callbacks and exports redacted JSONL. **Hardware validation pending**: compilation does not prove any wireless data path or Mesh forwarding.

Build classes and DEX (no installation artifact offered):

```sh
ANDROID_SDK_ROOT=/path/to/sdk JAVA_HOME=/path/to/jdk17 bash mesh/android-probe/build.sh
```

Requires platform 36 and build-tools 35.0.0. No existing NearbyIM module is modified. Do not substitute a temporary debug certificate for the repository's APK delivery rules. The optional unsigned internal packaging switch is for development infrastructure, not owner installation.

See [hardware protocol](../../docs/mesh/hardware-validation.md) for modes, permission handling, signing prerequisites and strict three-device acceptance. `inventory` does not initiate radio discovery; `wifi-discover`, `wifi-group`, `aware`, and `ble` require explicit selection. Requests and actual callbacks are recorded separately. Wi-Fi group cleanup checks the unique group name and GO role before removal. No Rust Core native data adapter or background service is claimed.
