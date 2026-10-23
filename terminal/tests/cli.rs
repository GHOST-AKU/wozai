//! Public executable contracts, without a network adapter or terminal emulator.
use std::process::Command;
fn bin() -> Command {
    Command::new(env!("CARGO_BIN_EXE_nearbyim"))
}
#[test]
fn help_completions_and_non_tty_are_plain() {
    let out = bin().arg("--help").output().unwrap();
    assert!(out.status.success());
    assert!(String::from_utf8_lossy(&out.stdout).contains("NearbyIM Terminal"));
    assert!(!out.stdout.contains(&27));
    for shell in ["bash", "zsh", "powershell"] {
        let out = bin().args(["completions", shell]).output().unwrap();
        assert!(out.status.success());
        assert!(!out.stdout.is_empty());
    }
    let out = bin().arg("--json").output().unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(!out.stdout.contains(&27));
    let value: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(value["exit_code"], 2);
}
#[test]
fn independent_profile_json_permissions_and_corruption() {
    let d = tempfile::tempdir().unwrap();
    let path = d.path().join("terminal-profile");
    let out = bin()
        .args(["--profile", path.to_str().unwrap(), "--json", "status"])
        .output()
        .unwrap();
    assert!(out.status.success());
    let value: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(value["protocol"], "NIM4");
    assert_eq!(value["client"], "NearbyIM Terminal");
    assert!(value.get("private_key").is_none());
    let id = value["device_id"].clone();
    let out = bin()
        .args(["--profile", path.to_str().unwrap(), "--json", "doctor"])
        .output()
        .unwrap();
    let value: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(id, value["device_id"]);
    std::fs::write(path.join("identity.json"), b"broken").unwrap();
    let out = bin()
        .args(["--profile", path.to_str().unwrap(), "--json", "status"])
        .output()
        .unwrap();
    assert!(!out.status.success());
    assert_eq!(
        std::fs::read(path.join("identity.json")).unwrap(),
        b"broken"
    );
}

#[test]
fn invalid_input_and_config_match_manual_contract() {
    let out = bin()
        .args([
            "--json",
            "send",
            "--address",
            "127.0.0.1:1",
            "--text",
            &"中".repeat(2731),
        ])
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(2));
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["error"]["code"], "INVALID_INPUT");
    assert!(
        v["error"]["message"]
            .as_str()
            .unwrap()
            .contains("UTF-8 bytes")
    );
    let out = bin().args(["--json", "--does-not-exist"]).output().unwrap();
    assert_eq!(out.status.code(), Some(2));
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["schema"], 1);
    assert_eq!(v["ok"], false);
    let d = tempfile::tempdir().unwrap();
    let p = d.path().join("p");
    let profile = p.to_str().unwrap();
    let out = bin()
        .args([
            "--profile",
            profile,
            "--json",
            "config",
            "get",
            "lan_listen",
        ])
        .output()
        .unwrap();
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["value"], false);
    let out = bin()
        .args([
            "--profile",
            profile,
            "--json",
            "config",
            "set",
            "color",
            "invalid",
        ])
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(2));
    let out = bin()
        .args([
            "--profile",
            profile,
            "--json",
            "trust",
            "revoke",
            "11111111-2222-3333-4444-555555555555",
        ])
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(6));
}

#[test]
#[ignore = "requires localhost sockets; run test-interop.sh"]
fn scripts_require_consent_and_only_return_saved_after_ack() {
    use std::{
        net::{TcpListener, TcpStream},
        process::Stdio,
        thread,
        time::{Duration, Instant},
    };
    fn identity(path: &std::path::Path) -> serde_json::Value {
        let out = bin()
            .args([
                "--profile",
                path.to_str().unwrap(),
                "--json",
                "identity",
                "show",
            ])
            .output()
            .unwrap();
        assert!(out.status.success());
        serde_json::from_slice(&out.stdout).unwrap()
    }
    let d = tempfile::tempdir().unwrap();
    let a = d.path().join("a");
    let b = d.path().join("b");
    let aid = identity(&a);
    let bid = identity(&b);
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let address = format!("127.0.0.1:{port}");
    let mut server = bin()
        .args([
            "--profile",
            b.to_str().unwrap(),
            "--json",
            "--listen",
            &address,
            "--timeout",
            "0",
            "listen",
        ])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while TcpStream::connect(&address).is_err() {
        assert!(Instant::now() < deadline);
        thread::sleep(Duration::from_millis(30));
    }
    let out = bin()
        .args([
            "--profile",
            a.to_str().unwrap(),
            "--json",
            "--address",
            &address,
            "send",
            "--text",
            "unknown must fail",
        ])
        .output()
        .unwrap();
    assert_eq!(
        out.status.code(),
        Some(6),
        "{}",
        String::from_utf8_lossy(&out.stdout)
    );
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["error"]["code"], "INTERACTION_REQUIRED");
    assert!(!out.stdout.contains(&27));
    server.kill().unwrap();
    server.wait().unwrap();
    // The harness provisions known fixture roots explicitly; the public client has no --yes trust bypass.
    for (path, remote, endpoint) in [(&a, &bid, address.clone()), (&b, &aid, String::new())] {
        let connection = rusqlite::Connection::open(path.join("history.sqlite3")).unwrap();
        let peer = serde_json::json!({"id":remote["device_id"],"name":"fixture","root":remote["public_key"],"remembered":true,"verified":false,"endpoint":endpoint});
        connection
            .execute(
                "INSERT INTO peers VALUES(?,?) ON CONFLICT(id) DO UPDATE SET value=excluded.value",
                rusqlite::params![remote["device_id"].as_str().unwrap(), peer.to_string()],
            )
            .unwrap();
    }
    let out = bin()
        .args([
            "--profile",
            b.to_str().unwrap(),
            "config",
            "set",
            "auto_receive_trusted",
            "true",
        ])
        .output()
        .unwrap();
    assert!(out.status.success());
    let mut server = bin()
        .args([
            "--profile",
            b.to_str().unwrap(),
            "--json",
            "--receive-dir",
            d.path().join("received").to_str().unwrap(),
            "--listen",
            &address,
            "--timeout",
            "0",
            "listen",
        ])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let deadline = Instant::now() + Duration::from_secs(5);
    while TcpStream::connect(&address).is_err() {
        assert!(Instant::now() < deadline);
        thread::sleep(Duration::from_millis(30));
    }
    let out = bin()
        .args([
            "--profile",
            a.to_str().unwrap(),
            "--json",
            "--address",
            &address,
            "send",
            bid["device_id"].as_str().unwrap(),
            "--text",
            "脚本 👩‍💻",
        ])
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stdout)
    );
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["status"], "saved");
    assert_eq!(v["ok"], true);
    assert_eq!(v["operation"], "send_text");
    assert_eq!(v["read"], false);
    let file = d.path().join("source.bin");
    std::fs::write(&file, b"file\0data").unwrap();
    let out = bin()
        .args([
            "--profile",
            a.to_str().unwrap(),
            "--json",
            "--address",
            &address,
            "send",
            bid["device_id"].as_str().unwrap(),
            "--file",
            file.to_str().unwrap(),
        ])
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stdout)
    );
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["status"], "saved");
    assert_eq!(v["operation"], "send_file");
    server.kill().unwrap();
    server.wait().unwrap();
}
