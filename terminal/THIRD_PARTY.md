# Third-party software

NearbyIM Terminal is part of GHOST-AKU/wozai and is distributed under AGPL-3.0-only; see the repository LICENSE. Source for this version is available from its corresponding repository commit and Cargo.lock.

The executable uses maintained upstream crates, including Snow (Noise XX), RustCrypto (P-256, AES-GCM, SHA-256), Ratatui, Crossterm, Clap, mdns-sd and rusqlite with bundled SQLite. Cryptographic primitives are not implemented by this client. Snow's raw split is used after the completed handshake solely to provide NIM4's required outer-header AAD; see src/crypto.rs and protocol tests.

`tools/licenses.py` obtains exact package names, versions, declared SPDX expressions and repositories from the locked Cargo dependency graph and copies upstream license/notice files into the distribution's `licenses/` directory. `licenses/inventory.json` lists the retained files. The inventory follows the current host’s normal/build dependency closure, excludes test-only and other-platform dependencies, and requires retained license text for every selected crate. Cargo.lock records registry checksums. Review both the inventory and retained notices before publishing a release.

Image selection additionally uses the `image` crate's selected PNG/JPEG/WebP decoders, `flate2` and `crc32fast`; no external image viewer is automatically launched. The zune-core 0.4.12 and zune-jpeg 0.4.21 registry archives omit upstream license files. `vendor-licenses/sources.json` pins their published source revisions, original notice/Zlib-license URLs and SHA-256 hashes. The collector verifies the registry's VCS revision and retained hashes before copying these texts, with provenance, into the package; these crates are used under their declared Zlib option.

The production Java interoperability fixture is a development/test dependency only. It compiles the repository's existing vendored noise-java implementation and Java core; these are not bundled into the terminal executable. Existing notices and license files in the repository apply to those test sources.
