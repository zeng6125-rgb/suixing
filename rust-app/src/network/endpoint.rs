//! Model endpoint policy shared by chat, summaries, children and model discovery.
//! Inspect the final URL and headers before a client can send credentials.
use super::{CONNECT_TIMEOUT, TURN_TIMEOUT};
use crate::state::Settings;
use reqwest::{Client, Url, header::HeaderMap};
use rustls::{
    DigitallySignedStruct, Error, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{WebPkiSupportedAlgorithms, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, ServerName, UnixTime},
};
use std::{net::IpAddr, sync::Arc};

pub(super) fn strict_client() -> Result<Client, String> {
    client(None)
}

pub(super) fn model_client(
    settings: &Settings,
    url: &Url,
    headers: &HeaderMap,
) -> Result<Client, String> {
    let local = private_host(url);
    if url.scheme() == "http"
        && !local
        && headers.iter().any(|(name, value)| {
            let name = name.as_str();
            let credential = matches!(name, "authorization" | "cookie")
                || name.contains("token")
                || name.contains("api-key")
                || name.contains("apikey");
            credential
                && value
                    .as_bytes()
                    .iter()
                    .any(|byte| !byte.is_ascii_whitespace())
        })
    {
        return Err("公网接口携带凭据时必须使用 HTTPS，请修改接口地址".into());
    }
    let exception = if settings.behavior.allow_insecure_tls && local && url.scheme() == "https" {
        let host = url.host_str().ok_or("本地 TLS 接口缺少主机")?;
        Some(
            ServerName::try_from(
                host.trim_start_matches('[')
                    .trim_end_matches(']')
                    .to_owned(),
            )
            .map_err(|_| "本地 TLS 接口主机无效")?,
        )
    } else {
        None
    };
    client(exception)
}

/// Match the original EndpointSecurity.kt policy without DNS. The runtime
/// permission classifier is intentionally broader and cannot grant TLS trust.
fn private_host(url: &Url) -> bool {
    let Some(host) = url.host_str() else {
        return false;
    };
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if host == "localhost" || host.len() > 6 && host.ends_with(".local") {
        return true;
    }
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => ip.is_private() || ip.is_loopback() || ip.is_link_local(),
        Ok(IpAddr::V6(ip)) => {
            ip.is_loopback()
                || ip.is_unicast_link_local()
                || ip.segments()[0] & 0xfe00 == 0xfc00
                || ip
                    .to_ipv4_mapped()
                    .is_some_and(|ip| ip.is_loopback() || ip.is_link_local())
        }
        _ => false,
    }
}

fn client(exception: Option<ServerName<'static>>) -> Result<Client, String> {
    // Agent runs own different Tokio runtimes. A request-scoped pool must not
    // survive into a later run, and a local exception must not become global.
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let roots = rustls::RootCertStore {
        roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
    };
    let mut tls = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|_| "无法初始化 TLS 协议".to_owned())?
        .with_root_certificates(roots)
        .with_no_client_auth();
    if let Some(server_name) = exception {
        tls.dangerous()
            .set_certificate_verifier(Arc::new(LocalVerifier {
                server_name,
                algorithms: provider.signature_verification_algorithms,
            }));
    }
    Client::builder()
        .tls_backend_preconfigured(tls)
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(TURN_TIMEOUT)
        .timeout(TURN_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|_| "无法初始化 HTTPS 客户端".into())
}

#[derive(Debug)]
struct LocalVerifier {
    server_name: ServerName<'static>,
    algorithms: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for LocalVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        if server_name != &self.server_name {
            return Err(Error::General("本地 TLS 例外不适用于其他接口".into()));
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}
