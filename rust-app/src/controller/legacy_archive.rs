//! A single bounded read worker for sealed legacy process evidence.
use crate::legacy_process_browser::{self as browser, Generation};
use std::{
    path::PathBuf,
    sync::mpsc::{self, Receiver, SyncSender, TryRecvError},
    thread,
};

#[derive(Clone, Debug)]
pub(crate) enum Request {
    Open {
        request_id: String,
        ui_token: String,
    },
    Read(browser::Request),
}
impl Request {
    pub(crate) fn id(&self) -> &str {
        match self {
            Self::Open { request_id, .. } => request_id,
            Self::Read(
                browser::Request::List { request_id, .. }
                | browser::Request::Detail { request_id, .. }
                | browser::Request::Log { request_id, .. },
            ) => request_id,
        }
    }
}
#[derive(Clone, Debug)]
pub(crate) enum Update {
    Opened {
        request_id: String,
        ui_token: String,
        result: Result<Generation, String>,
    },
    Read(browser::Update),
    Failed {
        request_id: String,
        error: String,
    },
}
pub(crate) struct Runtime {
    jobs: SyncSender<(Request, PathBuf)>,
    replies: Receiver<Update>,
}
impl Runtime {
    pub(crate) fn boot() -> Result<Self, String> {
        let (jobs, receiver) = mpsc::sync_channel::<(Request, PathBuf)>(4);
        let (output, replies) = mpsc::sync_channel(4);
        thread::Builder::new()
            .name("rust-legacy-archives".into())
            .spawn(move || {
                let mut repository: Option<browser::Repository> = None;
                while let Ok((request, namespace)) = receiver.recv() {
                    let update = match request {
                        Request::Open {
                            request_id,
                            ui_token,
                        } => {
                            repository = None;
                            let result = browser::Repository::open(namespace, ui_token.clone())
                                .map(|opened| {
                                    let generation = opened.generation();
                                    repository = Some(opened);
                                    generation
                                });
                            Update::Opened {
                                request_id,
                                ui_token,
                                result,
                            }
                        }
                        Request::Read(request) => match &repository {
                            Some(repository) => Update::Read(repository.dispatch(request)),
                            None => Update::Failed {
                                request_id: Request::Read(request).id().into(),
                                error: "请先打开迁移归档".into(),
                            },
                        },
                    };
                    if output.send(update).is_err() {
                        break;
                    }
                }
            })
            .map_err(|_| "归档读取线程无法启动")?;
        Ok(Self { jobs, replies })
    }
    pub(crate) fn dispatch(&self, request: Request, namespace: PathBuf) -> Result<(), String> {
        if request.id().is_empty() || request.id().len() > 128 || request.id().contains('\0') {
            return Err("归档读取身份无效".into());
        }
        self.jobs
            .try_send((request, namespace))
            .map_err(|_| "归档读取队列已满或不可用".into())
    }
    pub(crate) fn poll(&mut self) -> Vec<Update> {
        let mut updates = Vec::new();
        for _ in 0..4 {
            match self.replies.try_recv() {
                Ok(update) => updates.push(update),
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        updates
    }
}
