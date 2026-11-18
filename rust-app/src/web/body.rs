//! One bounded streaming HTTP content decoder. Only gzip is requested, matching
//! the original OkHttp bridge; unknown encodings fail rather than becoming text.
use std::io::{self, Write};
pub(super) enum Decoder<W: Write> {
    Identity(W),
    Gzip(flate2::write::MultiGzDecoder<W>),
}
pub(super) struct Stream<W: Write> {
    decoder: Decoder<W>,
    wire: u64,
    wire_limit: u64,
    header_pending: u64,
}
impl<W: Write> Stream<W> {
    pub(super) fn new(content_encoding: &str, sink: W, wire_limit: u64) -> Result<Self, String> {
        let decoder = match content_encoding.trim().to_ascii_lowercase().as_str() {
            "" | "identity" => Decoder::Identity(sink),
            "gzip" | "x-gzip" => Decoder::Gzip(flate2::write::MultiGzDecoder::new(sink)),
            _ => return Err("HTTP返回了未支持的Content-Encoding；未把压缩字节当作正文".into()),
        };
        Ok(Self {
            decoder,
            wire: 0,
            wire_limit,
            header_pending: 0,
        })
    }
    pub(super) fn sink(&mut self) -> &mut W {
        match &mut self.decoder {
            Decoder::Identity(sink) => sink,
            Decoder::Gzip(decoder) => decoder.get_mut(),
        }
    }
    pub(super) fn push(&mut self, bytes: &[u8]) -> io::Result<()> {
        if bytes.len() as u64 > self.wire_limit.saturating_sub(self.wire) {
            return Err(io::Error::other("HTTP encoded body exceeds limit"));
        }
        self.wire += bytes.len() as u64;
        match &mut self.decoder {
            Decoder::Identity(sink) => sink.write_all(bytes),
            Decoder::Gzip(decoder) => {
                for chunk in bytes.chunks(16 * 1024) {
                    if decoder.header().is_none() {
                        self.header_pending += chunk.len() as u64;
                        if self.header_pending > 64 * 1024 {
                            return Err(io::Error::other("gzip header exceeds64KiB"));
                        }
                    } else {
                        self.header_pending = 0;
                    }
                    decoder.write_all(chunk)?;
                }
                Ok(())
            }
        }
    }
    pub(super) fn finish(self) -> io::Result<(W, u64)> {
        let sink = match self.decoder {
            Decoder::Identity(sink) => sink,
            Decoder::Gzip(decoder) => decoder.finish()?,
        };
        Ok((sink, self.wire))
    }
}
pub(super) struct FetchSink {
    pub bytes: Vec<u8>,
    pub capped: bool,
    limit: usize,
}
impl FetchSink {
    pub(super) fn new(initial: usize, limit: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(initial.min(limit + 1)),
            capped: false,
            limit,
        }
    }
}
impl Write for FetchSink {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.capped {
            return Err(io::Error::other("fetch prefix cap reached"));
        }
        let allowed = (self.limit + 1 - self.bytes.len()).min(bytes.len());
        self.bytes.extend_from_slice(&bytes[..allowed]);
        if self.bytes.len() > self.limit {
            self.capped = true;
            return Err(io::Error::other("fetch prefix cap reached"));
        }
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
#[cfg(unix)]
pub(super) struct DownloadSink<'a> {
    pub active: super::download::unix::Active,
    pub cancel: &'a crate::network::CancelToken,
    pub written: u64,
}
#[cfg(unix)]
impl Write for DownloadSink<'_> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        for chunk in bytes.chunks(16 * 1024) {
            self.active
                .write(chunk, self.cancel)
                .map_err(io::Error::other)?;
            self.written = self
                .written
                .checked_add(chunk.len() as u64)
                .ok_or_else(|| io::Error::other("download length overflow"))?;
        }
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
