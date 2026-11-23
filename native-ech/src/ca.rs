use std::collections::HashMap;
use std::sync::Arc;

use base64::Engine as _;
use boring2::asn1::Asn1Time;
use boring2::bn::{BigNum, MsbOption};
use boring2::ec::{EcGroup, EcKey};
use boring2::hash::MessageDigest;
use boring2::nid::Nid;
use boring2::pkey::{PKey, Private};
use boring2::stack::Stack;
use boring2::x509::extension::{
    AuthorityKeyIdentifier, BasicConstraints, ExtendedKeyUsage, KeyUsage, SubjectAlternativeName,
    SubjectKeyIdentifier,
};
use boring2::x509::store::X509StoreBuilder;
use boring2::x509::verify::X509CheckFlags;
use boring2::x509::{X509NameBuilder, X509StoreContext, X509};
use parking_lot::RwLock;

#[derive(Clone)]
pub struct LeafMaterial {
    pub cert: X509,
    pub key: PKey<Private>,
}

/// Per-install ECDSA P-256 local authority for minting loopback SAN leaf certs
/// and cryptographically verifying presented leaves in WebView / OkHttp callbacks.
pub struct CaAuthority {
    ca_cert: X509,
    ca_key: PKey<Private>,
    leaves: RwLock<HashMap<String, LeafMaterial>>,
}

fn generate_ec_p256_key() -> Result<PKey<Private>, String> {
    let group = EcGroup::from_curve_name(Nid::X9_62_PRIME256V1)
        .map_err(|e| format!("ec group error: {e}"))?;
    let ec_key = EcKey::generate(&group).map_err(|e| format!("ec keygen error: {e}"))?;
    PKey::from_ec_key(ec_key).map_err(|e| format!("pkey error: {e}"))
}

fn random_serial() -> Result<boring2::asn1::Asn1Integer, String> {
    let mut serial = BigNum::new().map_err(|e| format!("bignum error: {e}"))?;
    serial
        .rand(128, MsbOption::MAYBE_ZERO, false)
        .map_err(|e| format!("serial rand error: {e}"))?;
    serial
        .to_asn1_integer()
        .map_err(|e| format!("asn1 serial error: {e}"))
}

/// Generates a fresh self-signed ECDSA P-256 Root CA keypair in PEM format.
pub fn generate_root_ca_pem() -> Result<(String, String), String> {
    let key = generate_ec_p256_key()?;

    let mut name_builder = X509NameBuilder::new().map_err(|e| format!("name error: {e}"))?;
    name_builder
        .append_entry_by_text("O", "Lindo")
        .map_err(|e| format!("name O error: {e}"))?;
    name_builder
        .append_entry_by_text("CN", "Lindo Local ECH Authority")
        .map_err(|e| format!("name CN error: {e}"))?;
    let name = name_builder.build();

    let mut builder = X509::builder().map_err(|e| format!("x509 builder: {e}"))?;
    builder.set_version(2).map_err(|e| format!("version: {e}"))?;
    let serial = random_serial()?;
    builder
        .set_serial_number(&serial)
        .map_err(|e| format!("serial: {e}"))?;
    builder
        .set_subject_name(&name)
        .map_err(|e| format!("subject: {e}"))?;
    builder
        .set_issuer_name(&name)
        .map_err(|e| format!("issuer: {e}"))?;
    builder
        .set_pubkey(&key)
        .map_err(|e| format!("pubkey: {e}"))?;

    let now_secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let not_before = if now_secs > 86_400 {
        Asn1Time::from_unix((now_secs - 86_400) as _)
    } else {
        Asn1Time::days_from_now(0)
    }
    .map_err(|e| format!("not_before: {e}"))?;
    let not_after = Asn1Time::days_from_now(3650).map_err(|e| format!("not_after: {e}"))?;
    builder
        .set_not_before(&not_before)
        .map_err(|e| format!("set_not_before: {e}"))?;
    builder
        .set_not_after(&not_after)
        .map_err(|e| format!("set_not_after: {e}"))?;

    let bc = BasicConstraints::new()
        .critical()
        .ca()
        .pathlen(0)
        .build()
        .map_err(|e| format!("basic constraints: {e}"))?;
    builder
        .append_extension(&bc)
        .map_err(|e| format!("append bc: {e}"))?;

    let ku = KeyUsage::new()
        .critical()
        .key_cert_sign()
        .crl_sign()
        .build()
        .map_err(|e| format!("key usage: {e}"))?;
    builder
        .append_extension(&ku)
        .map_err(|e| format!("append ku: {e}"))?;

    let ski = SubjectKeyIdentifier::new()
        .build(&builder.x509v3_context(None, None))
        .map_err(|e| format!("ski: {e}"))?;
    builder
        .append_extension(&ski)
        .map_err(|e| format!("append ski: {e}"))?;

    builder
        .sign(&key, MessageDigest::sha256())
        .map_err(|e| format!("sign ca: {e}"))?;
    let cert = builder.build();

    let cert_pem = String::from_utf8(cert.to_pem().map_err(|e| format!("cert pem: {e}"))?)
        .map_err(|e| format!("cert utf8: {e}"))?;
    let key_pem = String::from_utf8(
        key.private_key_to_pem_pkcs8()
            .map_err(|e| format!("key pem: {e}"))?,
    )
    .map_err(|e| format!("key utf8: {e}"))?;

    Ok((cert_pem, key_pem))
}

