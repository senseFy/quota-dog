#[cfg(target_os = "ios")]
mod c_api;
#[cfg(not(target_os = "ios"))]
mod jni_bridge;
mod signer;
mod tls;

use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, LazyLock, Mutex};

use signer::PlatformKey;
use tls::{PIN_LEN, Session};

#[derive(Debug)]
struct Failure(&'static str);

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for Failure {}

type Result<T> = std::result::Result<T, Failure>;

// Handles are never pointers and never reused, even after a failed session.
type Slot = Arc<Mutex<Option<Session>>>;
const MAX_SESSIONS: usize = 32;
#[derive(Default)]
struct Registry {
    last_id: i64,
    slots: HashMap<i64, Slot>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(Mutex::default);

fn registry() -> Result<std::sync::MutexGuard<'static, Registry>> {
    REGISTRY
        .lock()
        .map_err(|_| Failure("TLS registry unavailable"))
}

fn open_session(
    server: bool,
    spki: Vec<u8>,
    signer: PlatformKey,
    pin: Option<[u8; PIN_LEN]>,
) -> Result<i64> {
    let mut registry = registry()?;
    if registry.slots.len() >= MAX_SESSIONS {
        return Err(Failure("too many open TLS sessions"));
    }
    // No external callbacks in Session::new; check the cap before allocating TLS state.
    let signed = signer.signed.clone();
    let session = Session::new(server, spki, Arc::new(signer), signed, pin)?;
    let id = registry
        .last_id
        .checked_add(1)
        .ok_or(Failure("TLS handles exhausted"))?;
    registry.last_id = id;
    registry
        .slots
        .insert(id, Arc::new(Mutex::new(Some(session))));
    Ok(id)
}

fn with_session<T>(id: i64, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
    let slot = registry()?
        .slots
        .get(&id)
        .cloned()
        .ok_or(Failure("unknown TLS session"))?;
    // Never hold the registry lock across a platform callback. Reject busy handles.
    let mut guard = slot
        .try_lock()
        .map_err(|_| Failure("busy or failed TLS session"))?;
    let result = f(guard.as_mut().ok_or(Failure("failed TLS session"))?);
    if result.is_err() {
        guard.take(); // Discard keys, buffered plaintext, and any unsent records.
    }
    result
}
