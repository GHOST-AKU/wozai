use nearby_mesh::store::Store;
#[cfg(unix)]
use std::fs;

#[test]
fn missing_snapshot_is_an_empty_initial_state() {
    let dir = tempfile::tempdir().unwrap();
    let store = Store::open(dir.path()).unwrap();
    let snapshot = store.load().unwrap();
    assert!(snapshot.seen.is_empty());
    assert_eq!(snapshot.pending_lost, 0);
    assert_eq!(snapshot.saved_at, 0);
}

#[cfg(unix)]
#[test]
fn snapshot_symlinks_are_rejected_even_when_the_target_is_missing() {
    use std::os::unix::fs::symlink;
    for target_exists in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let target = dir.path().join("snapshot-target");
        let contents = br#"{"seen":{},"pending_lost":0,"saved_at":0}"#;
        if target_exists {
            fs::write(&target, contents).unwrap();
        }
        let path = dir.path().join("seen.json");
        symlink(&target, &path).unwrap();

        assert!(
            store.load().is_err(),
            "snapshot symlink was accepted (target_exists={target_exists})"
        );
        assert_eq!(fs::read_link(&path).unwrap(), target);
        if target_exists {
            assert_eq!(fs::read(&target).unwrap(), contents);
        } else {
            assert!(!target.exists());
        }
    }
}

#[cfg(unix)]
#[test]
fn identity_symlinks_are_rejected_without_replacing_or_writing_the_target() {
    use std::os::unix::fs::symlink;
    for target_exists in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        let store = Store::open(dir.path()).unwrap();
        let target = dir.path().join("seed-target");
        if target_exists {
            fs::write(&target, [17; 32]).unwrap();
        }
        let path = dir.path().join("identity.seed");
        symlink(&target, &path).unwrap();

        assert!(store.identity().is_err());
        assert_eq!(fs::read_link(&path).unwrap(), target);
        if target_exists {
            assert_eq!(fs::read(&target).unwrap(), [17; 32]);
        } else {
            assert!(!target.exists());
        }
    }
}

#[cfg(unix)]
#[test]
fn snapshot_metadata_errors_are_not_treated_as_missing_state() {
    let parent = tempfile::tempdir().unwrap();
    let state = parent.path().join("state");
    let moved = parent.path().join("state-moved");
    let store = Store::open(&state).unwrap();
    fs::rename(&state, &moved).unwrap();
    fs::write(&state, b"fixture: the state path is no longer a directory").unwrap();

    assert!(store.load().is_err());
    assert!(!moved.join("seen.json").exists());
}

#[test]
fn repeated_snapshot_replacement_survives_close_and_reopen() {
    let dir = tempfile::tempdir().unwrap();
    let seen = std::collections::BTreeMap::from([("a".repeat(64), 1200)]);
    {
        let store = Store::open(dir.path()).unwrap();
        store.save(&seen, 3, 1000).unwrap();
        store.save(&seen, 0, 1001).unwrap();
    }
    let loaded = Store::open(dir.path()).unwrap().load().unwrap();
    assert_eq!(loaded.seen, seen);
    assert_eq!(loaded.pending_lost, 0);
    assert_eq!(loaded.saved_at, 1001);
}

#[cfg(windows)]
#[test]
fn windows_junction_is_rejected_as_state_directory() {
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("target");
    let junction = dir.path().join("junction");
    std::fs::create_dir(&target).unwrap();
    let result = std::process::Command::new("cmd")
        .args(["/C", "mklink", "/J"])
        .arg(&junction)
        .arg(&target)
        .output()
        .unwrap();
    assert!(result.status.success(), "junction fixture failed");
    assert!(Store::open(&junction).is_err());
    assert!(!target.join("identity.seed").exists());
    std::fs::remove_dir(junction).unwrap();
}
