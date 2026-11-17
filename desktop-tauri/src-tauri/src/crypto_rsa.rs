use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use rand::rngs::OsRng;
use rsa::pkcs1::{DecodeRsaPrivateKey, EncodeRsaPrivateKey};
use rsa::pkcs8::{EncodePublicKey, LineEnding};
use rsa::{Pkcs1v15Encrypt, RsaPrivateKey, RsaPublicKey};

/// Generates a 2048-bit RSA keypair across macOS, Windows, and Linux in pure Rust:
/// - `public_pem`: SPKI `-----BEGIN PUBLIC KEY-----` PEM accepted by Discourse `/user-api-key/new`
/// - `private_base64`: PKCS#1 DER base64 (100% wire-compatible with macOS `SecKeyCopyExternalRepresentation`)
pub fn generate_rsa_keypair() -> Result<(String, String), String> {
    let mut rng = OsRng;
    let priv_key = RsaPrivateKey::new(&mut rng, 2048)
        .map_err(|e| format!("RSA 密钥生成失败: {e}"))?;
    let pub_key = RsaPublicKey::from(&priv_key);

    let public_pem = pub_key
        .to_public_key_pem(LineEnding::LF)
        .map_err(|e| format!("RSA 公钥导出失败: {e}"))?;
    let priv_der = priv_key
        .to_pkcs1_der()
        .map_err(|e| format!("RSA 私钥导出失败: {e}"))?;
    let private_base64 = B64.encode(priv_der.as_bytes());

    Ok((public_pem, private_base64))
}

/// Decrypts a Discourse `payload` / `one_time_password` PKCS#1 v1.5 ciphertext using the PKCS#1 DER base64 private key.
pub fn decrypt_rsa_pkcs1v15(private_b64: &str, cipher_b64: &str) -> Result<String, String> {
    let clean_priv: String = private_b64.chars().filter(|c| !c.is_whitespace()).collect();
    let clean_cipher: String = cipher_b64.chars().filter(|c| !c.is_whitespace()).collect();

    let priv_bytes = B64
        .decode(clean_priv.as_bytes())
        .map_err(|_| "无效的 RSA 私钥编码".to_string())?;
    let cipher_bytes = B64
        .decode(clean_cipher.as_bytes())
        .map_err(|_| "无效的 RSA 密文编码".to_string())?;

    let priv_key = RsaPrivateKey::from_pkcs1_der(&priv_bytes)
        .map_err(|_| "RSA 私钥解析失败".to_string())?;
    let plain_bytes = priv_key
        .decrypt(Pkcs1v15Encrypt, &cipher_bytes)
        .map_err(|_| "RSA 解密未成功".to_string())?;

    String::from_utf8(plain_bytes).map_err(|_| "解密内容不是合法的 UTF-8 文本".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rsa::pkcs8::DecodePublicKey;

    #[test]
    fn rsa_keygen_spki_pem_and_pkcs1v15_decrypt_roundtrip() {
        let (pub_pem, priv_b64) = generate_rsa_keypair().expect("keygen should succeed");
        assert!(pub_pem.starts_with("-----BEGIN PUBLIC KEY-----\n"));

        let pub_key = RsaPublicKey::from_public_key_pem(&pub_pem).expect("valid SPKI PEM");
        let sample = r#"{"key":"abc","nonce":"123456","one_time_password":"otp_token_xyz"}"#;
        let mut rng = OsRng;
        let encrypted = pub_key
            .encrypt(&mut rng, Pkcs1v15Encrypt, sample.as_bytes())
            .expect("encrypt should succeed");
        let cipher_b64 = B64.encode(&encrypted);

        let decrypted = decrypt_rsa_pkcs1v15(&priv_b64, &cipher_b64).expect("decrypt should succeed");
        assert_eq!(decrypted, sample);
    }
}
