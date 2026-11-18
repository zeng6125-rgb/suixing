//! Pure equivalents of the old FileReference tokens. Offsets are Rust UTF8
//! byte positions; paths and labels stay intact through editing/queued copies.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileReference {
    pub start: usize,
    pub end: usize,
    pub path: String,
}
pub fn token(path: &str) -> String {
    let mut value = String::with_capacity(path.len() + 4);
    value.push_str("@\"");
    for character in path.chars() {
        if matches!(character, '\\' | '"') {
            value.push('\\');
        }
        value.push(character);
    }
    value.push('"');
    value
}
pub fn append(text: &str, path: &str) -> String {
    let mut result = text.to_owned();
    if text
        .chars()
        .last()
        .is_some_and(|last| !last.is_whitespace())
    {
        result.push(' ');
    }
    result.push_str(&token(path));
    result.push(' ');
    result
}
fn absolute(path: &str) -> bool {
    path.starts_with('/') || {
        let bytes = path.as_bytes();
        bytes.len() > 2 && bytes[1] == b':' && matches!(bytes[2], b'/' | b'\\')
    }
}
pub fn parse(text: &str) -> Vec<FileReference> {
    let mut found = Vec::new();
    let mut from = 0;
    while from < text.len() {
        let character = text[from..].chars().next().unwrap();
        if character != '@'
            || text[..from].chars().next_back().is_some_and(|previous| {
                previous.is_ascii_alphanumeric() || matches!(previous, '_' | '.' | '-')
            })
        {
            from += character.len_utf8();
            continue;
        }
        let start = from;
        from += 1;
        let quoted = text[from..].starts_with('"');
        if quoted {
            from += 1;
        }
        let mut path = String::new();
        let mut closed = !quoted;
        while from < text.len() {
            let character = text[from..].chars().next().unwrap();
            if quoted && character == '"' {
                from += 1;
                closed = true;
                break;
            }
            if !quoted && character.is_whitespace() {
                break;
            }
            if quoted && character == '\\' {
                if let Some(next) = text[from + 1..]
                    .chars()
                    .next()
                    .filter(|next| matches!(next, '\\' | '"'))
                {
                    path.push(next);
                    from += 1 + next.len_utf8();
                    continue;
                }
            }
            path.push(character);
            from += character.len_utf8();
        }
        if closed && absolute(&path) {
            found.push(FileReference {
                start,
                end: from,
                path,
            });
            if found.len() == 256 {
                break;
            }
        }
    }
    found
}
pub fn only_path(text: &str) -> Option<String> {
    let mut references = parse(text);
    if references.len() != 1 {
        return None;
    }
    let reference = references.pop()?;
    (reference.start == 0 && reference.end == text.len()).then_some(reference.path)
}
// Composer file-browser action calls append on the original target draft, bumps
// text_revision, and leaves picked attachments intact. Display clicks emit
// HistoryOpenFile/PreviewFile intents; they never read files or use OpenUrl for
// file paths. Standard HTTP(S)/mailto Markdown links keep their existing path.
