//! Small, length-bounded C ABI for the existing engine. Opaque IDs are never raw pointers.
use nearby_mesh::embedded::{Engine, MAX_CONTROL_BYTES, MAX_EVENT_BYTES};
use std::{
    collections::BTreeMap,
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

struct Host {
    engine: Engine,
    pending: Mutex<Option<Vec<u8>>>,
}
static HOSTS: OnceLock<Mutex<BTreeMap<u64, Arc<Host>>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn hosts() -> &'static Mutex<BTreeMap<u64, Arc<Host>>> {
    HOSTS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn host(id: u64) -> Option<Arc<Host>> {
    hosts().lock().ok()?.get(&id).cloned()
}
fn guarded<T: Default>(f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_default()
}

/// # Safety
/// `path` must address `length` readable bytes. The path must be absolute UTF-8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nm_mesh_start(path: *const u8, length: usize) -> u64 {
    guarded(|| {
        if path.is_null() || length == 0 || length > 4096 {
            return 0;
        }
        // SAFETY: the caller supplies a readable buffer; lengths are bounded before access.
        let bytes = unsafe { std::slice::from_raw_parts(path, length) };
        let Ok(path) = std::str::from_utf8(bytes) else {
            return 0;
        };
        if path.contains('\0') || !Path::new(path).is_absolute() {
            return 0;
        }
        let Ok(mut registry) = hosts().lock() else {
            return 0;
        };
        if registry.len() >= 4 {
            return 0;
        }
        let Ok(engine) = Engine::start(Path::new(path)) else {
            return 0;
        };
        let Ok(id) = NEXT.fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| n.checked_add(1))
        else {
            return 0;
        };
        registry.insert(
            id,
            Arc::new(Host {
                engine,
                pending: Mutex::new(None),
            }),
        );
        id
    })
}

/// # Safety
/// `bytes` must address `length` readable bytes. Returns 1 on acceptance, 0 on rejection.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nm_mesh_command(id: u64, bytes: *const u8, length: usize) -> i32 {
    guarded(|| {
        if bytes.is_null() || length == 0 || length > MAX_CONTROL_BYTES {
            return 0;
        }
        let Some(host) = host(id) else {
            return 0;
        };
        // SAFETY: the caller supplies a readable buffer, bounded above.
        i32::from(
            host.engine
                .command(unsafe { std::slice::from_raw_parts(bytes, length) })
                .is_ok(),
        )
    })
}

/// # Safety
/// `output` must address `capacity` writable bytes; capacity must be 1..=16384.
/// Returns bytes written, 0 for no event, -1 invalid/stopped, -3 short buffer (event retained).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn nm_mesh_poll(
    id: u64,
    output: *mut u8,
    capacity: usize,
    timeout_ms: u32,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        if output.is_null() || capacity == 0 || capacity > MAX_EVENT_BYTES {
            return -1;
        }
        let Some(host) = host(id) else {
            return -1;
        };
        let Ok(mut pending) = host.pending.lock() else {
            return -1;
        };
        if pending.is_none() {
            match host
                .engine
                .event(Duration::from_millis(timeout_ms.min(250).into()))
            {
                Ok(Some(value)) => *pending = serde_json::to_vec(&value).ok(),
                Ok(None) => return if host.engine.state() == 1 { 0 } else { -1 },
                Err(_) => return -1,
            }
        }
        let Some(bytes) = pending.as_ref() else {
            return -1;
        };
        if bytes.len() > capacity {
            return -3;
        }
        // SAFETY: writable output of sufficient capacity is required by the ABI.
        unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len());
        }
        let count = bytes.len() as i32;
        *pending = None;
        count
    }))
    .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "C" fn nm_mesh_state(id: u64) -> i32 {
    guarded(|| host(id).map_or(-1, |host| host.engine.state()))
}

/// Idempotent. Must be called off the UI thread because shutdown checkpoints private state.
#[unsafe(no_mangle)]
pub extern "C" fn nm_mesh_stop(id: u64) {
    guarded(|| {
        let retired = hosts()
            .lock()
            .ok()
            .and_then(|mut registry| registry.remove(&id));
        if let Some(host) = retired {
            host.engine.close();
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn abi_retains_short_events_rejects_invalid_handles_and_closes_idempotently() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().to_str().unwrap().as_bytes();
        let id = unsafe { nm_mesh_start(path.as_ptr(), path.len()) };
        assert_ne!(id, 0);
        assert_eq!(unsafe { nm_mesh_command(id, std::ptr::null(), 5) }, 0);
        let mut out = [0u8; MAX_EVENT_BYTES];
        assert_eq!(unsafe { nm_mesh_poll(id, out.as_mut_ptr(), 1, 0) }, -3);
        let size = unsafe { nm_mesh_poll(id, out.as_mut_ptr(), out.len(), 0) };
        assert!(size > 1);
        let event: serde_json::Value = serde_json::from_slice(&out[..size as usize]).unwrap();
        assert_eq!(event["event"], "ready");
        assert_eq!(nm_mesh_state(id), 1);
        nm_mesh_stop(id);
        nm_mesh_stop(id);
        assert_eq!(nm_mesh_state(id), -1);
        assert_eq!(
            unsafe { nm_mesh_poll(id, out.as_mut_ptr(), out.len(), 0) },
            -1
        );
        assert_eq!(unsafe { nm_mesh_start(std::ptr::null(), 100) }, 0);
        assert_eq!(unsafe { nm_mesh_start(b"relative".as_ptr(), 8) }, 0);
    }
}
