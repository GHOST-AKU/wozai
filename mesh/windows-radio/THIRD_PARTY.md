# Dependency and research notices

Adapter code is AGPL-3.0-only under the repository LICENSE. It uses the public API
of Bumble0.0.219 (Google LLC and contributors), Apache-2.0:
https://github.com/google/bumble/tree/v0.0.219
https://github.com/google/bumble/blob/v0.0.219/LICENSE

No upstream bridge or test implementation is copied. The released Bumble wheel
and its dependencies are installed separately; their original license notices
remain in the installed distributions. Python runtime/USB driver are not bundled.
Windows lab binaries use existing locked Rust dependencies; their notices are in
mesh/THIRD_PARTY.md and mesh/vendor-licenses. Official Microsoft documentation was
consulted for research; no Windows driver or closed-source code is copied.

Reviewed source locations:
- bumble/l2cap.py: LeCreditBasedChannelSpec, LeCreditBasedChannel.write/drain;
  pause_reading/resume_reading are no-ops in the selected release.
- bumble/device.py: Device.connect, Connection.create_l2cap_channel, advertisements.
- bumble/transport/usb.py and platforms/windows.md: dedicated USB/WinUSB path.
- tests/test_utils.py: asynchronous host/controller wiring for virtual HCI tests.
