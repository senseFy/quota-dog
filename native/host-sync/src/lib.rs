mod signer;
mod tls;

use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, LazyLock, Mutex, TryLockError};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JObject};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned};
use tls::{MAX_PLAIN, MAX_WIRE, PIN_LEN, SPKI_LEN, Session};
use zeroize::Zeroizing;

#[derive(Debug)]
struct Failure(&'static str);

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for Failure {}

impl From<jni::errors::Error> for Failure {
    fn from(_: jni::errors::Error) -> Self {
        Self("host TLS JNI call failed")
    }
}

type Result<T> = std::result::Result<T, Failure>;

// No raw pointers cross JNI. IDs are never reused, even after a failed session.
type Slot = Arc<Mutex<Option<Session>>>;
const MAX_SESSIONS: usize = 32;
struct Registry {
    last_id: jlong,
    slots: HashMap<jlong, Slot>,
}

static REGISTRY: LazyLock<Mutex<Registry>> = LazyLock::new(|| {
    Mutex::new(Registry {
        last_id: 0,
        slots: HashMap::new(),
    })
});

fn registry() -> Result<std::sync::MutexGuard<'static, Registry>> {
    REGISTRY
        .lock()
        .map_err(|_| Failure("TLS registry unavailable"))
}

fn with_session<T>(id: jlong, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
    let slot = registry()?
        .slots
        .get(&id)
        .cloned()
        .ok_or(Failure("unknown TLS session"))?;
    // Never hold the registry lock across a Java callback. Reject same-session
    // concurrent/reentrant use rather than deadlocking on the signer's thread.
    let mut guard = slot
        .try_lock()
        .map_err(|_| Failure("busy or failed TLS session"))?;
    let result = f(guard.as_mut().ok_or(Failure("failed TLS session"))?);
    if result.is_err() {
        guard.take(); // Discard keys, buffered plaintext, and any unsent records.
    }
    result
}

fn bytes(env: &Env<'_>, array: &JByteArray<'_>, max: usize) -> Result<Vec<u8>> {
    if array.is_null() || array.len(env)? > max {
        return Err(Failure("missing or oversized TLS argument"));
    }
    Ok(env.convert_byte_array(array)?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_open(
    mut entry: EnvUnowned<'_>,
    _this: JObject<'_>,
    server: jboolean,
    spki: JByteArray<'_>,
    signer: JObject<'_>,
    peer_pin: JByteArray<'_>,
) -> jlong {
    entry
        .with_env(|env| {
            let pin = if peer_pin.is_null() {
                None
            } else {
                Some(
                    bytes(env, &peer_pin, PIN_LEN)?
                        .try_into()
                        .map_err(|_| Failure("pin must be SHA-256(SPKI)"))?,
                )
            };
            if !server && pin.is_none() {
                return Err(Failure("client requires a server pin"));
            }
            let spki = bytes(env, &spki, SPKI_LEN)?;
            let key =
                tls::parse_spki(&spki).map_err(|_| Failure("expected canonical P-256 SPKI DER"))?;
            let signer = signer::PlatformKey::new(env, &signer, key)?;
            let mut registry = registry()?;
            if registry.slots.len() >= MAX_SESSIONS {
                return Err(Failure("too many open TLS sessions"));
            }
            // No Java calls in Session::new. Check the cap *before* allocating
            // connection state, including when independent threads call open.
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
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_receive(
    mut entry: EnvUnowned<'_>,
    _this: JObject<'_>,
    id: jlong,
    ciphertext: JByteArray<'_>,
) {
    entry
        .with_env(|env| with_session(id, |s| s.receive(&bytes(env, &ciphertext, MAX_WIRE)?)))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_send(
    mut entry: EnvUnowned<'_>,
    _this: JObject<'_>,
    id: jlong,
    plaintext: JByteArray<'_>,
) {
    entry
        .with_env(|env| {
            with_session(id, |s| {
                s.send(&Zeroizing::new(bytes(env, &plaintext, MAX_PLAIN)?))
            })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_outbound<'local>(
    mut entry: EnvUnowned<'local>,
    _this: JObject<'_>,
    id: jlong,
) -> JByteArray<'local> {
    entry
        .with_env(|env| with_session(id, |s| Ok(env.byte_array_from_slice(&s.outbound()?)?)))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_plaintext<'local>(
    mut entry: EnvUnowned<'local>,
    _this: JObject<'_>,
    id: jlong,
) -> JByteArray<'local> {
    entry
        .with_env(|env| with_session(id, |s| Ok(env.byte_array_from_slice(&s.take_plaintext())?)))
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_peerPin<'local>(
    mut entry: EnvUnowned<'local>,
    _this: JObject<'_>,
    id: jlong,
) -> JByteArray<'local> {
    entry
        .with_env(|env| {
            with_session(id, |s| match s.peer_pin() {
                Some(pin) => Ok(env.byte_array_from_slice(pin)?),
                None => Ok(JByteArray::null()),
            })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_saien_quotadog_hostsync_NativeHostTls_close(
    mut entry: EnvUnowned<'_>,
    _this: JObject<'_>,
    id: jlong,
) {
    entry
        .with_env(|_| {
            let mut map = registry()?;
            let slot = map.slots.get(&id).ok_or(Failure("unknown TLS session"))?;
            let session = match slot.try_lock() {
                Ok(mut slot) => slot.take(),
                Err(TryLockError::Poisoned(e)) => e.into_inner().take(),
                Err(TryLockError::WouldBlock) => return Err(Failure("busy TLS session")),
            };
            map.slots.remove(&id);
            drop(map);
            drop(session);
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}
