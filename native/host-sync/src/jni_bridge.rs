use std::sync::TryLockError;

use aws_lc_rs::signature::ParsedPublicKey;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JObject, JValue};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned, jni_sig, jni_str};
use zeroize::Zeroizing;

use crate::signer::{MAX_SIGNATURE, PlatformKey};
use crate::tls::{self, MAX_PLAIN, MAX_WIRE, PIN_LEN, SPKI_LEN};
use crate::{Failure, Result, open_session, registry, with_session};

impl From<jni::errors::Error> for Failure {
    fn from(_: jni::errors::Error) -> Self {
        Self("host TLS JNI call failed")
    }
}

fn bytes(env: &Env<'_>, array: &JByteArray<'_>, max: usize) -> Result<Vec<u8>> {
    if array.is_null() || array.len(env)? > max {
        return Err(Failure("missing or oversized TLS argument"));
    }
    Ok(env.convert_byte_array(array)?)
}

fn platform_key(
    env: &mut Env<'_>,
    signer: &JObject<'_>,
    key: ParsedPublicKey,
) -> Result<PlatformKey> {
    let class = env.get_object_class(signer)?;
    env.get_method_id(class, jni_str!("sign"), jni_sig!("([B)[B"))?;
    let vm = env.get_java_vm()?;
    let object = env.new_global_ref(signer)?;
    Ok(PlatformKey::new(key, move |message| {
        // Only a global reference crosses JNI calls/threads. The callback is synchronous.
        vm.with_local_frame(8, |env| -> Result<Vec<u8>> {
            let message = env.byte_array_from_slice(message)?;
            let obj = env
                .call_method(
                    &object,
                    jni_str!("sign"),
                    jni_sig!("([B)[B"),
                    &[JValue::Object(&message)],
                )?
                .l()?;
            let sig = env.cast_local::<JByteArray>(obj)?;
            bytes(env, &sig, MAX_SIGNATURE)
        })
    }))
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
            open_session(server, spki, platform_key(env, &signer, key)?, pin)
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
        .with_env(|env| {
            with_session(id, |s| {
                s.drain_plaintext(MAX_PLAIN, |p| Ok(env.byte_array_from_slice(p)?))
            })
        })
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
