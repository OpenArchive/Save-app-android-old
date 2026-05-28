use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use c2pa::{Builder, CallbackSigner, SigningAlg};
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use log::LevelFilter;
use rcgen::{
    Certificate, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, KeyUsagePurpose,
    PKCS_ECDSA_P256_SHA256,
};
use ring::rand::SystemRandom;
use ring::signature::{EcdsaKeyPair, ECDSA_P256_SHA256_FIXED_SIGNING};
use std::io::Cursor;

// ── JNI: Init ────────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn Java_net_opendasharchive_openarchive_util_C2paFfi_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("C2PA-FFI"),
    );
    log::info!("C2PA FFI initialized");
    1
}

// ── JNI: Key + Certificate Generation ────────────────────────────────────────

/// Returns JSON: {"cert_pem":"...", "key_der_b64":"..."}
/// cert_pem  — PEM-encoded self-signed X.509 certificate (pass to c2pa as signing cert)
/// key_der_b64 — Base64-encoded PKCS#8 DER private key (store encrypted in C2paKeyStore)
#[no_mangle]
pub extern "C" fn Java_net_opendasharchive_openarchive_util_C2paFfi_nativeGenerateKeyAndCertificate(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match generate_key_and_cert() {
        Ok(json) => env
            .new_string(json)
            .map(|s| s.into_raw())
            .unwrap_or(JObject::null().into_raw()),
        Err(e) => {
            log::error!("Key/cert generation failed: {}", e);
            JObject::null().into_raw()
        }
    }
}

fn generate_key_and_cert() -> Result<String, Box<dyn std::error::Error>> {
    let mut params = CertificateParams::default();
    params.alg = &PKCS_ECDSA_P256_SHA256;
    params.not_before = rcgen::date_time_ymd(2024, 1, 1);
    params.not_after = rcgen::date_time_ymd(2034, 1, 1);

    // c2pa check_cert requires:
    //   - not a self-signed CA (IsCa must be ExplicitNoCa)
    //   - KeyUsage.digitalSignature
    //   - EKU with emailProtection (OID 1.3.6.1.5.5.7.3.4) or similar allowed OID
    //   - AuthorityKeyIdentifier extension present
    params.is_ca = IsCa::ExplicitNoCa;
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::EmailProtection];
    params.use_authority_key_identifier_extension = true;

    params
        .distinguished_name
        .push(DnType::CommonName, "OpenArchive Save");
    params
        .distinguished_name
        .push(DnType::OrganizationName, "OpenArchive");

    let cert = Certificate::from_params(params)?;
    let cert_pem = cert.serialize_pem()?;
    let key_der_b64 = BASE64.encode(cert.serialize_private_key_der());

    let json = serde_json::json!({
        "cert_pem": cert_pem,
        "key_der_b64": key_der_b64
    });

    Ok(json.to_string())
}

// ── JNI: Sidecar Generation ───────────────────────────────────────────────────

/// Signs the asset and writes a binary .c2pa sidecar (JUMBF format).
/// The original file is NOT modified.
///
/// Parameters:
///   filePath     — absolute path to the media asset
///   sidecarPath  — absolute path for the output .c2pa file
///   certPem      — PEM certificate string from C2paKeyStore
///   keyDerB64    — Base64 PKCS#8 DER private key from C2paKeyStore
///   metadataJson — JSON object with optional fields: title, description, author, location
///
/// Returns 1 on success, 0 on failure.
#[no_mangle]
pub extern "C" fn Java_net_opendasharchive_openarchive_util_C2paFfi_nativeGenerateSidecar(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    sidecar_path: JString,
    cert_pem: JString,
    key_der_b64: JString,
    metadata_json: JString,
) -> jboolean {
    macro_rules! jstr {
        ($s:expr) => {
            match env.get_string(&$s) {
                Ok(s) => String::from(s),
                Err(e) => {
                    log::error!("JNI string error: {}", e);
                    return 0;
                }
            }
        };
    }

    let file_path = jstr!(file_path);
    let sidecar_path = jstr!(sidecar_path);
    let cert_pem = jstr!(cert_pem);
    let key_der_b64 = jstr!(key_der_b64);
    let metadata_json = jstr!(metadata_json);

    match generate_sidecar(
        &file_path,
        &sidecar_path,
        &cert_pem,
        &key_der_b64,
        &metadata_json,
    ) {
        Ok(()) => 1,
        Err(e) => {
            log::error!("Sidecar generation failed for {}: {}", file_path, e);
            0
        }
    }
}

const TSA_URL: &str = "https://timestamp.digicert.com";

