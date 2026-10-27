# Tablet panes and measured file routing / 平板双栏与实测选路

Development changes; not included in the published 0.3.2 release.
开发中变更，尚未包含在已发布的 0.3.2 中。

## Tablet / 平板

- The rail's 56dp new-chat button centers its 24dp glyph with symmetric 16dp padding.
- Drag the conversation divider to resize the existing list and chat views. Its 48dp-wide touch region has a visible handle. Tap the divider to center it; keyboard Left/Right adjusts it, Home centers it. Accessibility exposes a slider and a center action.
- The center is the midpoint of the usable application window, including the navigation rail. Snap engages within 16dp and releases beyond 28dp. Haptic feedback honors the system setting. Each pane retains its font-scale-aware minimum; if the center cannot satisfy those minimums, it is not a snap point.
- Completed adjustments are stored locally as a window-relative position. Cancelled drags restore the previous position. Small windows retain single-pane behavior; a separating/occluding vertical hinge fixes the split and disables dragging. No connection/controller is recreated to resize panes.

导航栏新聊天图标居中。拖动分隔线可调整两栏，点击可居中；靠近可用窗口中线时吸附，离开较大范围后释放，避免抖动。位置保存在本机，取消拖动恢复原位置。键盘及无障碍均可调整。两栏最小宽度、大字体、窄窗口和铰链避让优先于个人位置设置；调整时保留会话、输入与阅读位置。

## Routing / 选路

The Android and Windows/Linux Java clients share this policy. This change does not add native P2P support to Apple or Terminal clients.

1. Before pausing a healthy Direct transfer, reject an assessment if even zero-time LAN payload delivery could not repay the estimated switch/prefix-validation cost, a two-second probe allowance and the required two-second net benefit. Existing two-second warmup, eight-second remaining-work admission and one-minute comparison cache remain.
2. Compare the fastest credible Direct sample with the slowest credible LAN sample across both alternating rounds. Apply the observed local/remote I/O capacity ceiling to both. LAN must still improve by at least 25% and save at least two seconds after the actual comparison time and estimated migration cost. Incomplete or too-short Direct measurements cannot justify a switch.
3. Probe errors retain the healthy Direct lane and are recorded as inconclusive. Actual Direct interruption still uses the existing authenticated LAN recovery/checkpoint path. Manual pause/cancel, original content and saved-receipt semantics remain unchanged. No same-batch LAN-to-P2P re-upgrade is introduced (#36).

先判断比较是否可能划算，再暂停文件。比较使用两轮保守速率边界并考虑本地 I/O 上限，避免把射频波动当成稳定收益。保留 25% 速度优势、至少净省两秒、有限探测预算与缓存；探测失败不替换健康直连，真实断路继续走既有 LAN 续传。

### Diagnostics / 诊断

`file_quality_scope=last_assessment`; these are per-routing-session assessment observations, not whole-file throughput statistics (#32). The coordinator computes the decision; `file_quality_decision_available` explicitly distinguishes missing local decision evidence from measured zero throughput.

- `file_quality_samples_bytes_ns_complete`: alternating `direct1;lan1;direct2;lan2`, each with acknowledged payload bytes, round-trip wall-clock nanoseconds and validity. Maximum 4 MiB per round / 16 MiB per assessment; probe window and protocol are unchanged.
- `file_quality_{direct,lan}_bytes_per_second`: conservative, I/O-capped rates, in bytes/s (divide by 1,048,576 for MiB/s).
- `file_quality_remaining_bytes`, `file_quality_io_capacity_bytes_per_second`, `file_quality_decision_cost_ms`, `file_quality_net_savings_ms`, threshold fields and decision reason expose the calculation. Decision cost includes comparison time through the decision plus estimated migration cost; it is not a measured disk-rescan duration.
- `file_quality_setup_ms`, `file_quality_probe_ms`, `file_quality_assessment_ms`, `file_quality_quiesce_to_resume_ms`: measured local wall-clock intervals. Quiesce-to-resume starts when local quiescence/freeze is requested and ends when resume preparation completes, including drain/coordination/checkpoint work. It is **not** the precise interval between the last old-route DATA byte and first resumed DATA byte. Live/incomplete intervals may remain zero; no complete timing is claimed for failed restoration.

诊断明确统计范围、字节/秒和毫秒单位、样本有效性、决策依据与协调耗时。它不替代单文件端到端统计，也不把协调暂停区间声称为精确的 DATA 进度停顿。

## Device checks / 真机验证

Use the same candidate on both devices. Check button alignment, divider drag/snap/restart, portrait/landscape/split-screen, large text and keyboard; ensure drafts, reading position and ongoing transfers survive resizing. Compare the same large file under Direct and LAN conditions, copy both diagnostics after completion, and check content/save receipts. Exercise Direct interruption with a still-reachable LAN. Record device/OS/build, network and VPN conditions. Emulator and loopback evidence does not establish radio throughput gains or folding-device acceptance.

两端安装同一候选，验证按钮、拖动/吸附/重启记忆、旋转/分屏/大字体/键盘和正在传输时调整。使用同一大文件验证选路及断路恢复，完成后复制双端诊断，记录版本、网络、VPN 与实际体验。没有真机结果不宣称提速或完成射频验收。
