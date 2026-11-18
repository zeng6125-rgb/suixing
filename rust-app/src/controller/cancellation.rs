//! One bounded watcher ties queued effects to their live runtime scope.
use crate::network::CancelToken;
use std::{
    collections::HashMap,
    sync::{
        Arc, Condvar, Mutex, OnceLock, Weak,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};
type Validator = Arc<dyn Fn() -> bool + Send + Sync>;
struct Entry {
    flag: Weak<AtomicBool>,
    cancel: Weak<CancelToken>,
    valid: Option<Validator>,
}
struct Shared {
    entries: Mutex<HashMap<u64, Entry>>,
    changed: Condvar,
}
static WATCHER: OnceLock<Result<Arc<Shared>, String>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
pub(crate) fn flag(
    cancel: Arc<CancelToken>,
    valid: Option<Validator>,
) -> Result<Arc<AtomicBool>, String> {
    let shared = WATCHER
        .get_or_init(|| {
            let shared = Arc::new(Shared {
                entries: Mutex::new(HashMap::new()),
                changed: Condvar::new(),
            });
            let worker = shared.clone();
            thread::Builder::new()
                .name("rust-effect-cancellation".into())
                .spawn(move || {
                    loop {
                        let mut entries = worker.entries.lock().unwrap_or_else(|e| e.into_inner());
                        while entries.is_empty() {
                            entries = worker
                                .changed
                                .wait(entries)
                                .unwrap_or_else(|e| e.into_inner());
                        }
                        entries.retain(|_, entry| {
                            let Some(flag) = entry.flag.upgrade() else {
                                return false;
                            };
                            let live = entry
                                .cancel
                                .upgrade()
                                .is_some_and(|cancel| !cancel.is_cancelled())
                                && entry.valid.as_ref().is_none_or(|valid| valid());
                            if !live {
                                flag.store(true, Ordering::Release);
                            }
                            live
                        });
                        drop(entries);
                        thread::sleep(Duration::from_millis(20));
                    }
                })
                .map_err(|_| "取消管理线程无法启动".to_owned())?;
            Ok(shared)
        })
        .as_ref()
        .map_err(Clone::clone)?
        .clone();
    let id = NEXT
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| "取消身份耗尽")?;
    let flag = Arc::new(AtomicBool::new(cancel.is_cancelled()));
    let mut entries = shared.entries.lock().unwrap_or_else(|e| e.into_inner());
    entries.retain(|_, entry| entry.flag.strong_count() > 0);
    if entries.len() >= 128 {
        return Err("待执行操作取消范围达到128个".into());
    }
    entries.insert(
        id,
        Entry {
            flag: Arc::downgrade(&flag),
            cancel: Arc::downgrade(&cancel),
            valid,
        },
    );
    shared.changed.notify_one();
    Ok(flag)
}
