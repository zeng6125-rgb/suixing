//! Bounded child cohorts borrow the same immutable record during execution.
//! Record mutation/checkpointing is serial and preserves every actual receipt.
use super::*;
impl ChildRun<'_> {
    pub(super) fn execute_tool_batch(
        &self,
        settings: &Settings,
        policy: &ChildPolicy,
        runtime: &tokio::runtime::Runtime,
        record: &mut Record,
        calls: &[ToolCall],
        ledger: &mut agent::run_ledger::RunLedger,
    ) -> Result<(), String> {
        let groups = crate::tool_execution::groups(
            calls,
            settings.behavior.tool_parallelism(),
            policy.config.approval_mode,
        );
        for group in groups {
            if self.cancel.is_cancelled() {
                record.messages.extend(
                    calls[group.start..]
                        .iter()
                        .map(|call| tool_result(call.id.clone(), cancelled_before_dispatch())),
                );
                return Err("子任务已停止".into());
            }
            let results = if group.len() == 1 {
                vec![
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        self.call_tool(settings, policy, runtime, record, &calls[group.start])
                    }))
                    .unwrap_or_else(|_| {
                        Err(ToolFailure {
                            error: "子任务工具执行异常，效果未知，请先检查".into(),
                            dispatched: true,
                        })
                    }),
                ]
            } else {
                // Include all peers before any is dispatched. Each current
                // builtin maximum is 5 MiB including tool image/header escape.
                let reserve = (5usize * 1024 * 1024)
                    .checked_mul(group.len())
                    .ok_or("子工具并行结果预算溢出")?;
                if record_size(record)?
                    > RECORD_BUDGET
                        .saturating_sub(ANSWER_RESERVE)
                        .saturating_sub(PENDING_RESERVE)
                        .saturating_sub(reserve)
                {
                    append_not_dispatched(
                        record,
                        calls[group.start..].iter().cloned(),
                        "子任务没有空间保留整个并行组结果，本组未执行",
                    );
                    return Err("子任务没有空间保留整个并行组结果，本组未执行".into());
                }
                let snapshot: &Record = record;
                thread::scope(|scope| {
                    let mut workers = Vec::with_capacity(group.len());
                    for call in &calls[group.clone()] {
                        let worker = thread::Builder::new()
                            .name("jagent-child-tool".into())
                            .spawn_scoped(scope, move || {
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    let runtime = tokio::runtime::Builder::new_current_thread()
                                        .enable_all()
                                        .build()
                                        .map_err(|_| {
                                            ToolFailure::not_dispatched(
                                                "无法创建子工具网络运行时，未执行".into(),
                                            )
                                        })?;
                                    self.call_tool(settings, policy, &runtime, snapshot, call)
                                }))
                                .unwrap_or_else(|_| {
                                    Err(ToolFailure {
                                        error: "子工具worker异常，效果未知".into(),
                                        dispatched: true,
                                    })
                                })
                            });
                        workers.push(worker.map_err(|_| {
                            ToolFailure::not_dispatched("无法创建子工具worker，未执行本调用".into())
                        }));
                    }
                    workers
                        .into_iter()
                        .map(|worker| match worker {
                            Err(error) => Err(error),
                            Ok(worker) => worker.join().unwrap_or_else(|_| {
                                Err(ToolFailure {
                                    error: "子工具worker终止，效果未知".into(),
                                    dispatched: true,
                                })
                            }),
                        })
                        .collect::<Vec<_>>()
                })
            };
            let mut terminal_error = None;
            for (call, result) in calls[group.clone()].iter().zip(results) {
                let result = match result {
                    Ok(value) => value,
                    Err(failure) => {
                        terminal_error = terminal_error.or(Some(failure.error.clone()));
                        json!({"ok":false,"started":failure.dispatched,"effect_unknown":failure.dispatched,"error":failure.error})
                    }
                };
                ledger.record(call, &result)?;
                record.messages.push(tool_result(call.id.clone(), result));
            }
            // Join completes before recording. Never label an already executed
            // sibling as not_started when one peer or checkpoint failed.
            if let Err(error) = self.checkpoint(record) {
                append_not_dispatched(record, calls[group.end..].iter().cloned(), &error);
                return Err(error);
            }
            if let Some(error) = terminal_error {
                append_not_dispatched(record, calls[group.end..].iter().cloned(), &error);
                return Err(error);
            }
        }
        Ok(())
    }
}
