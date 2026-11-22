use std::fmt;
use std::io::{Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use aws_lc_rs::digest::{SHA256, digest};
use aws_lc_rs::encoding::AsDer;
use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1, ParsedPublicKey};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::{AlwaysResolvesClientRawPublicKeys, Resumption};
use rustls::crypto::{
    WebPkiSupportedAlgorithms, aws_lc_rs as aws, verify_tls13_signature_with_raw_key,
};
use rustls::pki_types::{CertificateDer, ServerName, SubjectPublicKeyInfoDer, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::server::{AlwaysResolvesServerRawPublicKeys, NoServerSessionStorage};
use rustls::sign::{CertifiedKey, SigningKey};
use rustls::{
    CertificateError, ClientConfig, ClientConnection, Connection, DigitallySignedStruct,
    DistinguishedName, Error, HandshakeKind, ProtocolVersion, ServerConfig, ServerConnection,
    SignatureScheme,
};
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::{Failure, Result};

#[cfg(test)]
mod tests;

pub const SCHEME: SignatureScheme = SignatureScheme::ECDSA_NISTP256_SHA256;
pub const SPKI_LEN: usize = 91; // canonical DER for named P-256 and an uncompressed point
pub const PIN_LEN: usize = 32;
pub const MAX_PLAIN: usize = 1024 * 1024;
pub const MAX_WIRE: usize = MAX_PLAIN + 64 * 1024; // allow TLS record overhead
const MAX_HANDSHAKE: usize = 64 * 1024;
type Pin = [u8; PIN_LEN];

pub fn parse_spki(spki: &[u8]) -> std::result::Result<ParsedPublicKey, Error> {
    let invalid = || Error::InvalidCertificate(CertificateError::BadEncoding);
    if spki.len() != SPKI_LEN {
        return Err(invalid());
    }
    let key = ParsedPublicKey::new(&ECDSA_P256_SHA256_ASN1, spki).map_err(|_| invalid())?;
    // AWS-LC's parser also accepts bare points. Require the identity bytes to
    // be exactly the DER serialization, not just something this parser accepts.
    if key.as_der().map_err(|_| invalid())?.as_ref() != spki {
        return Err(invalid());
    }
    Ok(key)
}

fn fingerprint(spki: &[u8]) -> Pin {
    let mut pin = [0; PIN_LEN];
    pin.copy_from_slice(digest(&SHA256, spki).as_ref());
    pin
}

struct Peer {
    pin: Option<Pin>,
    algorithms: WebPkiSupportedAlgorithms,
}

impl fmt::Debug for Peer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("RpkPeerVerifier")
    }
}

