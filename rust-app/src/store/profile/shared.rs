use super::*;
use crate::profile::Bookkeeping;

const MAX_PROFILE_BYTES: u64 = 256 * 1024;

#[derive(Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
struct Progress {
    revision: u64,
    completed_turn_ordinal: u64,
}

impl From<&Document> for Progress {
    fn from(document: &Document) -> Self {
        Self {
            revision: document.revision,
            completed_turn_ordinal: document.completed_turn_ordinal,
        }
    }
}

#[derive(Default, Serialize, Deserialize)]
struct State {
    #[serde(
        rename = "suixingRustProfileProgress",
        skip_serializing_if = "Option::is_none"
    )]
    progress: Option<Progress>,
    #[serde(flatten)]
    bookkeeping: Bookkeeping,
}

struct SharedProfile<'a> {
    files: &'a Path,
    root: &'a Path,
}

impl SharedProfile<'_> {
    fn checkpoint(&self) -> Result<Option<Progress>, String> {
        optional_json(
            self.root,
            &self.root.join("profile-progress.json"),
            MAX_PROFILE_BYTES,
        )
    }

    fn save_checkpoint(&self, progress: Progress) -> Result<(), String> {
        atomic_json(
            self.root,
            &self.root.join("profile-progress.json"),
            &progress,
            MAX_PROFILE_BYTES,
        )
        .map_err(|error| error.message)
    }

    fn state(&self) -> Result<Option<State>, String> {
        optional_json(
            self.files,
            &self.files.join("profile.state.json"),
            MAX_PROFILE_BYTES,
        )
    }

    fn read(&self, archived: &Document) -> Result<Document, String> {
        let state = self.state()?.unwrap_or_default();
        let progress = self.checkpoint()?.map_or(Progress::default(), |saved| {
            saved.max(state.progress.unwrap_or_default())
        });
        Ok(Document {
            text: read_text(self.files, "profile.md")?.unwrap_or_default(),
            previous_text: read_text(self.files, "profile.md.bak")?,
            state: state.bookkeeping,
            revision: progress.revision,
            completed_turn_ordinal: progress.completed_turn_ordinal,
            ..archived.clone()
        })
    }

    fn seed(&self, document: &Document) -> Result<(), String> {
        if let Some(backup) = &document.previous_text
            && read_text(self.files, "profile.md.bak")?.is_none()
        {
            self.write_text("profile.md.bak", backup)?;
        }
        if (!document.text.is_empty() || document.previous_text.is_some())
            && read_text(self.files, "profile.md")?.is_none()
        {
            self.write_text("profile.md", &document.text)?;
        }
        if document.state != Bookkeeping::default() && self.state()?.is_none() {
            self.write_state(&document.state, None)?;
        }
        self.save_checkpoint(document.into())
    }

    fn write_text(&self, name: &str, text: &str) -> Result<(), String> {
        atomic_bytes(self.files, &self.files.join(name), text.as_bytes())
            .map_err(|error| error.message)
    }

    fn write_state(
        &self,
        bookkeeping: &Bookkeeping,
        progress: Option<Progress>,
    ) -> Result<(), String> {
        i32::try_from(bookkeeping.profile_turn_mark)
            .map_err(|_| "画像轮数超出 Compose 支持范围")?;
        atomic_json(
            self.files,
            &self.files.join("profile.state.json"),
            &State {
                bookkeeping: bookkeeping.clone(),
                progress,
            },
            MAX_PROFILE_BYTES,
        )
        .map_err(|error| error.message)
    }

    fn commit(&self, previous: &Document, next: &Document) -> Result<Document, String> {
        if &self.read(previous)? != previous {
            return Err("画像磁盘内容或轮数已变化，请重新读取；未覆盖原文".into());
        }
        // The state write commits the counter and its Rust replay ordinal together.
        // The private checkpoint survives when Compose rewrites only its own fields.
        if self.checkpoint()?.is_none() {
            self.save_checkpoint(previous.into())?;
        }
        if previous.previous_text != next.previous_text
            && let Some(backup) = &next.previous_text
        {
            self.write_text("profile.md.bak", backup)?;
        }
        if previous.text != next.text {
            self.write_text("profile.md", &next.text)?;
        }
        if previous.state != next.state
            || previous.completed_turn_ordinal != next.completed_turn_ordinal
        {
            self.write_state(&next.state, Some(next.into()))?;
        }
        self.save_checkpoint(next.into())?;
        let actual = self.read(next)?;
        if &actual != next {
            return Err("画像发布后实际读回与提交不一致".into());
        }
        Ok(actual)
    }
}

pub(super) fn open(
    store: &Store,
    files: PathBuf,
    generation: String,
    root_metadata: Metadata,
) -> Result<Repository, String> {
    let namespace = store.namespace_root.clone();
    let root = store.root.clone();
    let shared = SharedProfile {
        files: &files,
        root: &root,
    };
    let archived = optional_json::<Document>(&root, &root.join("profile.json"), MAX_PROFILE_BYTES)?;
    if let Some(document) = &archived
        && shared.checkpoint()?.is_none()
        && store.writable().is_ok()
    {
        validate_namespace(&namespace, &root, &root_metadata)?;
        crate::profile::encode(document)?;
        shared.seed(document)?;
    }
    let mut previous = shared.read(&archived.unwrap_or_default())?;
    let bytes = crate::profile::encode(&previous)?;
    if let Some(saved) = shared.checkpoint()?
        && Progress::from(&previous) > saved
        && store.writable().is_ok()
    {
        shared.save_checkpoint((&previous).into())?;
    }
    let sink = Box::new(move |next: &Document, _expected: &Revision| {
        validate_namespace(&namespace, &root, &root_metadata)?;
        let shared = SharedProfile {
            files: &files,
            root: &root,
        };
        let actual = shared.commit(&previous, next)?;
        previous = actual.clone();
        Ok(actual)
    });
    Repository::open(generation, Some(&bytes), sink)
}

fn read_text(files: &Path, name: &str) -> Result<Option<String>, String> {
    let path = files.join(name);
    match fs::symlink_metadata(&path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(fs_error("读取画像文件信息", &path, error)),
        Ok(metadata) if !metadata.is_file() => return Err("画像文件不是普通文件".into()),
        Ok(_) => {}
    }
    let mut text = String::new();
    checked_file(files, &path)?
        .take(MAX_PROFILE_BYTES + 1)
        .read_to_string(&mut text)
        .map_err(|error| fs_error("读取画像文本", &path, error))?;
    if text.len() as u64 > MAX_PROFILE_BYTES {
        return Err("画像文本超过读取预算".into());
    }
    Ok(Some(text))
}
