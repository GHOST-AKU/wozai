# Troubleshooting and honest status reporting

1. **Command missing:** `nearbyim` is not on this machine's PATH. Ask the user to install NearbyIM Terminal on the execution host or provide an environment with access. Do not conclude the user's other devices lack the app.
2. **Locality:** `nearbyim` run inside Codex Cloud, SSH, a VM or container executes there, not on the user's local Windows/Linux host. Discovery requires access to the proper LAN. Remote access needs separate user-approved connectivity.
3. **`status: not_running`:** Normal status for a Terminal client without a daemon or IPC. It does not inspect or establish a persistent session. Do not advertise another process as online from this output.
4. **`PROFILE_BUSY` / exit 4:** The same Terminal profile is locked by another TUI/CLI process. Do not kill it or delete lock files. Ask the user to end or coordinate the existing session.
5. **No peers:** Check explicit scan, local network, VPN/firewall/multicast isolation, recipient's explicit foreground LAN listener, and direct address when appropriate. Never turn on listeners or alter firewall rules without approval.
6. **Interaction required / exit 6:** Unknown peers and security approval cannot be silently accepted from JSON or piped input. Hand off to an interactive human session; retry only after authorization.
7. **Identity mismatch / exit 7:** Stop and have the user independently verify the peer. Do not forget the old pin to force success.
8. **Network error / exit 5; handshake / exit 8:** Report the specific error. Bluetooth RFCOMM/native Wi-Fi Direct and background IPC are not implemented by this Terminal client.
9. **Timeout / exit 10:** Delivery is not confirmed. **Do not automatically resend text** (could duplicate delivery). Let the user decide after checking relevant status/history.
10. **File transfer:** The recipient may require interactive acceptance. Source may change, prefix may fail revalidation, or the original peer identity may no longer match. Do not bypass checksum/identity protections or overwrite exported files.
11. **Unexpected output:** Run `nearbyim --help` and consult `terminal/README.md` for the installed version. Prefer structured output and the real exit code; don't invent unsupported commands or fabricate receipts.

Report a concise factual outcome: executable/profile host, requested operation, target ID if known, exit code/status, and confirmed vs unconfirmed delivery. Never paste sensitive message history or safety codes by default.