impl Peer {
    fn identity(
        &self,
        key: &CertificateDer<'_>,
        others: &[CertificateDer<'_>],
    ) -> std::result::Result<(), Error> {
        if !others.is_empty() {
            return Err(CertificateError::BadEncoding.into());
        }
        parse_spki(key.as_ref())?;
        if let Some(pin) = self.pin
            && !bool::from(pin.ct_eq(&fingerprint(key.as_ref())))
        {
            return Err(CertificateError::ApplicationVerificationFailure.into());
        }
        Ok(())
    }

    fn signature(
        &self,
        msg: &[u8],
        key: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, Error> {
        if dss.scheme != SCHEME {
            return Err(CertificateError::BadSignature.into());
        }
        verify_tls13_signature_with_raw_key(
            msg,
            &SubjectPublicKeyInfoDer::from(key.as_ref()),
            dss,
            &self.algorithms,
        )
    }
}

impl ServerCertVerifier for Peer {
    fn verify_server_cert(
        &self,
        key: &CertificateDer<'_>,
        others: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> std::result::Result<ServerCertVerified, Error> {
        if self.pin.is_none() {
            return Err(CertificateError::ApplicationVerificationFailure.into());
        }
        self.identity(key, others)?;
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls13_signature(
        &self,
        msg: &[u8],
        key: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, Error> {
        self.signature(msg, key, dss)
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, Error> {
        Err(Error::General("TLS 1.2 disabled".into()))
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SCHEME]
    }
    fn requires_raw_public_keys(&self) -> bool {
        true
    }
}

impl ClientCertVerifier for Peer {
    fn client_auth_mandatory(&self) -> bool {
        true
    }
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }
    fn verify_client_cert(
        &self,
        key: &CertificateDer<'_>,
        others: &[CertificateDer<'_>],
        _: UnixTime,
    ) -> std::result::Result<ClientCertVerified, Error> {
        // Enrollment waives the *pin only*, never CertificateVerify or Finished.
        self.identity(key, others)?;
        Ok(ClientCertVerified::assertion())
    }
    fn verify_tls13_signature(
        &self,
        msg: &[u8],
        key: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, Error> {
        self.signature(msg, key, dss)
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> std::result::Result<HandshakeSignatureValid, Error> {
        Err(Error::General("TLS 1.2 disabled".into()))
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SCHEME]
    }
    fn requires_raw_public_keys(&self) -> bool {
        true
    }
}

pub struct Session {
    conn: Connection,
    signed: Arc<AtomicBool>,
    verified_pin: Option<Pin>,
    handshake_read: usize,
    pending: Zeroizing<Vec<u8>>,
}

impl Session {
    pub fn new(
        server: bool,
        spki: Vec<u8>,
        signer: Arc<dyn SigningKey>,
        signed: Arc<AtomicBool>,
        pin: Option<Pin>,
    ) -> Result<Self> {
        let provider = Arc::new(aws::default_provider());
        let peer = Arc::new(Peer {
            pin,
            algorithms: provider.signature_verification_algorithms,
        });
        let key = Arc::new(CertifiedKey::new(vec![CertificateDer::from(spki)], signer));
        let versions = &[&rustls::version::TLS13];
        let config_error = |_| Failure("TLS configuration failed");
        let mut conn = if server {
            let mut config = ServerConfig::builder_with_provider(provider)
                .with_protocol_versions(versions)
                .map_err(config_error)?
                .with_client_cert_verifier(peer)
                .with_cert_resolver(Arc::new(AlwaysResolvesServerRawPublicKeys::new(key)));
            config.session_storage = Arc::new(NoServerSessionStorage {});
            config.send_tls13_tickets = 0;
            config.max_early_data_size = 0;
            config.send_half_rtt_data = false;
            ServerConnection::new(Arc::new(config)).map(Connection::Server)
        } else {
            if pin.is_none() {
                return Err(Failure("client requires a server pin"));
            }
            let mut config = ClientConfig::builder_with_provider(provider)
                .with_protocol_versions(versions)
                .map_err(config_error)?
                .dangerous()
                .with_custom_certificate_verifier(peer)
                .with_client_cert_resolver(Arc::new(AlwaysResolvesClientRawPublicKeys::new(key)));
            config.resumption = Resumption::disabled();
            config.enable_early_data = false;
            config.enable_sni = false;
            // No DNS lookup/name trust. rustls requires a name argument even without SNI.
            let name = ServerName::from(std::net::Ipv4Addr::LOCALHOST);
            ClientConnection::new(Arc::new(config), name).map(Connection::Client)
        }
        .map_err(config_error)?;
        conn.set_buffer_limit(Some(MAX_WIRE));
        Ok(Self {
            conn,
            signed,
            verified_pin: None,
            handshake_read: 0,
            pending: Zeroizing::default(),
        })
    }

    pub fn receive(&mut self, mut wire: &[u8]) -> Result<()> {
        if wire.len() > MAX_WIRE {
            return Err(Failure("oversized TLS input"));
        }
        while !wire.is_empty() {
            let n = self
                .conn
                .read_tls(&mut wire)
                .map_err(|_| Failure("TLS input error"))?;
            if n == 0 {
                return Err(Failure("TLS input closed"));
            }
            if self.verified_pin.is_none() {
                self.handshake_read += n;
                if self.handshake_read > MAX_HANDSHAKE {
                    return Err(Failure("TLS handshake too large"));
                }
            }
            let state = self
                .conn
                .process_new_packets()
                .map_err(|_| Failure("TLS peer or record rejected"))?;
            if state.peer_has_closed() {
                return Err(Failure("TLS peer closed"));
            }
            if state.tls_bytes_to_write() > MAX_WIRE {
                return Err(Failure("TLS output limit"));
            }
            if self.verified_pin.is_none() && !self.conn.is_handshaking() {
                // peer_certificates() alone is NOT a completion signal. Both a
                // verified peer Finished and local client/server auth are required.
                if self.conn.protocol_version() != Some(ProtocolVersion::TLSv1_3)
                    || !matches!(
                        self.conn.handshake_kind(),
                        Some(HandshakeKind::Full | HandshakeKind::FullWithHelloRetryRequest)
                    )
                    || !self.signed.load(Ordering::Relaxed)
                {
                    return Err(Failure("expected full mutual TLS 1.3 handshake"));
                }
                let Some([key]) = self.conn.peer_certificates() else {
                    return Err(Failure("missing authenticated raw public key"));
                };
                self.verified_pin = Some(fingerprint(key.as_ref()));
            }
            let n = state.plaintext_bytes_to_read();
            if n != 0 {
                let start = self.pending.len();
                if self.verified_pin.is_none() || n > MAX_PLAIN - start {
                    return Err(Failure("unauthenticated or excess plaintext"));
                }
                self.pending.resize(start + n, 0);
                self.conn
                    .reader()
                    .read_exact(&mut self.pending[start..])
                    .map_err(|_| Failure("TLS plaintext read failed"))?;
            }
        }
        Ok(())
    }

    pub fn send(&mut self, plain: &[u8]) -> Result<()> {
        if self.verified_pin.is_none() {
            return Err(Failure("TLS handshake incomplete"));
        }
        if plain.len() > MAX_PLAIN {
            return Err(Failure("oversized plaintext"));
        }
        self.conn
            .writer()
            .write_all(plain)
            .map_err(|_| Failure("TLS output limit"))?;
        self.output_len()?;
        Ok(())
    }

    fn output_len(&mut self) -> Result<usize> {
        let n = self
            .conn
            .process_new_packets()
            .map_err(|_| Failure("TLS session failed"))?
            .tls_bytes_to_write();
        if n > MAX_WIRE {
            return Err(Failure("TLS output limit"));
        }
        Ok(n)
    }

    pub fn outbound(&mut self) -> Result<Vec<u8>> {
        let mut wire = vec![0; self.output_len()?];
        let mut rest = &mut wire[..];
        while !rest.is_empty() {
            if self
                .conn
                .write_tls(&mut rest)
                .map_err(|_| Failure("TLS output error"))?
                == 0
            {
                return Err(Failure("TLS output stalled"));
            }
        }
        Ok(wire)
    }

    pub fn take_plaintext(&mut self) -> Zeroizing<Vec<u8>> {
        std::mem::take(&mut self.pending)
    }
    pub fn peer_pin(&self) -> Option<&Pin> {
        self.verified_pin.as_ref()
    }
}
