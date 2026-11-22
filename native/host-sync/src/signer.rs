use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use aws_lc_rs::signature::ParsedPublicKey;
use rustls::pki_types::SubjectPublicKeyInfoDer;
use rustls::sign::{Signer, SigningKey};
use rustls::{Error, SignatureAlgorithm, SignatureScheme};

use crate::Result;
use crate::tls::SCHEME;

pub const MAX_SIGNATURE: usize = 72; // maximum DER-encoded ECDSA P-256 (r,s)
type Sign = dyn Fn(&[u8]) -> Result<Vec<u8>> + Send + Sync;

struct Callback {
    sign: Box<Sign>,
    key: ParsedPublicKey,
}

#[derive(Clone)]
pub struct PlatformKey {
    callback: Arc<Callback>,
    pub signed: Arc<AtomicBool>,
}

impl fmt::Debug for PlatformKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("PlatformP256SigningKey")
    }
}

impl PlatformKey {
    pub fn new(
        key: ParsedPublicKey,
        sign: impl Fn(&[u8]) -> Result<Vec<u8>> + Send + Sync + 'static,
    ) -> Self {
        Self {
            callback: Arc::new(Callback {
                sign: Box::new(sign),
                key,
            }),
            signed: Arc::new(AtomicBool::new(false)),
        }
    }
}

impl SigningKey for PlatformKey {
    fn choose_scheme(&self, offered: &[SignatureScheme]) -> Option<Box<dyn Signer>> {
        offered
            .contains(&SCHEME)
            .then(|| Box::new(self.clone()) as Box<dyn Signer>)
    }
    fn algorithm(&self) -> SignatureAlgorithm {
        SignatureAlgorithm::ECDSA
    }
    fn public_key(&self) -> Option<SubjectPublicKeyInfoDer<'_>> {
        Some(self.callback.key.as_ref().into())
    }
}

impl Signer for PlatformKey {
    fn scheme(&self) -> SignatureScheme {
        SCHEME
    }

    fn sign(&self, message: &[u8]) -> std::result::Result<Vec<u8>, Error> {
        let rejected = || Error::General("platform TLS signing failed".into());
        if message.len() > 256 {
            return Err(rejected());
        }
        let sig = (self.callback.sign)(message).map_err(|_| rejected())?;
        // Also reject a stale/mismatched platform key locally rather than
        // publishing a peer pin for a handshake we cannot authenticate in.
        self.callback
            .key
            .verify_sig(message, &sig)
            .map_err(|_| rejected())?;
        self.signed.store(true, Ordering::Relaxed);
        Ok(sig)
    }
}