impl CaAuthority {
    pub fn from_pem(cert_pem: &str, key_pem: &str) -> Result<Arc<Self>, String> {
        let ca_cert = X509::from_pem(cert_pem.as_bytes())
            .map_err(|e| format!("parse ca cert pem: {e}"))?;
        let ca_key = PKey::private_key_from_pem(key_pem.as_bytes())
            .map_err(|e| format!("parse ca key pem: {e}"))?;
        Ok(Arc::new(Self {
            ca_cert,
            ca_key,
            leaves: RwLock::new(HashMap::new()),
        }))
    }

    pub fn root_cert(&self) -> &X509 {
        &self.ca_cert
    }

    pub fn spki_sha256_base64(&self) -> Result<String, String> {
        let pkey = self
            .ca_cert
            .public_key()
            .map_err(|e| format!("ca pubkey: {e}"))?;
        let spki_der = pkey
            .public_key_to_der()
            .map_err(|e| format!("ca spki der: {e}"))?;
        let digest = boring2::hash::hash(MessageDigest::sha256(), &spki_der)
            .map_err(|e| format!("ca spki hash: {e}"))?;
        Ok(base64::engine::general_purpose::STANDARD.encode(digest.as_ref()))
    }

    pub fn leaf_for_host(&self, host: &str) -> Result<LeafMaterial, String> {
        let clean = normalize_host(host);
        if clean.is_empty() {
            return Err("empty host for leaf minting".to_string());
        }
        if let Some(existing) = self.leaves.read().get(&clean) {
            return Ok(existing.clone());
        }

        let minted = self.mint_leaf(&clean)?;
        let mut guard = self.leaves.write();
        let entry = guard.entry(clean).or_insert(minted);
        Ok(entry.clone())
    }

