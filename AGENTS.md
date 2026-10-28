# Repository working rules

## Build and verification resources

- Avoid repeated or unnecessary tests. Prioritize resources for producing usable builds and completing the requested implementation.
- Select verification according to the files and behavior changed. Run the required checks once; broaden or repeat them only when new changes, failures, or unresolved concerns justify it.
- Reuse successful verification when the relevant source files and build configuration are identical. Record the verified commit, workflow link, and source comparison; do not describe reused results as a new run.
- Documentation-only changes do not require application builds or full regression suites. Check the document and diff instead, and avoid triggering unrelated CI.
- Do not repeatedly rebuild the same APK or other artifact without a concrete change or diagnostic purpose. Avoid duplicate local and CI runs that establish the same result.
- Keep meaningful regression tests and required release checks. Resource conservation must not hide failures, weaken acceptance criteria, or substitute automated results for real-device evidence.

## Temporary resources and evidence

- The repository owner authorizes deletion of resources created solely for temporary testing, including temporary verification branches, disposable fixtures, and local scratch artifacts, once they are no longer needed.
- Confirm that a resource is temporary before deletion. Preserve implementation branches, reusable build dependencies and caches, committed regression tests, release artifacts, user data, and acceptance evidence.
- Record progress and verification evidence in the corresponding GitHub Issues and PRs. Do not create TODO files.
- Write code comments in English and PR descriptions in both Chinese and English.

## Development versions

- Every Android APK offered to the owner for installation must pass `tools/verify-android-signature.py` against the persistent certificate in `tools/android-signing.json`. A temporary debug certificate is not an acceptable delivery substitute. Keep debug/UI fixtures clearly labeled internal; never ask the owner to uninstall or clear data to bypass a certificate mismatch. Keep private signing material inside the existing signing step.

- New development builds use the 0.3.3 preview series, starting at `0.3.3-preview.2`; do not relabel or replace published v0.3.2 or v0.3.3-preview.1 assets.
- `i18n/config.json` is the release-version and build-number authority for all clients. After changing it, run `python3 tools/version.py --write` and `python3 tools/generate-i18n.py`; verify with `python3 tools/version.py --check`.
- Synchronize this version change into an older implementation branch before building it. Preserve its actual source revision and feature/acceptance boundaries; sharing a version does not imply sharing every platform feature.
- Keep Apple/Windows numeric package versions separate from the full preview identifier, and keep Debian previews ordered below the eventual stable release. Publishing and merging remain separate actions.
