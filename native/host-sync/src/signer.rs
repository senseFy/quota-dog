use std::fmt;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use aws_lc_rs::signature::ParsedPublicKey;
use jni::objects::{Global, JByteArray, JObject, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};
use rustls::pki_types::SubjectPublicKeyInfoDer;
use rustls::sign::{Signer, SigningKey};
use rustls::{Error, SignatureAlgorithm, SignatureScheme};

use crate::tls::SCHEME;
use crate::{Failure, Result, bytes};

struct Callback {
    vm: JavaVM,
    object: Global<JObject<'static>>,
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
    pub fn new(env: &mut Env<'_>, signer: &JObject<'_>, key: ParsedPublicKey) -> Result<Self> {
        let class = env.get_object_class(signer)?;
        env.get_method_id(class, jni_str!("sign"), jni_sig!("([B)[B"))?;
        Ok(Self {
            callback: Arc::new(Callback {
                vm: env.get_java_vm()?,
                object: env.new_global_ref(signer)?,
                key,
            }),
            signed: Arc::new(AtomicBool::new(false)),
        })
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
        // rustls calls synchronously within a JNI entrypoint. No Env or local
        // reference escapes its calling thread/frame. SHA256withECDSA takes
        // rustls's unhashed CertificateVerify input and returns ASN.1 DER.
        let sig = self
            .callback
            .vm
            .with_local_frame(8, |env| -> Result<Vec<u8>> {
                let message = env.byte_array_from_slice(message)?;
                let obj = env
                    .call_method(
                        &self.callback.object,
                        jni_str!("sign"),
                        jni_sig!("([B)[B"),
                        &[JValue::Object(&message)],
                    )?
                    .l()?;
                let sig = env.cast_local::<JByteArray>(obj)?;
                bytes(env, &sig, 72).map_err(|_| Failure("invalid platform signature"))
            })
            .map_err(|_| rejected())?;
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
