use super::*;
use crate::todos::{Board, Item};

const LIMIT: u64 = 3 * 1024 * 1024;

pub(super) fn read(files: &Path) -> Result<Option<Vec<Item>>, String> {
    optional_json(files, &files.join("todos.json"), LIMIT)
}

pub(super) fn preserve_item_fields(previous: &Board, next: &mut Board) {
    let by_id: HashMap<_, _> = previous
        .items
        .iter()
        .map(|item| (item.id.as_str(), &item.extra))
        .collect();
    for item in &mut next.items {
        if let Some(fields) = by_id.get(item.id.as_str()) {
            for (key, value) in *fields {
                item.extra
                    .entry(key.clone())
                    .or_insert_with(|| value.clone());
            }
        }
    }
}

pub(super) fn write(files: &Path, board: &Board) -> Result<(), CommitFailure> {
    crate::todos::validate(board)?;
    atomic_json(files, &files.join("todos.json"), &board.items, LIMIT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::todos::Operation;
    use serde_json::{Value, json};

    #[test]
    fn shared_todos_store_reads_and_writes_compose_file() {
        let files = std::env::temp_dir().join(fresh_id("shared-todos").unwrap());
        fs::create_dir(&files).unwrap();
        let files = fs::canonicalize(files).unwrap();
        let path = files.join("todos.json");
        assert!(read(&files).unwrap().is_none());

        fs::write(
            &path,
            r#"[{"id":"t1","subject":"核对资料","status":"in_progress","label":{"value":"保留"}}]"#,
        )
        .unwrap();
        let root = files.join("rust-state");
        let mut store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
        assert_eq!(store.todos().items[0].subject, "核对资料");
        store
            .mutate_todos(Operation::Write {
                items: serde_json::from_value(json!([
                    {"id":"t1","subject":"核对资料","status":"completed"},
                    {"id":"t2","subject":"归档"}
                ]))
                .unwrap(),
                expected_revision: Some(0),
            })
            .unwrap();
        let saved: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(
            saved,
            json!([
                {"id":"t1","subject":"核对资料","status":"completed","label":{"value":"保留"}},
                {"id":"t2","subject":"归档","status":"pending"}
            ])
        );
        fs::write(&path, "[]").unwrap();
        let reopened = Store::open_with_shared_files(root, Some(files)).unwrap();
        assert!(reopened.todos().items.is_empty());
    }
}
