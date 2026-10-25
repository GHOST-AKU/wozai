//! Cross-language integration checks are run by tools/test-interop.sh.
use crate::{
    crypto,
    files::Engine,
    profile::{self, Profile},
    wire::*,
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use std::{
    fs,
    io::Write,
    net::{TcpListener, TcpStream},
    process::{Command, Stdio},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

struct JavaGuard(Option<std::process::Child>);
impl std::ops::Deref for JavaGuard {
    type Target = std::process::Child;
    fn deref(&self) -> &Self::Target {
        self.0.as_ref().unwrap()
    }
}
impl std::ops::DerefMut for JavaGuard {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.0.as_mut().unwrap()
    }
}
impl Drop for JavaGuard {
    fn drop(&mut self) {
        if let Some(mut child) = self.0.take() {
            let _ = child.kill();
            if let Ok(out) = child.wait_with_output()
                && !out.stderr.is_empty()
            {
                eprintln!(
                    "Java fixture diagnostics: {}",
                    String::from_utf8_lossy(&out.stderr)
                );
            }
        }
    }
}
fn exchange(java_initiator: bool) {
    let classes =
        std::env::var("NEARBYIM_JAVA_CLASSES").expect("run terminal/tools/test-interop.sh");
    let dir = tempfile::tempdir().unwrap();
    let image = image::RgbaImage::from_fn(96, 96, |x, y| {
        image::Rgba([x as u8, y as u8, (x * y) as u8, 255])
    });
    let mut encoded = std::io::Cursor::new(Vec::new());
    image
        .write_to(&mut encoded, image::ImageFormat::Png)
        .unwrap();
    let avatar_png = encoded.into_inner();
    fs::write(dir.path().join("rust-avatar.png"), &avatar_png).unwrap();
    let portfile = dir.path().join("port");
    let listener = if java_initiator {
        Some(TcpListener::bind("127.0.0.1:0").unwrap())
    } else {
        None
    };
    let mut cmd = Command::new("java");
    cmd.args([
        "-Xmx48m",
        "-cp",
        &classes,
        "dev.ghost.nearbyim.core.TerminalInteropPeer",
    ]);
    if let Some(l) = &listener {
        cmd.arg("client")
            .arg(l.local_addr().unwrap().port().to_string());
    } else {
        cmd.arg("server").arg(&portfile);
    }
    let mut java = JavaGuard(Some(
        cmd.arg(dir.path().join("java"))
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap(),
    ));
    let stream = if let Some(l) = listener {
        l.accept().unwrap().0
    } else {
        let until = Instant::now() + Duration::from_secs(15);
        while !portfile.exists() {
            assert!(Instant::now() < until, "Java did not bind");
            std::thread::sleep(Duration::from_millis(20));
        }
        TcpStream::connect(format!(
            "127.0.0.1:{}",
            fs::read_to_string(&portfile).unwrap()
        ))
        .unwrap()
    };
    let profile = Arc::new(Mutex::new(
        Profile::open(dir.path().join("rust"), Some("Rust 👩‍💻")).unwrap(),
    ));
    let identity = profile.lock().unwrap().identity.clone();
    crate::avatar::save_local(&profile.lock().unwrap(), avatar_png).unwrap();
    let mut e = crypto::establish(stream, !java_initiator, &identity).unwrap();
    e.stream
        .set_read_timeout(Some(Duration::from_secs(20)))
        .unwrap();
    let code = profile::safety(&identity.public, &STANDARD.decode(&e.peer.root).unwrap());
    let mut writer = e.stream.try_clone().unwrap();
    let send = &mut e.send;
    let mut write = |f: Frame| {
        writer.write_all(&send.encrypt(3, &f.encode()?)?)?;
        Ok(())
    };
    write(Frame::control(READY)).unwrap();
    let (h, b) = crypto::read_envelope(&mut e.stream, 3, MAX_PLAIN + 16).unwrap();
    assert_eq!(
        Frame::decode(&e.receive.decrypt(&h, &b).unwrap())
            .unwrap()
            .kind,
        READY
    );
    assert_ne!(e.peer.capabilities & 4, 0);
    let mut avatars = crate::avatar::Sync::new(&profile.lock().unwrap(), &e.peer.root).unwrap();
    avatars
        .local_changed(&profile.lock().unwrap(), &mut write)
        .unwrap();
    let content: Vec<_> = (0..3 * BLOCK as usize + 17)
        .map(|i| (i as u8).wrapping_mul(31).wrapping_add(7))
        .collect();
    let source = dir.path().join("rust-source.bin");
    fs::write(&source, &content).unwrap();
    let mut files = Engine::new(
        profile.clone(),
        e.peer.id.clone(),
        e.peer.root.clone(),
        e.generation,
        dir.path().join("received"),
    );
    files.offer(&source, &mut write).unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    write(Frame {
        kind: TEXT,
        time: 1234,
        id: id.clone(),
        body: format!("Rust 你好 👩‍💻\n{code}"),
        data: vec![],
    })
    .unwrap();
    let (mut text, mut ack, mut white, mut received, mut saved) =
        (false, false, false, false, false);
    // A tiny read timeout would lose partial envelopes. A separate reader preserves framing.
    let (tx, rx) = std::sync::mpsc::sync_channel(64);
    let mut receive = e.receive;
    let mut reader = e.stream.try_clone().unwrap();
    std::thread::spawn(move || {
        loop {
            let r = (|| {
                let (h, b) = crypto::read_envelope(&mut reader, 3, MAX_PLAIN + 16)?;
                Frame::decode(&receive.decrypt(&h, &b)?)
            })();
            let failed = r.is_err();
            if tx.send(r).is_err() || failed {
                break;
            }
        }
    });
    let deadline = Instant::now() + Duration::from_secs(25);
    while Instant::now() < deadline {
        if let Ok(result) = rx.recv_timeout(if files.can_pump() {
            Duration::ZERO
        } else {
            Duration::from_millis(10)
        }) {
            let f = result.unwrap();
            match f.kind {
                TEXT => {
                    if f.body == "\u{a0}\u{2003}" {
                        white = true;
                    } else {
                        assert_eq!(f.body, format!("Java 你好 👩‍💻\n{code}"));
                        text = true;
                    }
                    let mut r = Frame::control(ACK);
                    r.id = f.id;
                    write(r).unwrap();
                }
                ACK => {
                    assert_eq!(f.id, id);
                    ack = true;
                }
                PING => write(Frame::control(PONG)).unwrap(),
                PONG => {}
                FILE => files
                    .handle(Packet::decode(&f.data).unwrap(), &mut write)
                    .unwrap(),
                crate::avatar::FAMILY => avatars
                    .handle(
                        crate::avatar::Packet::decode(&f.data).unwrap(),
                        &profile.lock().unwrap(),
                        &mut write,
                    )
                    .unwrap(),
                BYE => break,
                _ => panic!("unexpected frame"),
            }
        }
        files.pump(&mut write).unwrap();
        for s in files.notices.drain(..) {
            if s.state == "received" {
                assert_eq!(fs::read(&s.destination).unwrap(), content);
                received = true;
            }
            if s.state == "saved" {
                saved = true;
            }
        }
    }
    assert!(
        text && ack && white && received && saved,
        "incomplete Rust exchange: {text} {ack} {white} {received} {saved}"
    );
    assert!(
        avatars.failures.is_empty(),
        "avatar task failed: {:?}",
        avatars.failures
    );
    assert_eq!(
        crate::avatar::snapshot(&profile.lock().unwrap(), &e.peer.root)
            .unwrap()
            .png,
        fs::read(dir.path().join("java/java-avatar.png")).unwrap()
    );
    let _ = e.stream.shutdown(std::net::Shutdown::Both);
    let deadline = Instant::now() + Duration::from_secs(5);
    while java.try_wait().unwrap().is_none() {
        if Instant::now() > deadline {
            java.kill().unwrap();
            panic!("Java fixture did not exit");
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let out = java.0.take().unwrap().wait_with_output().unwrap();
    assert!(
        out.status.success(),
        "{}\n{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    println!("{}", String::from_utf8_lossy(&out.stdout));
}
#[test]
#[ignore = "requires compiled production Java fixture"]
fn production_java_initiator() {
    exchange(true)
}
#[test]
#[ignore = "requires compiled production Java fixture"]
fn production_java_responder() {
    exchange(false)
}
