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
