# Android Next Mesh Preview Implementation Plan

> For agentic workers: use subagent-driven-development with scoped implementation and review; do not spawn nested helpers.

Goal: signed0.4.0-preview.1/build16 installable app based on latest app-next.
Spec: android-next-preview-design.md. Constraints: no mixed transport, no NIM4 wire/trust changes, preserve next package/signature/data, no fabricated hardware result.

1. Native client task: implement MeshSnapshot/MeshClient/MeshService and public RadioConfig factory with scoped tests. Inputs exact interfaces in spec; stage source without commits so root controls final build revision. Prove bounds/UTF8/event transitions and cancellation/generation behavior; compile via root Gradle only once integrated.
2. UI task: new MeshScreen observesStateFlow, permission/visibility/relay/mode controls, timeline/draft, private-device entry. Tests start from missing screen/state expectedfailure, preserve original nearby device tests under explicit device subpage. Keep Activity/service lifecycle boundaries.
3. Packaging/version: add native sourceSet excludingRadioLab, package Androidarm64 core in extractednative directory with generated source/hash provenance+licenses, R8keep needed publicentrypoints. Update authoritativeversion andgeneratoroutputs. Extend existingnext CI to build nativecore beforetests/signedrelease and verifyAPK ELF/manifest/signature.
4. Verify/review: next test/lint + native tests, fresh wholebranch review, targeted fixes, one final signedCI artifact sourcebinding. Update DraftPR andEpic/Issue with limitations and installinstructions. No formal release/merge.

Review focus: staleprocess callbacks; backgroundstop/permission revocation; nativeexec packaging/R8; unsupportedradios notfallback; public≠E2EE; unchangedNIM4 paths.
