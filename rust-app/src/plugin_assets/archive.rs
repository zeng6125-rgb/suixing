//! Bounded ZIP reader using the already cached flate2 Deflate decoder. Stored,
//! Deflate, data descriptors, UTF-8/CP437 names and budget-fitting ZIP64 work.
//! Encryption, multi-volume archives and non-regular Unix entry types fail.
use super::*;
use std::io::Read;
pub(crate) const MAX_COMPRESSED: u64 = 64 * 1024 * 1024;

fn u16(bytes: &[u8], at: usize) -> Result<u16, String> {
    Ok(u16::from_le_bytes(
        bytes
            .get(at..at + 2)
            .ok_or("ZIP字段截断")?
            .try_into()
            .map_err(|_| "ZIP u16字段无效")?,
    ))
}
fn u32(bytes: &[u8], at: usize) -> Result<u32, String> {
    Ok(u32::from_le_bytes(
        bytes
            .get(at..at + 4)
            .ok_or("ZIP字段截断")?
            .try_into()
            .map_err(|_| "ZIP u32字段无效")?,
    ))
}
fn u64(bytes: &[u8], at: usize) -> Result<u64, String> {
    Ok(u64::from_le_bytes(
        bytes
            .get(at..at + 8)
            .ok_or("ZIP字段截断")?
            .try_into()
            .map_err(|_| "ZIP u64字段无效")?,
    ))
}
fn size(value: u64) -> Result<usize, String> {
    usize::try_from(value).map_err(|_| "ZIP偏移超出当前ABI".to_owned())
}
fn range(bytes: &[u8], at: usize, count: usize) -> Result<&[u8], String> {
    bytes
        .get(at..at.checked_add(count).ok_or("ZIP范围溢出")?)
        .ok_or_else(|| "ZIP范围越界".into())
}
const fn crc_table() -> [u32; 256] {
    let mut table = [0; 256];
    let mut index = 0;
    while index < 256 {
        let mut value = index as u32;
        let mut bit = 0;
        while bit < 8 {
            value = (value >> 1) ^ (0xedb88320u32 & 0u32.wrapping_sub(value & 1));
            bit += 1;
        }
        table[index] = value;
        index += 1;
    }
    table
}
fn crc(bytes: &[u8]) -> u32 {
    const TABLE: [u32; 256] = crc_table();
    let mut value = !0u32;
    for byte in bytes {
        value = (value >> 8) ^ TABLE[((value ^ *byte as u32) & 255) as usize];
    }
    !value
}
// CP437 is ZIP's original non-UTF8 filename encoding. Filesystem paths remain
// UTF8 after decoding, and names are checked before extracting a single byte.
fn name(raw: &[u8], utf8: bool) -> Result<String, String> {
    if utf8 {
        return String::from_utf8(raw.to_vec()).map_err(|_| "ZIP名称UTF8标记与字节不匹配".into());
    }
    const CP: &str = "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ ";
    let table: Vec<_> = CP.chars().collect();
    if table.len() != 128 {
        return Err("ZIP字符表异常".into());
    }
    Ok(raw
        .iter()
        .map(|byte| {
            if *byte < 128 {
                *byte as char
            } else {
                table[*byte as usize - 128]
            }
        })
        .collect())
}
struct Entry {
    path: PathBuf,
    raw_name: Vec<u8>,
    directory: bool,
    mode: u32,
    flags: u16,
    method: u16,
    crc: u32,
    compressed: u64,
    unpacked: u64,
    local: u64,
}
pub(crate) struct Package {
    pub manifest: crate::plugins::PluginManifest,
    pub raw_manifest: Vec<u8>,
    pub tree: Tree,
    pub archive_sha256: String,
}
impl std::fmt::Debug for Package {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PluginPackage")
            .field("id", &self.manifest.id)
            .field("tree", &self.tree)
            .field("archive_sha256", &self.archive_sha256)
            .finish()
    }
}

