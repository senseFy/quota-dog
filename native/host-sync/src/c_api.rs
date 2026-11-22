//! iOS client only. Foreign buffer and callback lifetimes are specified in qd_host_tls.h.
use std::ffi::c_void;
use std::panic::{UnwindSafe, catch_unwind};
use std::{ptr, slice};

use crate::signer::{MAX_SIGNATURE, PlatformKey};
use crate::tls::{PIN_LEN, SPKI_LEN, Session, parse_spki};
use crate::{Failure, REGISTRY, Result, open_session, with_session};

const MAX_CHUNK: usize = 65536;
const MAX_FRAME: usize = 4 + 16384;
type SignFn = unsafe extern "C" fn(*mut c_void, *const u8, i32, *mut u8, i32) -> i32;

struct ForeignSigner {
    sign: SignFn,
    ctx: *mut c_void,
}

// SAFETY: Rust never dereferences ctx. The owner keeps it alive through close
// and chooses the calling thread. A session lock serializes synchronous calls.
unsafe impl Send for ForeignSigner {}
unsafe impl Sync for ForeignSigner {}

impl ForeignSigner {
    fn signature(&self, message: &[u8]) -> Result<Vec<u8>> {
        let mut out = [0; MAX_SIGNATURE];
        // SAFETY: PlatformKey bounds the message to 256 bytes. Both buffers
        // live through this call only; the callback must respect its capacity.
        let n = unsafe {
            (self.sign)(
                self.ctx,
                message.as_ptr(),
                message.len() as i32,
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        if !(1..=MAX_SIGNATURE as i32).contains(&n) {
            return Err(Failure("invalid platform signature length"));
        }
        Ok(out[..n as usize].to_vec()) // PlatformKey independently verifies the DER signature.
    }
}

// Do not unwind into C. A panic with a session locked poisons its slot;
// subsequent operations fail closed, and close can still release the slot.
fn ffi<T>(failure: T, call: impl FnOnce() -> Result<T> + UnwindSafe) -> T {
    catch_unwind(call)
        .ok()
        .and_then(Result::ok)
        .unwrap_or(failure)
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_open_client(
    spki: *const u8,
    spki_len: i32,
    pin: *const u8,
    pin_len: i32,
    sign: Option<SignFn>,
    ctx: *mut c_void,
) -> i64 {
    ffi(0, || {
        let cb = ForeignSigner {
            sign: sign.ok_or(Failure("missing signer"))?,
            ctx,
        };
        if spki.is_null()
            || spki_len != SPKI_LEN as i32
            || pin.is_null()
            || pin_len != PIN_LEN as i32
        {
            return Err(Failure("missing or invalid client identity or server pin"));
        }
        // SAFETY: the caller supplies readable buffers of the checked lengths.
        // Copy both; only the opaque signing context survives open.
        let spki = unsafe { slice::from_raw_parts(spki, SPKI_LEN) }.to_vec();
        let pin = unsafe { pin.cast::<[u8; PIN_LEN]>().read() };
        let key = parse_spki(&spki).map_err(|_| Failure("expected canonical P-256 SPKI DER"))?;
        open_session(
            false,
            spki,
            PlatformKey::new(key, move |m| cb.signature(m)),
            Some(pin),
        )
    })
}

unsafe fn input(
    id: i64,
    data: *const u8,
    len: i32,
    max: usize,
    apply: fn(&mut Session, &[u8]) -> Result<()>,
) -> i32 {
    ffi(-1, || {
        with_session(id, |s| {
            if len < 0 || len as usize > max || (len != 0 && data.is_null()) {
                return Err(Failure("invalid TLS input buffer"));
            }
            // SAFETY: nonempty input is readable and unchanged for this call.
            let bytes = if len == 0 {
                &[]
            } else {
                unsafe { slice::from_raw_parts(data, len as usize) }
            };
            apply(s, bytes)?;
            Ok(0)
        })
    })
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_receive(id: i64, data: *const u8, len: i32) -> i32 {
    unsafe { input(id, data, len, MAX_CHUNK, Session::receive) }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_send(id: i64, data: *const u8, len: i32) -> i32 {
    unsafe { input(id, data, len, MAX_FRAME, Session::send) }
}

enum Output {
    Wire,
    Plaintext,
    Pin,
}

unsafe fn output(id: i64, out: *mut u8, cap: i32, kind: Output) -> i32 {
    ffi(-1, || {
        with_session(id, |s| {
            if cap <= 0 || out.is_null() || (matches!(kind, Output::Pin) && cap < PIN_LEN as i32) {
                return Err(Failure("invalid TLS output buffer"));
            }
            let limit = MAX_CHUNK.min(cap as usize);
            let copy = |bytes: &[u8]| {
                if bytes.len() > limit {
                    return Err(Failure("TLS output too large"));
                }
                // SAFETY: out has cap writable bytes, disjoint from Rust-owned state.
                // Do not manufacture a Rust reference to potentially uninitialized C bytes.
                unsafe { ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len()) };
                Ok(bytes.len() as i32)
            };
            match kind {
                Output::Wire => copy(&s.outbound_up_to(limit)?),
                Output::Plaintext => s.drain_plaintext(limit, copy),
                Output::Pin => s.peer_pin().map_or(Ok(0), |pin| copy(pin)),
            }
        })
    })
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_outbound(id: i64, out: *mut u8, cap: i32) -> i32 {
    unsafe { output(id, out, cap, Output::Wire) }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_plaintext(id: i64, out: *mut u8, cap: i32) -> i32 {
    unsafe { output(id, out, cap, Output::Plaintext) }
}

#[unsafe(no_mangle)]
unsafe extern "C" fn qd_tls_peer_pin(id: i64, out: *mut u8, cap: i32) -> i32 {
    unsafe { output(id, out, cap, Output::Pin) }
}

#[unsafe(no_mangle)]
extern "C" fn qd_tls_close(id: i64) {
    ffi((), || {
        let lock_registry = || REGISTRY.lock().unwrap_or_else(|p| p.into_inner());
        let slot = lock_registry().slots.get(&id).cloned();
        if let Some(slot) = slot {
            // No registry lock while waiting. Even a racing close must not let
            // the owner release ctx while an already-started callback uses it.
            slot.lock().unwrap_or_else(|p| p.into_inner()).take();
            lock_registry().slots.remove(&id);
        }
        Ok(())
    });
}
