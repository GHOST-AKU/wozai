# 有线与 Wi-Fi 局域网互通 / Ethernet and Wi-Fi LAN discovery

电脑接路由器网线、手机接同一路由器的 Wi-Fi 可以使用同一个 LAN 模式。两端都需要运行“我在”并开启局域网接收；普通电脑、路由器不会因为有 IP 就出现在附近列表中。无需开启热点或 Wi-Fi Direct。

A computer on Ethernet and a phone on the router's Wi-Fi use the same LAN mode. Both must run NearbyIM and enable LAN reception. Nearby is a list of advertising NearbyIM clients, not every IP device. No hotspot or Wi-Fi Direct is required.

## 实现与范围 / Implementation and scope

- 发现使用 `_nearbyim._tcp.local.` DNS-SD/mDNS，UDP 5353；聊天连接对端广告中的实际 TCP 端口。发现信息只提供路由线索，身份仍由 NIM4 加密握手和用户认可确认。
- 本分支的 Windows/Linux GUI 会在开启接收期间定期检查普通 LAN IPv4 地址，给新增地址建立发现，移除已消失接口上的设备，并重试注册失败的接口。多地址 DNS 答复优先使用接收接口所在网段的地址；没有同网段地址时仍允许其他私有局域网地址。
- 手动 IP 连接保留。自动发现当前使用 IPv4；IPv6 手动连接仍受原有本地地址和作用域限制。Terminal 使用 `mdns-sd`，Apple 使用系统 Bonjour；本次没有修改这两个客户端的发现实现。
- 路由器必须允许客户端互访，并转发相应链路上的 mDNS。访客网络、无线隔离、VLAN、阻断组播的交换机及系统防火墙都可能影响结果。没有自动修改防火墙或扫描整个网段。

Discovery uses DNS-SD/mDNS (`_nearbyim._tcp.local.`, UDP 5353). Chat uses the advertised TCP port. Advertisements remain untrusted routing hints; NIM4 authenticates identity and applies consent.

On this branch, Windows/Linux GUI clients periodically reconcile ordinary LAN IPv4 interfaces while reception is enabled, add new interfaces, withdraw peers from removed interfaces and retry failed registrations. Multi-address answers prefer the receiving interface's subnet, retaining private routed addresses as a fallback. Manual IP connection remains available, including existing local IPv6 support and scope restrictions. Terminal's `mdns-sd` and Apple's Bonjour implementations are unchanged.

The router must allow device-to-device traffic and mDNS on the relevant links. Guest isolation, VLANs, multicast filtering and host firewalls can interfere. NearbyIM does not automatically alter firewall rules or sweep subnets.

## 搜不到时 / When neither device appears

1. 双端开启「附近 → 局域网」接收，等待发现完成，分别点击「复制局域网诊断」。Windows/Linux 的此按钮和 Android 的此按钮均不需要现有聊天。记录实际软件版本、操作系统及有线/Wi-Fi 连接方式。
2. 从电脑“我的连接地址”选取真实有线网卡的完整 `IP:端口`，在手机上手动连接；也反向试一次。端口每次重新开启接收都可能改变。连接成功后，正常完成身份认可并互发文字，检查保存回执。
3. 若手动连接成功、自动发现失败，重点检查 UDP 5353、mDNS 和网卡参与情况。若手动连接也失败，再检查地址/端口、监听状态、路由可达性与 TCP 防火墙规则，不能仅凭这个结果认定是组播问题。
4. Windows 只对可信家庭/办公 LAN 使用「专用网络」，允许实际应用进程的局域网接收；Linux 检查所用防火墙的对应规则。不需要关闭整个防火墙。VPN 或虚拟网卡场景请保留诊断中的网卡与错误信息。

Enable LAN reception on both devices, then use **Copy LAN diagnostics** in Nearby on each side; an existing chat is not required. Record the exact build, operating systems and connection types. Try the computer's actual Ethernet `IP:port` from the phone and the reverse direction. Reopening reception may change the port. Complete consent and exchange text with saved receipts.

Successful manual connections with failed discovery point toward mDNS/interface filtering. Failed manual connections also require checking the address, port, listener, routes and TCP firewall policy. On Windows, use a Private profile only for a trusted LAN and permit the actual application process; check the corresponding firewall rules on Linux. Do not disable the entire firewall. Retain interface/error details when testing with VPNs or virtual adapters.

## 诊断字段 / Diagnostic fields

| 字段 / Field | 含义 / Meaning |
| --- | --- |
| `lan_listen_port` | 当前 TCP 接收端口；0 表示未监听。Current TCP reception port; 0 means off. |
| `lan_mdns_eligible_interfaces`, `lan_mdns_interfaces` | 桌面参与候选的 IPv4 地址、网卡和组播能力。Eligible desktop IPv4 addresses, interfaces and multicast capability. |
| `lan_mdns_registered_interfaces` | 桌面成功注册服务的接口数，不是发现或连通成功的证明。Desktop registered interface count, not evidence of peer discovery or connectivity. |
| `lan_registration_state` | Android NSD 注册状态。Android NSD advertisement state. |
| `lan_discovery_state` | 发现任务的本地状态；`running` 不代表已找到对端。Local discovery state; `running` does not mean a peer was found. |
| `lan_mdns_service_responses` | 本次开启接收以来的服务事件计数。桌面可能包含自身广告；Android 过滤已知自身服务名。Service events since reception started; desktop can include its own advertisement, Android excludes its known service name. |
| `lan_mdns_resolved_responses` | 解析并通过本地筛选的响应数，可能包含重复更新，不是唯一设备数。Resolved responses passing local filtering, including possible repeated updates; not a unique peer count. |
| `lan_mdns_resolve_failures` | Android 本次接收期间的解析失败数。Android resolve failures during this reception session. |
| `lan_mdns_last_error` | 最近一次本地注册/发现/解析错误，可能保留已恢复的错误。Last local advertisement/discovery/resolve error; may retain a recovered failure. |

诊断包含局域网 IP、端口和网卡名；由用户主动复制，不会自动上传。请连同双向手动连接结果更新 [Issue #5](https://github.com/GHOST-AKU/wozai/issues/5)。单机测试或 CI 成功不能代替 Windows/Linux 有线电脑与 Android Wi-Fi 在真实路由器上的验收。

Diagnostics include local IPs, ports and interface names. They are copied on request and are not uploaded automatically. Record both reports and manual-connection results in [Issue #5](https://github.com/GHOST-AKU/wozai/issues/5). Same-host tests and CI do not replace real Ethernet-to-Wi-Fi acceptance on Windows/Linux and Android.