pub(crate) fn read(
    bytes: &[u8],
    reserved: &std::collections::HashSet<String>,
    cancel: &CancelToken,
) -> Result<Package, String> {
    let tree = extract_tree(bytes, cancel, true, MAX_NODES, MAX_BYTES)?;
    let raw_manifest = tree
        .files
        .get(Path::new("manifest.json"))
        .ok_or("ZIP选定目录没有清单")?
        .clone();
    if raw_manifest.len() > 128 * 1024 {
        return Err("插件清单超过128 KiB".into());
    }
    let manifest = crate::plugins::parse_manifest(
        std::str::from_utf8(&raw_manifest).map_err(|_| "插件清单不是UTF8")?,
        reserved,
    )?;
    Ok(Package {
        manifest,
        raw_manifest,
        tree,
        archive_sha256: hash(bytes),
    })
}
pub(crate) fn read_tree(
    bytes: &[u8],
    cancel: &CancelToken,
    max_nodes: usize,
    max_bytes: u64,
) -> Result<Tree, String> {
    extract_tree(
        bytes,
        cancel,
        false,
        max_nodes.min(MAX_NODES),
        max_bytes.min(MAX_BYTES),
    )
}
fn extract_tree(
    bytes: &[u8],
    cancel: &CancelToken,
    plugin: bool,
    max_nodes: usize,
    max_bytes: u64,
) -> Result<Tree, String> {
    if bytes.len() < 22 || bytes.len() as u64 > MAX_COMPRESSED {
        return Err("插件ZIP为空、截断或压缩包超过64 MiB".into());
    }
    let first = bytes.len().saturating_sub(22 + 65535);
    let end = (first..=bytes.len() - 22)
        .rev()
        .find(|at| {
            u32(bytes, *at).ok() == Some(0x06054b50)
                && u16(bytes, *at + 20)
                    .ok()
                    .is_some_and(|comment| *at + 22 + comment as usize == bytes.len())
        })
        .ok_or("ZIP缺少有效中央目录结束标记")?;
    if u16(bytes, end + 4)? != 0 || u16(bytes, end + 6)? != 0 {
        return Err("插件ZIP不支持多卷".into());
    }
    let mut count = u16(bytes, end + 10)? as u64;
    let mut central_size = u32(bytes, end + 12)? as u64;
    let mut central_offset = u32(bytes, end + 16)? as u64;
    let mut central_end = end;
    if count == 0xffff || central_size == 0xffff_ffff || central_offset == 0xffff_ffff {
        if end < 20
            || u32(bytes, end - 20)? != 0x07064b50
            || u32(bytes, end - 16)? != 0
            || u32(bytes, end - 4)? != 1
        {
            return Err("ZIP64定位器或卷信息无效".into());
        }
        let record = size(u64(bytes, end - 12)?)?;
        if u32(bytes, record)? != 0x06064b50
            || u64(bytes, record + 4)? < 44
            || record
                .checked_add(12 + size(u64(bytes, record + 4)?)?)
                .ok_or("ZIP64长度溢出")?
                != end - 20
        {
            return Err("ZIP64结束记录范围无效".into());
        }
        if u32(bytes, record + 16)? != 0
            || u32(bytes, record + 20)? != 0
            || u64(bytes, record + 24)? != u64(bytes, record + 32)?
        {
            return Err("ZIP64不支持多卷".into());
        }
        count = u64(bytes, record + 32)?;
        central_size = u64(bytes, record + 40)?;
        central_offset = u64(bytes, record + 48)?;
        central_end = record;
    } else if u16(bytes, end + 8)? as u64 != count {
        return Err("ZIP中央目录卷计数不一致".into());
    }
    if count == 0
        || count > max_nodes as u64
        || central_offset.checked_add(central_size) != Some(central_end as u64)
    {
        return Err("ZIP中央目录预算或范围无效".into());
    }
    let mut entries = Vec::new();
    let mut cursor = size(central_offset)?;
    let mut seen = BTreeSet::new();
    let mut total = 0u64;
    for _ in 0..count {
        if cancel.is_cancelled() {
            return Err("插件ZIP读取已取消".into());
        }
        if u32(bytes, cursor)? != 0x02014b50 {
            return Err("ZIP中央条目无效".into());
        }
        let flags = u16(bytes, cursor + 8)?;
        let method = u16(bytes, cursor + 10)?;
        if flags & !0x080e != 0 || !matches!(method, 0 | 8) {
            return Err("插件ZIP含加密或不支持的压缩方法".into());
        }
        let name_len = u16(bytes, cursor + 28)? as usize;
        let extra_len = u16(bytes, cursor + 30)? as usize;
        let comment_len = u16(bytes, cursor + 32)? as usize;
        let raw_name = range(bytes, cursor + 46, name_len)?.to_vec();
        let mut decoded = name(&raw_name, flags & 0x0800 != 0)?;
        let directory = decoded.ends_with('/');
        if directory {
            decoded.pop();
        }
        let path = PathBuf::from(decoded);
        relative(&path)?;
        if !seen.insert(path.clone()) {
            return Err("插件ZIP含重复名称".into());
        }
        let mut compressed = u32(bytes, cursor + 20)? as u64;
        let mut unpacked = u32(bytes, cursor + 24)? as u64;
        let mut local = u32(bytes, cursor + 42)? as u64;
        let mut disk = u16(bytes, cursor + 34)? as u32;
        let extra = range(bytes, cursor + 46 + name_len, extra_len)?;
        let mut extra_cursor = 0;
        let need_zip64 = compressed == 0xffff_ffff
            || unpacked == 0xffff_ffff
            || local == 0xffff_ffff
            || disk == 0xffff;
        let mut zip64_found = false;
        while extra_cursor < extra.len() {
            let kind = u16(extra, extra_cursor)?;
            let length = u16(extra, extra_cursor + 2)? as usize;
            let content = range(extra, extra_cursor + 4, length)?;
            if kind == 1 {
                if zip64_found {
                    return Err("ZIP64附加信息重复".into());
                }
                zip64_found = true;
                let mut at = 0;
                if unpacked == 0xffff_ffff {
                    unpacked = u64(content, at)?;
                    at += 8;
                }
                if compressed == 0xffff_ffff {
                    compressed = u64(content, at)?;
                    at += 8;
                }
                if local == 0xffff_ffff {
                    local = u64(content, at)?;
                    at += 8;
                }
                if disk == 0xffff {
                    disk = u32(content, at)?;
                }
            }
            extra_cursor = extra_cursor
                .checked_add(4 + length)
                .ok_or("ZIP附加范围溢出")?;
        }
        if need_zip64 && !zip64_found || disk != 0 {
            return Err("ZIP64尺寸缺失或ZIP含多卷条目".into());
        }
        let unix_type = (u32(bytes, cursor + 38)? >> 16) & 0o170000;
        let unix_mode = (u32(bytes, cursor + 38)? >> 16) & 0o777;
        if !matches!(unix_type, 0 | 0o100000 | 0o040000)
            || unix_type == 0o040000 && !directory
            || unix_type == 0o100000 && directory
        {
            return Err("插件ZIP含链接、设备或类型不一致条目".into());
        }
        if directory && unpacked != 0 {
            return Err("ZIP目录含非空内容".into());
        }
        if unpacked > max_bytes || compressed > max_bytes {
            return Err("ZIP单项超过256 MiB".into());
        }
        total = total
            .checked_add(unpacked)
            .filter(|total| *total <= max_bytes)
            .ok_or("ZIP解压总量超过256 MiB")?;
        let mode = if unix_mode == 0 {
            if directory { 0o700 } else { 0o600 }
        } else {
            unix_mode
        };
        entries.push(Entry {
            path,
            raw_name,
            directory,
            mode,
            flags,
            method,
            crc: u32(bytes, cursor + 16)?,
            compressed,
            unpacked,
            local,
        });
        cursor = cursor
            .checked_add(46 + name_len + extra_len + comment_len)
            .ok_or("ZIP中央目录偏移溢出")?;
        if cursor > central_end {
            return Err("ZIP中央目录条目越界".into());
        }
    }
    if cursor != central_end {
        return Err("ZIP中央目录有未解析内容".into());
    }
    let root_manifest = entries
        .iter()
        .any(|entry| entry.path == Path::new("manifest.json") && !entry.directory);
    let wrapped: Vec<_> = entries
        .iter()
        .filter(|entry| {
            entry
                .path
                .file_name()
                .is_some_and(|name| name == "manifest.json")
                && entry.path.components().count() == 2
                && !entry.directory
        })
        .map(|entry| entry.path.parent().unwrap().to_owned())
        .collect();
    let prefix = if !plugin || root_manifest {
        PathBuf::new()
    } else if wrapped.len() == 1 {
        wrapped[0].clone()
    } else {
        return Err("插件包缺少根清单或含多个包装目录清单".into());
    };
    let mut tree = Tree {
        layout: Vec::new(),
        files: BTreeMap::new(),
        warnings: Vec::new(),
    };
    let mut extents = Vec::new();
    for entry in entries {
        let local = size(entry.local)?;
        if u32(bytes, local)? != 0x04034b50
            || u16(bytes, local + 6)? != entry.flags
            || u16(bytes, local + 8)? != entry.method
        {
            return Err("ZIP本地条目与中央目录不一致".into());
        }
        let length = u16(bytes, local + 26)? as usize;
        let extra = u16(bytes, local + 28)? as usize;
        if range(bytes, local + 30, length)? != entry.raw_name {
            return Err("ZIP本地名称与中央目录不一致".into());
        }
        let start = local
            .checked_add(30 + length + extra)
            .ok_or("ZIP本地范围溢出")?;
        let finish = start
            .checked_add(size(entry.compressed)?)
            .filter(|finish| *finish <= size(central_offset).unwrap_or(0))
            .ok_or("ZIP数据越过中央目录")?;
        let mut extent_finish = finish;
        if entry.flags & 8 != 0 {
            let mut descriptor = finish;
            if u32(bytes, descriptor)? == 0x08074b50 {
                descriptor += 4;
            }
            if u32(bytes, descriptor)? != entry.crc {
                return Err("ZIP数据描述符CRC不一致".into());
            }
            descriptor += 4;
            let zip64 =
                u32(bytes, local + 18)? == 0xffff_ffff || u32(bytes, local + 22)? == 0xffff_ffff;
            if zip64 {
                if u64(bytes, descriptor)? != entry.compressed
                    || u64(bytes, descriptor + 8)? != entry.unpacked
                {
                    return Err("ZIP64数据描述符尺寸不一致".into());
                }
                extent_finish = descriptor.checked_add(16).ok_or("ZIP64描述符长度溢出")?;
            } else {
                if u32(bytes, descriptor)? as u64 != entry.compressed
                    || u32(bytes, descriptor + 4)? as u64 != entry.unpacked
                {
                    return Err("ZIP数据描述符尺寸不一致".into());
                }
                extent_finish = descriptor.checked_add(8).ok_or("ZIP描述符长度溢出")?;
            }
            if extent_finish > size(central_offset)? {
                return Err("ZIP描述符越过中央目录".into());
            }
        }
        if extents
            .iter()
            .any(|(begin, end)| local < *end && extent_finish > *begin)
        {
            return Err("ZIP本地数据条目互相覆盖".into());
        }
        extents.push((local, extent_finish));
        if entry.flags & 8 == 0 {
            if u32(bytes, local + 14)? != entry.crc {
                return Err("ZIP本地CRC与中央目录不一致".into());
            }
            let packed = u32(bytes, local + 18)?;
            let unpacked = u32(bytes, local + 22)?;
            if packed != 0xffff_ffff && packed as u64 != entry.compressed
                || unpacked != 0xffff_ffff && unpacked as u64 != entry.unpacked
            {
                return Err("ZIP本地尺寸与中央目录不一致".into());
            }
        }
        if entry.directory && entry.path == prefix {
            continue;
        }
        let selected = if prefix.as_os_str().is_empty() {
            Some(entry.path.clone())
        } else {
            entry
                .path
                .strip_prefix(&prefix)
                .ok()
                .filter(|path| !path.as_os_str().is_empty())
                .map(Path::to_owned)
        };
        let Some(path) = selected else {
            tree.warnings
                .push(format!("选定插件目录之外的{}未安装", entry.path.display()));
            continue;
        };
        if plugin && (path.starts_with("env.json") || path.starts_with("state.json")) {
            tree.warnings.push(format!(
                "包内宿主文件{}已丢弃；env与state由宿主管理",
                path.display()
            ));
            continue;
        }
        let mut content = Vec::new();
        if !entry.directory {
            let input = range(bytes, start, size(entry.compressed)?)?;
            if entry.method == 0 {
                if entry.compressed != entry.unpacked {
                    return Err("ZIP存储项尺寸不一致".into());
                }
                content.extend_from_slice(input);
            } else {
                let mut decoder = flate2::read::DeflateDecoder::new(input);
                let mut chunk = [0u8; 64 * 1024];
                loop {
                    if cancel.is_cancelled() {
                        return Err("ZIP解压已取消".into());
                    }
                    let count = decoder
                        .read(&mut chunk)
                        .map_err(|_| "ZIP Deflate数据无效")?;
                    if count == 0 {
                        break;
                    }
                    if content
                        .len()
                        .checked_add(count)
                        .is_none_or(|length| length as u64 > entry.unpacked)
                    {
                        return Err("ZIP数据超过声明解压尺寸".into());
                    }
                    content.extend_from_slice(&chunk[..count]);
                }
                if decoder.total_in() != entry.compressed {
                    return Err("ZIP Deflate尾部含未消费数据".into());
                }
            }
            if content.len() as u64 != entry.unpacked || crc(&content) != entry.crc {
                return Err("ZIP解压尺寸或CRC不匹配".into());
            }
        }
        let sha256 = if entry.directory {
            None
        } else {
            Some(hash(&content))
        };
        if !entry.directory {
            tree.files.insert(path.clone(), content);
        }
        tree.layout.push(Layout {
            path,
            directory: entry.directory,
            mode: entry.mode,
            bytes: entry.unpacked,
            sha256,
        });
    }
    let mut parents = BTreeSet::new();
    for node in &tree.layout {
        for parent in node
            .path
            .ancestors()
            .skip(1)
            .filter(|path| !path.as_os_str().is_empty())
        {
            parents.insert(parent.to_owned());
        }
    }
    for parent in parents {
        if !tree.layout.iter().any(|node| node.path == parent) {
            tree.layout.push(Layout {
                path: parent,
                directory: true,
                mode: 0o700,
                bytes: 0,
                sha256: None,
            });
        }
    }
    tree.layout
        .sort_by(|first, second| first.path.cmp(&second.path));
    tree.validate()?;
    Ok(tree)
}
