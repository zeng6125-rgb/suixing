//! Bounded tar extraction. All ordinary files and hard links are materialized
//! before symbolic links; guest absolute links are confined to the rootfs.
use std::{
    collections::{HashMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
};
const MAX_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const MAX_FILES: usize = 150_000;
struct Link {
    path: PathBuf,
    target: String,
    hard: bool,
}
pub(super) fn extract(
    root: &Path,
    prefix: &str,
    input: &mut impl Read,
    cancelled: &AtomicBool,
    mut progress: impl FnMut(u64),
) -> Result<(), String> {
    if !root.is_absolute()
        || fs::symlink_metadata(root)
            .ok()
            .is_some_and(|m| m.file_type().is_symlink())
    {
        return Err("rootfs目标目录无效".into());
    }
    fs::create_dir(root).map_err(|_| "rootfs临时根目录创建失败或已经存在")?;
    let root = fs::canonicalize(root).map_err(|_| "rootfs目标目录核对失败")?;
    let mut paths = HashSet::new();
    let mut tree_nodes = HashSet::new();
    let mut links = Vec::new();
    let mut long_name = None;
    let mut long_link = None;
    let mut pax = HashMap::new();
    let mut total = 0u64;
    let mut files = 0usize;
    loop {
        check(cancelled)?;
        let mut header = [0u8; 512];
        read_all(input, &mut header, cancelled)?;
        if header.iter().all(|byte| *byte == 0) {
            break;
        }
        let expected = octal(&header[148..156])?;
        let actual: u64 = header
            .iter()
            .enumerate()
            .map(|(index, byte)| {
                if (148..156).contains(&index) {
                    32
                } else {
                    u64::from(*byte)
                }
            })
            .sum();
        if expected != actual {
            return Err("rootfs tar校验失败".into());
        }
        let size = octal(&header[124..136])?;
        total = total
            .checked_add(size)
            .filter(|total| *total <= MAX_BYTES)
            .ok_or("rootfs解压大小超过2 GiB预算")?;
        files += 1;
        if files > MAX_FILES {
            return Err("rootfs条目超过150000个预算".into());
        }
        let kind = header[156];
        if matches!(kind, b'x' | b'g' | b'L' | b'K') {
            if size > 64 * 1024 {
                return Err("rootfs扩展头超过64 KiB".into());
            }
            let mut bytes = vec![0; size as usize];
            read_all(input, &mut bytes, cancelled)?;
            match kind {
                b'L' => long_name = Some(text(&bytes)?.trim_end_matches('\0').to_owned()),
                b'K' => long_link = Some(text(&bytes)?.trim_end_matches('\0').to_owned()),
                b'x' => pax = parse_pax(&bytes)?,
                b'g' => {
                    let global = parse_pax(&bytes)?;
                    if global
                        .keys()
                        .any(|key| matches!(key.as_str(), "path" | "linkpath" | "size"))
                    {
                        return Err("不支持全局tar路径覆盖".into());
                    }
                }
                _ => unreachable!(),
            }
        } else {
            if pax.contains_key("size") {
                return Err("不支持tar大小覆盖".into());
            }
            let header_name = field(&header[0..100])?;
            let header_prefix = if field(&header[257..263])? == "ustar" {
                field(&header[345..500])?
            } else {
                String::new()
            };
            let name = pax.remove("path").or(long_name.take()).unwrap_or_else(|| {
                if header_prefix.is_empty() {
                    header_name.clone()
                } else {
                    format!("{header_prefix}/{header_name}")
                }
            });
            let target = pax
                .remove("linkpath")
                .or(long_link.take())
                .unwrap_or(field(&header[157..257])?);
            pax.clear();
            let path = entry_path(&root, prefix, &name)?;
            let mut node = path.as_path();
            while node != root {
                tree_nodes.insert(node.to_path_buf());
                if tree_nodes.len() + 1 > MAX_FILES {
                    return Err("rootfs实际路径与隐式父目录超过150000个预算".into());
                }
                node = node.parent().ok_or("rootfs父路径越界")?;
            }
            match kind {
                0 | b'0' | b'7' => {
                    if path == root || !paths.insert(path.clone()) {
                        return Err("rootfs普通文件路径重复".into());
                    }
                    parents(&root, path.parent().ok_or("rootfs文件父目录缺失")?)?;
                    let mut options = OpenOptions::new();
                    options.write(true).create_new(true);
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::OpenOptionsExt;
                        options
                            .mode(0o600)
                            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
                    }
                    let mut output = options.open(&path).map_err(|_| "rootfs普通文件创建失败")?;
                    copy(input, &mut output, size, cancelled)?;
                    output.sync_all().map_err(|_| "rootfs文件同步失败")?;
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt;
                        let executable = octal(&header[100..108])? & 0o111 != 0;
                        fs::set_permissions(
                            &path,
                            fs::Permissions::from_mode(if executable { 0o700 } else { 0o600 }),
                        )
                        .map_err(|_| "rootfs文件权限设置失败")?;
                    }
                }
                b'5' => {
                    parents(&root, &path)?;
                    skip(input, size, cancelled)?;
                }
                b'1' | b'2' => {
                    if path == root
                        || !paths.insert(path.clone())
                        || fs::symlink_metadata(&path).is_ok()
                    {
                        return Err("rootfs链接与现有路径冲突".into());
                    }
                    if target.len() > 4096 || target.contains('\0') || target.contains('\\') {
                        return Err("rootfs链接目标无效".into());
                    }
                    links.push(Link {
                        path,
                        target,
                        hard: kind == b'1',
                    });
                    skip(input, size, cancelled)?;
                }
                b'3' | b'4' | b'6' => skip(input, size, cancelled)?,
                _ => return Err(format!("不支持rootfs tar类型{kind}")),
            }
        }
        skip(input, (512 - size % 512) % 512, cancelled)?;
        progress(total);
    }
    for link in links.iter().filter(|link| link.hard) {
        check(cancelled)?;
        let target = entry_path(&root, prefix, &link.target)?;
        parents(&root, target.parent().ok_or("rootfs硬链接父目录缺失")?)?;
        if !fs::symlink_metadata(&target)
            .ok()
            .is_some_and(|m| m.is_file() && !m.file_type().is_symlink())
        {
            return Err("rootfs硬链接目标无效".into());
        }
        parents(&root, link.path.parent().ok_or("rootfs链接父目录缺失")?)?;
        fs::hard_link(target, &link.path).map_err(|_| "rootfs硬链接创建失败")?;
    }
    for link in links.iter().filter(|link| !link.hard) {
        check(cancelled)?;
        let parent = link.path.parent().ok_or("rootfs链接父目录缺失")?;
        parents(&root, parent)?;
        let target = if link.target.starts_with('/') {
            normalize(&root, &root, link.target.trim_start_matches('/'))?
        } else {
            normalize(&root, parent, &link.target)?
        };
        let relative = relative(parent, &target);
        #[cfg(unix)]
        std::os::unix::fs::symlink(relative, &link.path).map_err(|_| "rootfs符号链接创建失败")?;
        #[cfg(not(unix))]
        {
            let _ = relative;
            return Err("当前平台没有Linux符号链接后端".into());
        }
    }
    sync_directories(&root, 0, &mut 0)?;
    Ok(())
}
fn check(cancelled: &AtomicBool) -> Result<(), String> {
    if cancelled.load(Ordering::Acquire) {
        Err("Linux操作已请求取消".into())
    } else {
        Ok(())
    }
}
fn text(bytes: &[u8]) -> Result<&str, String> {
    std::str::from_utf8(bytes).map_err(|_| "rootfs路径或扩展头不是UTF-8".into())
}
fn field(bytes: &[u8]) -> Result<String, String> {
    text(bytes.split(|byte| *byte == 0).next().unwrap_or(&[])).map(str::to_owned)
}
fn octal(bytes: &[u8]) -> Result<u64, String> {
    let text = field(bytes)?;
    let text = text.trim();
    if text.is_empty() {
        return Ok(0);
    }
    u64::from_str_radix(text, 8).map_err(|_| "rootfs tar数字字段无效".into())
}
fn read_all(input: &mut impl Read, bytes: &mut [u8], cancelled: &AtomicBool) -> Result<(), String> {
    let mut at = 0;
    while at < bytes.len() {
        check(cancelled)?;
        let count = input
            .read(&mut bytes[at..])
            .map_err(|_| "rootfs归档读取失败")?;
        if count == 0 {
            return Err("rootfs归档不完整".into());
        }
        at += count;
    }
    Ok(())
}
fn copy(
    input: &mut impl Read,
    output: &mut impl Write,
    mut size: u64,
    cancelled: &AtomicBool,
) -> Result<(), String> {
    let mut bytes = [0u8; 64 * 1024];
    while size > 0 {
        let count = size.min(bytes.len() as u64) as usize;
        read_all(input, &mut bytes[..count], cancelled)?;
        output
            .write_all(&bytes[..count])
            .map_err(|_| "rootfs文件写入失败")?;
        size -= count as u64;
    }
    Ok(())
}
fn skip(input: &mut impl Read, size: u64, cancelled: &AtomicBool) -> Result<(), String> {
    copy(input, &mut std::io::sink(), size, cancelled)
}
fn parse_pax(bytes: &[u8]) -> Result<HashMap<String, String>, String> {
    let mut values = HashMap::new();
    let mut at = 0;
    while at < bytes.len() {
        let space = bytes[at..]
            .iter()
            .position(|byte| *byte == b' ')
            .ok_or("PAX长度无效")?
            + at;
        let length: usize = text(&bytes[at..space])?
            .parse()
            .map_err(|_| "PAX长度不是整数")?;
        let end = at
            .checked_add(length)
            .filter(|end| *end <= bytes.len() && *end > space + 1)
            .ok_or("PAX长度越界")?;
        if bytes[end - 1] != b'\n' {
            return Err("PAX记录没有结束符".into());
        }
        let value = text(&bytes[space + 1..end - 1])?;
        let (key, value) = value.split_once('=').ok_or("PAX字段没有分隔符")?;
        if matches!(
            key,
            "size" | "GNU.sparse.map" | "GNU.sparse.size" | "GNU.sparse.realsize"
        ) {
            return Err("不支持PAX大小或稀疏覆盖".into());
        }
        values.insert(key.into(), value.into());
        at = end;
    }
    Ok(values)
}
fn entry_path(root: &Path, prefix: &str, raw: &str) -> Result<PathBuf, String> {
    if raw.starts_with('/') || raw.contains('\\') || raw.contains('\0') || raw.len() > 4096 {
        return Err("rootfs条目路径无效".into());
    }
    let name = raw.strip_prefix("./").unwrap_or(raw).trim_end_matches('/');
    let name = if prefix.is_empty() {
        name
    } else if name == prefix {
        ""
    } else {
        name.strip_prefix(prefix)
            .and_then(|name| name.strip_prefix('/'))
            .ok_or("rootfs顶级目录不匹配")?
    };
    normalize(root, root, name)
}
fn normalize(root: &Path, parent: &Path, raw: &str) -> Result<PathBuf, String> {
    let mut path = parent.to_path_buf();
    for component in Path::new(raw).components() {
        match component {
            Component::CurDir => (),
            Component::Normal(name) => path.push(name),
            Component::ParentDir => {
                if path == root || !path.pop() {
                    return Err("rootfs路径越界".into());
                }
            }
            _ => return Err("rootfs路径带宿主前缀".into()),
        }
    }
    if !path.starts_with(root) {
        return Err("rootfs路径越界".into());
    }
    if path
        .strip_prefix(root)
        .map_err(|_| "rootfs路径越界")?
        .components()
        .count()
        > 64
    {
        return Err("rootfs路径深度超过64个目录预算".into());
    }
    Ok(path)
}
fn sync_directories(path: &Path, depth: usize, count: &mut usize) -> Result<(), String> {
    if depth > 64 {
        return Err("rootfs目录同步深度超过预算".into());
    }
    *count += 1;
    if *count > MAX_FILES {
        return Err("rootfs目录同步条目超过预算".into());
    }
    let metadata = fs::symlink_metadata(path).map_err(|_| "rootfs同步路径不可读")?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Ok(());
    }
    for entry in fs::read_dir(path).map_err(|_| "rootfs同步目录不可读")? {
        sync_directories(
            &entry.map_err(|_| "rootfs同步条目不可读")?.path(),
            depth + 1,
            count,
        )?;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_DIRECTORY | libc::O_CLOEXEC)
            .open(path)
            .and_then(|file| file.sync_all())
            .map_err(|_| "rootfs目录同步失败")?;
    }
    Ok(())
}
fn parents(root: &Path, path: &Path) -> Result<(), String> {
    let suffix = path.strip_prefix(root).map_err(|_| "rootfs父目录越界")?;
    let mut current = root.to_path_buf();
    for component in suffix.components() {
        if let Component::Normal(name) = component {
            current.push(name);
        }
        match fs::symlink_metadata(&current) {
            Ok(m) if m.is_dir() && !m.file_type().is_symlink() => (),
            Ok(_) => return Err("rootfs父目录不是普通目录".into()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                fs::create_dir(&current).map_err(|_| "rootfs父目录创建失败")?
            }
            Err(_) => return Err("rootfs父目录不可读".into()),
        }
    }
    Ok(())
}
fn relative(parent: &Path, target: &Path) -> PathBuf {
    let first: Vec<_> = parent.components().collect();
    let last: Vec<_> = target.components().collect();
    let common = first.iter().zip(&last).take_while(|(a, b)| a == b).count();
    let mut result = PathBuf::new();
    for _ in common..first.len() {
        result.push("..");
    }
    for component in last.iter().skip(common) {
        result.push(component.as_os_str());
    }
    if result.as_os_str().is_empty() {
        result.push(".");
    }
    result
}