fn generate_sidecar(
    file_path: &str,
    sidecar_path: &str,
    cert_pem: &str,
    key_der_b64: &str,
    metadata_json: &str,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let key_der = BASE64
        .decode(key_der_b64)
        .map_err(|e| format!("Base64 decode: {}", e))?;

    let mime_type = mime_from_path(file_path);

    let metadata: serde_json::Value =
        serde_json::from_str(metadata_json).unwrap_or(serde_json::Value::Object(Default::default()));

    let title = metadata
        .get("title")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
        .unwrap_or_else(|| {
            std::path::Path::new(file_path)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("media")
                .to_string()
        });

    let manifest_json = serde_json::json!({
        "claim_generator_info": [{
            "name": "OpenArchive Save",
            "version": env!("CARGO_PKG_VERSION")
        }],
        "title": title,
        "format": mime_type,
        "assertions": [
            {
                "label": "c2pa.actions",
                "data": { "actions": [{ "action": "c2pa.created" }] }
            },
            {
                "label": "stds.schema-org.CreativeWork",
                "data": metadata
            }
        ]
    });

    let manifest_str = manifest_json.to_string();
    let asset_bytes = std::fs::read(file_path).map_err(|e| format!("Read asset failed: {}", e))?;

    // Try with RFC 3161 trusted timestamp first; fall back if device is offline.
    match do_sign(cert_pem, &key_der, &mime_type, &manifest_str, &asset_bytes, sidecar_path, Some(TSA_URL)) {
        Ok(()) => return Ok(()),
        Err(e) => log::warn!("TSA signing failed ({}), retrying without trusted timestamp", e),
    }

    do_sign(cert_pem, &key_der, &mime_type, &manifest_str, &asset_bytes, sidecar_path, None)
}

/// Signs asset bytes and writes binary JUMBF sidecar.
/// tsa_url: Some → embeds RFC 3161 trusted timestamp; None → no timestamp (offline fallback).
fn do_sign(
    cert_pem: &str,
    key_der: &[u8],
    mime_type: &str,
    manifest_str: &str,
    asset_bytes: &[u8],
    sidecar_path: &str,
    tsa_url: Option<&str>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let cert_bytes = cert_pem.as_bytes().to_vec();
    let key_der_owned = key_der.to_vec();

    let signer = {
        let s = CallbackSigner::new(
            move |_ctx: *const (), data: &[u8]| {
                sign_es256_p1363(data, &key_der_owned).map_err(|e| c2pa::Error::OtherError(e))
            },
            SigningAlg::Es256,
            cert_bytes,
        );
        match tsa_url {
            Some(url) => s.set_tsa_url(url),
            None => s,
        }
    };

    let mut builder = Builder::from_json(manifest_str)?;
    builder.set_no_embed(true);

    let mut source = Cursor::new(asset_bytes);
    let mut sink = Cursor::new(Vec::<u8>::new());

    let manifest_bytes = builder.sign(&signer, mime_type, &mut source, &mut sink)?;

    if let Some(parent) = std::path::Path::new(sidecar_path).parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("Create sidecar dir: {}", e))?;
    }
    std::fs::write(sidecar_path, &manifest_bytes).map_err(|e| format!("Write sidecar: {}", e))?;

    log::info!(
        "C2PA sidecar written: {} ({} bytes, TSA: {})",
        sidecar_path,
        manifest_bytes.len(),
        tsa_url.is_some()
    );
    Ok(())
}

// ── Signing ───────────────────────────────────────────────────────────────────

/// ECDSA P-256 + SHA-256 sign, returns P1363 (fixed 64-byte r||s).
/// This matches c2pa's expected ES256 signature format.
fn sign_es256_p1363(
    data: &[u8],
    key_der: &[u8],
) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    let rng = SystemRandom::new();
    let key_pair = EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, key_der, &rng)
        .map_err(|e| format!("ECDSA key parse failed: {:?}", e))?;
    let sig = key_pair
        .sign(&rng, data)
        .map_err(|e| format!("ECDSA sign failed: {:?}", e))?;
    Ok(sig.as_ref().to_vec())
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn mime_from_path(path: &str) -> String {
    let ext = std::path::Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "heic" | "heif" => "image/heic",
        "mp4" | "m4v" => "video/mp4",
        "mov" => "video/quicktime",
        "avi" => "video/avi",
        "mp3" => "audio/mp3",
        "wav" => "audio/wav",
        "m4a" => "audio/m4a",
        "pdf" => "application/pdf",
        _ => "image/jpeg", // safe fallback for camera captures
    }
    .to_string()
}