    fn mint_leaf(&self, host: &str) -> Result<LeafMaterial, String> {
        let key = generate_ec_p256_key()?;

        let mut subject_builder =
            X509NameBuilder::new().map_err(|e| format!("leaf name error: {e}"))?;
        subject_builder
            .append_entry_by_text("O", "Lindo")
            .map_err(|e| format!("leaf O error: {e}"))?;
        subject_builder
            .append_entry_by_text("CN", host)
            .map_err(|e| format!("leaf CN error: {e}"))?;
        let subject = subject_builder.build();

        let mut builder = X509::builder().map_err(|e| format!("leaf builder: {e}"))?;
        builder.set_version(2).map_err(|e| format!("leaf ver: {e}"))?;
        let serial = random_serial()?;
        builder
            .set_serial_number(&serial)
            .map_err(|e| format!("leaf serial: {e}"))?;
        builder
            .set_subject_name(&subject)
            .map_err(|e| format!("leaf subj: {e}"))?;
        builder
            .set_issuer_name(self.ca_cert.subject_name())
            .map_err(|e| format!("leaf issuer: {e}"))?;
        builder
            .set_pubkey(&key)
            .map_err(|e| format!("leaf pubkey: {e}"))?;

        let now_secs = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        let not_before = if now_secs > 86_400 {
            Asn1Time::from_unix((now_secs - 86_400) as _)
        } else {
            Asn1Time::days_from_now(0)
        }
        .map_err(|e| format!("leaf nb: {e}"))?;
        let not_after = Asn1Time::days_from_now(365).map_err(|e| format!("leaf na: {e}"))?;
        builder
            .set_not_before(&not_before)
            .map_err(|e| format!("leaf set_nb: {e}"))?;
        builder
            .set_not_after(&not_after)
            .map_err(|e| format!("leaf set_na: {e}"))?;

        let bc = BasicConstraints::new()
            .build()
            .map_err(|e| format!("leaf bc: {e}"))?;
        builder
            .append_extension(&bc)
            .map_err(|e| format!("leaf append bc: {e}"))?;

        let ku = KeyUsage::new()
            .critical()
            .digital_signature()
            .build()
            .map_err(|e| format!("leaf ku: {e}"))?;
        builder
            .append_extension(&ku)
            .map_err(|e| format!("leaf append ku: {e}"))?;

        let eku = ExtendedKeyUsage::new()
            .server_auth()
            .build()
            .map_err(|e| format!("leaf eku: {e}"))?;
        builder
            .append_extension(&eku)
            .map_err(|e| format!("leaf append eku: {e}"))?;

        let san = SubjectAlternativeName::new()
            .dns(host)
            .build(&builder.x509v3_context(Some(&self.ca_cert), None))
            .map_err(|e| format!("leaf san: {e}"))?;
        builder
            .append_extension(&san)
            .map_err(|e| format!("leaf append san: {e}"))?;

        let ski = SubjectKeyIdentifier::new()
            .build(&builder.x509v3_context(Some(&self.ca_cert), None))
            .map_err(|e| format!("leaf ski: {e}"))?;
        builder
            .append_extension(&ski)
            .map_err(|e| format!("leaf append ski: {e}"))?;

        let aki = AuthorityKeyIdentifier::new()
            .keyid(false)
            .issuer(false)
            .build(&builder.x509v3_context(Some(&self.ca_cert), None))
            .map_err(|e| format!("leaf aki: {e}"))?;
        builder
            .append_extension(&aki)
            .map_err(|e| format!("leaf append aki: {e}"))?;

        builder
            .sign(&self.ca_key, MessageDigest::sha256())
            .map_err(|e| format!("leaf sign: {e}"))?;

        Ok(LeafMaterial {
            cert: builder.build(),
            key,
        })
    }

    /// Cryptographically verifies a DER-encoded X.509 leaf certificate against
    /// ONLY this instance's Root CA, checking signature, validity window, and exact SAN host.
    pub fn verify_leaf_der(&self, leaf_der: &[u8], expected_host: &str) -> bool {
        let host = normalize_host(expected_host);
        if host.is_empty() || leaf_der.is_empty() {
            return false;
        }
        let Ok(leaf) = X509::from_der(leaf_der) else {
            return false;
        };
        let Ok(mut store_builder) = X509StoreBuilder::new() else {
            return false;
        };
        if store_builder.add_cert(self.ca_cert.clone()).is_err() {
            return false;
        }
        let store = store_builder.build();
        let Ok(chain) = Stack::new() else {
            return false;
        };
        let Ok(mut ctx) = X509StoreContext::new() else {
            return false;
        };
        ctx.init(&store, &leaf, &chain, |verify_ctx| {
            let param = verify_ctx.verify_param_mut();
            param.set_hostflags(X509CheckFlags::NO_PARTIAL_WILDCARDS);
            if param.set_host(&host).is_err() {
                return Ok(false);
            }
            let verified = verify_ctx.verify_cert().unwrap_or(false);
            let res_ok = verify_ctx.verify_result().is_ok();
            Ok(verified && res_ok)
        })
        .unwrap_or(false)
    }
}

pub fn normalize_host(host: &str) -> String {
    host.trim()
        .trim_end_matches('.')
        .to_ascii_lowercase()
}
