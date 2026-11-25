//! Verified private inbox reads. The actor admits only the bytes hashed here.
use crate::composer::{AttachmentRef, MAX_IMAGE_WIRE_BYTES};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

pub struct PreparedInput {
    pub text: String,
    pub metadata: crate::chat::MessageMetadata,
}

pub fn prepare(
    workspace: &Path,
    text: &str,
    attachments: &[AttachmentRef],
    cancelled: impl Fn() -> bool,
) -> Result<PreparedInput, String> {
    crate::composer::input_json_bytes(text, attachments)?;
    if attachments.len() > crate::composer::MAX_ATTACHMENTS {
        return Err("附件数量超过预算".into());
    }
    let mut result = PreparedInput {
        text: text.to_owned(),
        metadata: Default::default(),
    };
    let mut images = 0usize;
    let mut total = 0u64;
    let mut ids = std::collections::HashSet::new();
    for reference in attachments {
        reference.validate()?;
        if !ids.insert(&reference.id) {
            return Err("附件身份重复".into());
        }
        total = total.checked_add(reference.bytes).ok_or("附件长度溢出")?;
        if total > crate::composer::MAX_ATTACHMENT_BYTES {
            return Err("附件总量超过256 MiB".into());
        }
        let workspace = workspace_with_reference(workspace, reference);
        let encoded = read_verified(&workspace, reference, &cancelled)?;
        if let Some(encoded) = encoded {
            images = images.checked_add(encoded.len()).ok_or("图片长度溢出")?;
            if images > MAX_IMAGE_WIRE_BYTES {
                return Err("图片编码总量超过16 MiB".into());
            }
            result.metadata.images_base64.push(encoded);
        } else {
            let path = workspace.join(&reference.relative_path);
            let path = path.to_str().ok_or("附件路径不是UTF8")?;
            result.text = crate::composer::references::append(&result.text, path);
        }
        if result.text.len() > crate::composer::MAX_TEXT_BYTES {
            return Err("附件路径说明和正文超过1 MiB".into());
        }
    }
    crate::composer::metadata::insert(&mut result.metadata.extra, attachments)?;
    crate::composer::json_bytes(
        &(&result.text, &result.metadata),
        crate::composer::MAX_INPUT_JSON_BYTES,
    )?;
    Ok(result)
}

pub fn preview(
    workspace: &Path,
    reference: &AttachmentRef,
) -> Result<std::sync::Arc<crate::platform::attachments::PixelFrame>, String> {
    if !reference.image {
        return Err("该附件没有图片预览".into());
    }
    reference.validate()?;
    let workspace = workspace_with_reference(workspace, reference);
    let encoded = read_verified(&workspace, reference, || false)?.ok_or("附件不是图片")?;
    crate::platform::attachments::decode_base64_preview(&encoded, 320).map(std::sync::Arc::new)
}

fn workspace_with_reference(workspace: &Path, reference: &AttachmentRef) -> PathBuf {
    if workspace.join(&reference.relative_path).exists() {
        workspace.to_owned()
    } else {
        crate::store::workspace::private_workspace_for(workspace)
    }
}

fn read_verified(
    workspace: &Path,
    reference: &AttachmentRef,
    cancelled: impl Fn() -> bool,
) -> Result<Option<String>, String> {
    reference.validate()?;
    let mut file = open_reference(workspace, &reference.relative_path)?;
    let before = file.metadata().map_err(|_| "无法核对附件身份")?;
    if !before.is_file() || before.len() != reference.bytes {
        return Err("附件长度或文件类型已变化".into());
    }
    let mut image = if reference.image {
        if reference.bytes > (MAX_IMAGE_WIRE_BYTES / 4 * 3) as u64 {
            return Err("图片编码超过预算".into());
        }
        Some(Vec::with_capacity(reference.bytes as usize))
    } else {
        None
    };
    let mut hash = Sha256::new();
    let mut bytes = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        if cancelled() {
            return Err("附件核验已取消".into());
        }
        let count = file.read(&mut buffer).map_err(|_| "附件读取失败")?;
        if count == 0 {
            break;
        }
        bytes = bytes.checked_add(count as u64).ok_or("附件长度溢出")?;
        if bytes > reference.bytes {
            return Err("附件读取期间长度变化".into());
        }
        hash.update(&buffer[..count]);
        if let Some(image) = &mut image {
            image.extend_from_slice(&buffer[..count]);
        }
    }
    let after = file.metadata().map_err(|_| "无法重新核对附件身份")?;
    if bytes != reference.bytes
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
    {
        return Err("附件读取期间已变化".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return Err("附件身份在读取期间已变化".into());
        }
    }
    if format!("{:x}", hash.finalize()) != reference.sha256.to_ascii_lowercase() {
        return Err("附件哈希已变化，原草稿保留".into());
    }
    match image {
        None => Ok(None),
        Some(image) => {
            if !image.starts_with(&[0xff, 0xd8, 0xff]) {
                return Err("当前图片附件不是已核验的JPEG".into());
            }
            let encoded = encode_base64(&image);
            if encoded.len() != reference.image_wire_bytes {
                return Err("图片编码长度与原引用不一致".into());
            }
            Ok(Some(encoded))
        }
    }
}

#[cfg(unix)]
fn open_reference(workspace: &Path, relative: &Path) -> Result<File, String> {
    use std::{
        ffi::CString,
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::ffi::OsStrExt,
        },
        path::Component,
    };
    let root = CString::new(workspace.as_os_str().as_bytes()).map_err(|_| "附件工作区路径无效")?;
    let raw = unsafe {
        libc::open(
            root.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if raw < 0 {
        return Err("无法打开附件工作区，未跟随链接".into());
    }
    let mut directory = unsafe { File::from_raw_fd(raw) };
    let mut parts = relative.components().peekable();
    while let Some(component) = parts.next() {
        let Component::Normal(name) = component else {
            return Err("附件相对路径无效".into());
        };
        let name = CString::new(name.as_bytes()).map_err(|_| "附件路径无效")?;
        let flags = libc::O_RDONLY
            | libc::O_CLOEXEC
            | libc::O_NOFOLLOW
            | if parts.peek().is_some() {
                libc::O_DIRECTORY
            } else {
                libc::O_NONBLOCK
            };
        let raw = unsafe { libc::openat(directory.as_raw_fd(), name.as_ptr(), flags) };
        if raw < 0 {
            return Err("附件不存在、已变化或包含链接".into());
        }
        let opened = unsafe { File::from_raw_fd(raw) };
        if parts.peek().is_none() {
            return Ok(opened);
        }
        directory = opened;
    }
    Err("附件路径为空".into())
}
#[cfg(not(unix))]
fn open_reference(_workspace: &Path, _relative: &Path) -> Result<File, String> {
    Err("当前平台尚无拒链接的私有附件读取后端".into())
}

pub(super) fn encode_base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut result = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let value = ((chunk[0] as u32) << 16)
            | ((chunk.get(1).copied().unwrap_or(0) as u32) << 8)
            | chunk.get(2).copied().unwrap_or(0) as u32;
        result.push(ALPHABET[((value >> 18) & 63) as usize] as char);
        result.push(ALPHABET[((value >> 12) & 63) as usize] as char);
        result.push(if chunk.len() > 1 {
            ALPHABET[((value >> 6) & 63) as usize] as char
        } else {
            '='
        });
        result.push(if chunk.len() > 2 {
            ALPHABET[(value & 63) as usize] as char
        } else {
            '='
        });
    }
    result
}
