use super::*;
use aws_lc_rs::signature::{ECDSA_P256_SHA256_ASN1_SIGNING, EcdsaKeyPair};
use rustls::pki_types::PrivatePkcs8KeyDer;

struct Identity {
    key: Arc<dyn SigningKey>,
    spki: Vec<u8>,
}

impl Identity {
    fn generate() -> Self {
        let der = EcdsaKeyPair::generate(&ECDSA_P256_SHA256_ASN1_SIGNING)
            .unwrap()
            .to_pkcs8v1()
            .unwrap();
        let key = aws::default_provider()
            .key_provider
            .load_private_key(PrivatePkcs8KeyDer::from(der.as_ref().to_vec()).into())
            .unwrap();
        Self {
            spki: key.public_key().unwrap().to_vec(),
            key,
        }
    }

    fn session(&self, server: bool, advertised: &Self, peer: Option<&Self>) -> Session {
        // A software signing key bypasses the JNI signer's *local* consistency
        // check. A peer must independently verify CertificateVerify, even when
        // the advertised key matches its pin or it is in enrollment mode.
        Session::new(
            server,
            advertised.spki.clone(),
            self.key.clone(),
            Arc::new(AtomicBool::new(true)),
            peer.map(|p| fingerprint(&p.spki)),
        )
        .unwrap()
    }
}

#[test]
fn both_roles_check_peer_proof_independently_of_pin() {
    let c = Identity::generate();
    let s = Identity::generate();
    let other = Identity::generate();
    for pin in [None, Some(&c)] {
        let mut server = s.session(true, &s, pin);
        let mut client = other.session(false, &c, Some(&s));
        server.receive(&client.outbound().unwrap()).unwrap();
        client.receive(&server.outbound().unwrap()).unwrap();
        assert!(server.receive(&client.outbound().unwrap()).is_err());
        assert!(server.peer_pin().is_none());
        assert_eq!(
            server.drain_plaintext(MAX_PLAIN, |p| Ok(p.len())).unwrap(),
            0
        );
    }
    let mut server = other.session(true, &s, Some(&c));
    let mut client = c.session(false, &c, Some(&s));
    server.receive(&client.outbound().unwrap()).unwrap();
    assert!(client.receive(&server.outbound().unwrap()).is_err());
    assert!(client.peer_pin().is_none());
}

#[test]
fn enrollment_cannot_negotiate_without_a_raw_client_key() {
    let server_key = Identity::generate();
    let mut server = server_key.session(true, &server_key, None);
    let provider = Arc::new(aws::default_provider());
    let peer = Arc::new(Peer {
        pin: Some(fingerprint(&server_key.spki)),
        algorithms: provider.signature_verification_algorithms,
    });
    let config = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(peer)
        .with_no_client_auth();
    let mut client =
        ClientConnection::new(Arc::new(config), std::net::Ipv4Addr::LOCALHOST.into()).unwrap();
    let mut hello = Vec::new();
    while client.wants_write() {
        client.write_tls(&mut hello).unwrap();
    }
    assert!(server.receive(&hello).is_err());
    assert!(server.peer_pin().is_none());
}

#[test]
fn peer_key_is_not_published_between_certificate_verify_and_finished() {
    let c = Identity::generate();
    let s = Identity::generate();
    let mut client = c.session(false, &c, Some(&s));
    let provider = Arc::new(aws::default_provider());
    let verifier = Peer {
        pin: Some(fingerprint(&c.spki)),
        algorithms: provider.signature_verification_algorithms,
    };
    let key = CertifiedKey::new(vec![CertificateDer::from(s.spki.clone())], s.key);
    let mut config = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_client_cert_verifier(Arc::new(verifier))
        .with_cert_resolver(Arc::new(AlwaysResolvesServerRawPublicKeys::new(Arc::new(
            key,
        ))));
    // Force separate records: withholding one byte of a *coalesced* flight
    // alone does not prove the intermediate verified-key state is hidden.
    config.max_fragment_size = Some(32);
    let mut server = ServerConnection::new(Arc::new(config)).unwrap();
    let hello = client.outbound().unwrap();
    let mut remaining = &hello[..];
    while !remaining.is_empty() {
        assert_ne!(server.read_tls(&mut remaining).unwrap(), 0);
        server.process_new_packets().unwrap();
    }
    let mut flight = Vec::new();
    while server.wants_write() {
        server.write_tls(&mut flight).unwrap();
    }
    let mut saw_verified_key_before_finished = false;
    for byte in flight {
        client.receive(&[byte]).unwrap();
        if client.conn.is_handshaking() && client.conn.peer_certificates().is_some() {
            saw_verified_key_before_finished = true;
            assert!(client.peer_pin().is_none());
        }
    }
    assert!(saw_verified_key_before_finished);
    assert_eq!(client.peer_pin(), Some(&fingerprint(&s.spki)));
}

#[test]
fn full_handshake_has_no_tickets_or_early_plaintext() {
    let c = Identity::generate();
    let s = Identity::generate();
    let mut server = s.session(true, &s, Some(&c));
    let mut client = c.session(false, &c, Some(&s));
    assert!(client.send(b"early").is_err());
    server.receive(&client.outbound().unwrap()).unwrap();
    assert!(server.send(b"half RTT").is_err());
    client.receive(&server.outbound().unwrap()).unwrap();
    server.receive(&client.outbound().unwrap()).unwrap();
    assert_eq!(client.peer_pin(), Some(&fingerprint(&s.spki)));
    assert_eq!(server.peer_pin(), Some(&fingerprint(&c.spki)));
    assert_eq!(server.conn.handshake_kind(), Some(HandshakeKind::Full));
    assert_eq!(
        server.conn.protocol_version(),
        Some(ProtocolVersion::TLSv1_3)
    );
    assert!(!server.conn.wants_write()); // no NewSessionTicket flight
    assert_eq!(
        server.drain_plaintext(MAX_PLAIN, |p| Ok(p.len())).unwrap(),
        0
    );
}

#[test]
fn partial_drains_reclaim_capacity_without_losing_unread_plaintext() {
    let c = Identity::generate();
    let s = Identity::generate();
    let mut client = c.session(false, &c, Some(&s));
    let mut server = s.session(true, &s, Some(&c));
    server.receive(&client.outbound().unwrap()).unwrap();
    client.receive(&server.outbound().unwrap()).unwrap();
    server.receive(&client.outbound().unwrap()).unwrap();

    let original: Vec<u8> = (0..MAX_PLAIN).map(|i| i as u8).collect();
    client.send(&original).unwrap();
    server.receive(&client.outbound().unwrap()).unwrap();
    let drain = |bytes: &[u8]| Ok(bytes.to_vec());
    let n = 8193;
    assert_eq!(server.drain_plaintext(n, drain).unwrap(), original[..n]);
    client.send(&original[..n]).unwrap();
    server.receive(&client.outbound().unwrap()).unwrap();
    assert_eq!(
        server.drain_plaintext(MAX_PLAIN - n, drain).unwrap(),
        original[n..]
    );
    assert_eq!(
        server.drain_plaintext(MAX_PLAIN, drain).unwrap(),
        original[..n]
    );
    assert!(server.drain_plaintext(MAX_PLAIN, drain).unwrap().is_empty());
}
