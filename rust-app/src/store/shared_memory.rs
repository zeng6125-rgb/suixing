use super::*;

#[derive(Deserialize)]
#[serde(untagged)]
enum Note {
    Entry(Entry),
    Legacy(String),
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    value: String,
    #[serde(default)]
    updated_at: i64,
}

pub(super) fn read(files: &Path) -> Result<Vec<Memory>, String> {
    let notes = optional_json::<BTreeMap<String, Note>>(
        files,
        &files.join("memory.json"),
        MAX_MEMORY_BYTES,
    )?
    .unwrap_or_default();
    Ok(notes
        .into_iter()
        .map(|(id, note)| {
            let (text, updated_at) = match note {
                Note::Entry(entry) => (entry.value, entry.updated_at),
                Note::Legacy(text) => (text, 0),
            };
            Memory {
                id,
                text,
                updated_at,
            }
        })
        .collect())
}

pub(super) fn write(files: &Path, memories: &[Memory]) -> Result<(), CommitFailure> {
    let notes: BTreeMap<_, _> = memories
        .iter()
        .map(|memory| {
            (
                &memory.id,
                Entry {
                    value: memory.text.clone(),
                    updated_at: memory.updated_at,
                },
            )
        })
        .collect();
    atomic_json(files, &files.join("memory.json"), &notes, MAX_MEMORY_BYTES)
}
