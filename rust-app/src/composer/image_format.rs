//! Bounded MIME inspection for recovered original images. Do not transcode old
//! Base64 just to fit a new AttachmentRef; retain the original stored leaf.
pub fn media_type(base64: &str) -> Option<&'static str> {
    let mut header = [0u8; 12];
    let mut bytes = 0;
    let mut bits = 0u32;
    let mut available = 0u32;
    for byte in base64.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b' ' | b'\t' | b'\r' | b'\n' => continue,
            b'=' => break,
            _ => return None,
        };
        bits = (bits << 6) | value as u32;
        available += 6;
        if available >= 8 {
            available -= 8;
            header[bytes] = ((bits >> available) & 255) as u8;
            bytes += 1;
            if bytes == header.len() {
                break;
            }
        }
    }
    let header = &header[..bytes];
    if header.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if header.starts_with(b"\x89PNG\r\n\x1a\n") {
        Some("image/png")
    } else if header.starts_with(b"GIF87a") || header.starts_with(b"GIF89a") {
        Some("image/gif")
    } else if header.len() == 12 && header.starts_with(b"RIFF") && header[8..] == *b"WEBP" {
        Some("image/webp")
    } else {
        None
    }
}
// Current picker/camera converts to JPEG, and old Kotlin picker/camera plus
// virtual-screen frames were JPEG too. Rust protocol currently hardcodes JPEG
// in all three serializers. If a recovered source contains a recognized other
// format, use this MIME in the wire media_type/data URI instead of modifying the
// original history. Unknown bytes are rejected at wire preparation, not silently
// rewritten or stripped from persisted data. No body-sized decode occurs here.
