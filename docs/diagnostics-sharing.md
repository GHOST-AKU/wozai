# Sharing diagnostics safely

Diagnostic exports help reproduce device discovery, connection, and transfer failures. Before attaching an export to a public issue, review it for sensitive information.

## Checklist

1. **Reproduce once and note the steps.** Record the app version, affected platforms, expected behavior, actual behavior, and whether the issue is intermittent.
2. **Preserve useful technical context.** Include the transport attempted (such as LAN, Bluetooth, or Wi-Fi Direct), the fallback outcome, relevant permission states, and error names when available.
3. **Redact personal and network identifiers.** Remove device names containing personal information, IP and MAC addresses, SSIDs, peer identifiers, local file paths, account details, message contents, tokens, and keys. Check free-text error fields too.
4. **Keep timestamps consistent.** State the timezone or UTC offset for human-readable times; do not silently shift timestamps when comparing logs from different devices.
5. **Share the smallest relevant excerpt.** Avoid posting full chat histories, transferred files, or unrelated logs. If a complete trace is necessary, use an appropriate private reporting channel rather than a public issue.
6. **Describe the test environment.** Note whether both devices were on the same LAN, whether a router or hotspot was involved, and whether the devices changed networks during the test.

## Suggested issue summary

```text
App version:
Device / OS (non-identifying):
Connection setup:
Steps to reproduce:
Expected result:
Actual result:
Transport and fallback outcome:
Relevant redacted diagnostics:
```

This is a reporting checklist, not a guarantee that an exported diagnostic file is free of personal data. Review every attachment before publishing.
