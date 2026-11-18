use super::*;
use platform::{
    automation::{ActionResult, GlobalAction},
    virtual_screen::{self, Identity},
};
use std::{
    sync::atomic::{AtomicBool, AtomicUsize, Ordering},
    time::{Duration, Instant},
};
static RELAYS: AtomicUsize = AtomicUsize::new(0);
fn xml_dump_argv() -> Vec<String> {
    vec!["/system/bin/sh".into(),"-c".into(),
        "tmp=$(/system/bin/mktemp -d ./.jagent-ui-dump.XXXXXXXX) || exit 71; trap 'status=$?; /system/bin/rm -f -- \"$tmp/window.xml\" && /system/bin/rmdir -- \"$tmp\" || exit 72; exit \"$status\"' EXIT; /system/bin/uiautomator dump \"$tmp/window.xml\" >/dev/null || exit 73; /system/bin/cat \"$tmp/window.xml\"".into()]
}
pub(super) fn prepare_action(
    call: &ToolCall,
    ctx: &Context,
    target: &Target,
    action: Action,
) -> Result<Operation, String> {
    if let Action::Screenshot { max_width, quality } = action {
        if let Some(generation) = target.display_generation {
            let expected = virtual_screen::status()
                .identity
                .filter(|i| i.generation == generation && i.display_id == target.display_id)
                .ok_or("副屏已变更")?;
            return Ok(Operation::VirtualScreenshot {
                expected,
                width: max_width,
                quality,
            });
        }
        if can_accessibility(ctx, target) && platform::automation::capabilities().sdk_int >= 30 {
            return Ok(Operation::Accessibility(Action::Screenshot {
                max_width,
                quality,
            }));
        }
        return Ok(Operation::Privileged {
            request: request(
                ctx,
                call,
                vec![
                    "/system/bin/sh".into(),
                    "-c".into(),
                    "set -o pipefail; /system/bin/screencap -p | /system/bin/base64".into(),
                ],
                16 * 1024 * 1024,
            )?,
            result: ProcessResult::Png {
                width: max_width,
                quality,
            },
        });
    }
    if matches!(
        action,
        Action::Global {
            action: GlobalAction::Notifications
        }
    ) && target.display_id != 0
    {
        return Err("副屏没有独立通知栏，未在主屏展开通知".into());
    }
    if can_accessibility(ctx, target)
        && !matches!(action,Action::Global{..} if target.display_id!=0)
    {
        return Ok(Operation::Accessibility(action));
    }
    let (argv, result) = match action {
        Action::Dump { max_nodes } => {
            if target.display_id != 0 {
                return Err("副屏元素读取需要无障碍，未用主屏uiautomator代替".into());
            }
            (xml_dump_argv(), ProcessResult::Dump(max_nodes))
        }
        Action::ClickText { text, exact } => {
            if target.display_id != 0 {
                return Err("副屏文字定位需要无障碍，未点击主屏匹配项".into());
            }
            (xml_dump_argv(), ProcessResult::Click { text, exact })
        }
        Action::Tap { x, y, duration_ms } => {
            let values = if duration_ms <= 100 {
                vec!["tap".into(), (x as u32).to_string(), (y as u32).to_string()]
            } else {
                vec![
                    "swipe".into(),
                    (x as u32).to_string(),
                    (y as u32).to_string(),
                    (x as u32).to_string(),
                    (y as u32).to_string(),
                    duration_ms.to_string(),
                ]
            };
            (input(target, values), ProcessResult::Accepted)
        }
        Action::Swipe {
            x1,
            y1,
            x2,
            y2,
            duration_ms,
        } => (
            input(
                target,
                vec![
                    "swipe".into(),
                    (x1 as u32).to_string(),
                    (y1 as u32).to_string(),
                    (x2 as u32).to_string(),
                    (y2 as u32).to_string(),
                    duration_ms.to_string(),
                ],
            ),
            ProcessResult::Accepted,
        ),
        Action::Type { text, append } => {
            if append {
                return Err("固定特权文字路线不能读取完整原字段，未忽略append请求".into());
            }
            if text.bytes().any(|c| !(32..=126).contains(&c))
                || text.contains("%s")
                || text.len() > 64 * 1024
            {
                return Err(
                    "固定特权文字路线仅支持可忠实表示的可打印ASCII；请启用无障碍输入Unicode".into(),
                );
            }
            (
                input(target, vec!["text".into(), text.replace(' ', "%s")]),
                ProcessResult::TextAccepted,
            )
        }
        Action::TypeInto { .. } => {
            return Err("ui_type_into需要无障碍定位，未改写其它输入框".into());
        }
        Action::Global { action } => {
            let argv = match action {
                GlobalAction::Notifications => vec![
                    "/system/bin/cmd".into(),
                    "statusbar".into(),
                    "expand-notifications".into(),
                ],
                other => input(
                    target,
                    vec![
                        "keyevent".into(),
                        match other {
                            GlobalAction::Back => 4,
                            GlobalAction::Home => 3,
                            _ => 187,
                        }
                        .to_string(),
                    ],
                ),
            };
            (argv, ProcessResult::Accepted)
        }
        Action::CurrentApp => {
            if target.display_id != 0 {
                return Err("副屏前台识别需要无障碍".into());
            }
            (
                vec![
                    "/system/bin/dumpsys".into(),
                    "window".into(),
                    "displays".into(),
                ],
                ProcessResult::CurrentApp,
            )
        }
        Action::Screenshot { .. } => return Err("截图准备状态不一致".into()),
    };
    let maximum = if matches!(
        result,
        ProcessResult::Dump(_) | ProcessResult::Click { .. } | ProcessResult::CurrentApp
    ) {
        512 * 1024
    } else {
        32 * 1024
    };
    Ok(Operation::Privileged {
        request: request(ctx, call, argv, maximum)?,
        result,
    })
}
pub(super) fn prepare_launch(
    call: &ToolCall,
    ctx: &Context,
    target: &Target,
    app: platform::applications::Application,
) -> Result<Operation, String> {
    if target.display_id == 0 || app.package == platform::applications::own_package()? {
        return Ok(Operation::Launch(app));
    }
    let request = request(
        ctx,
        call,
        vec![
            "/system/bin/am".into(),
            "start".into(),
            "--display".into(),
            target.display_id.to_string(),
            "-n".into(),
            app.component,
        ],
        32 * 1024,
    )?;
    if !matches!(request.route, Route::Root { .. }) {
        return Err("私有副屏启动第三方应用需要固定Root路线，未采用shell身份或主屏".into());
    }
    Ok(Operation::Privileged {
        request,
        result: ProcessResult::Accepted,
    })
}
pub(super) fn prepare_virtual(
    call: &ToolCall,
    args: &Map<String, Value>,
    ctx: &Context,
    target: &mut Option<Target>,
) -> Result<Operation, String> {
    let action = text(args, "action", 32, false)?;
    if action == "status" {
        return Ok(Operation::VirtualStatus);
    }
    let state = virtual_screen::status();
    let expected = state.identity;
    if action == "open" {
        return Ok(Operation::Virtual {
            expected: expected.clone(),
            action: virtual_screen::Action::OpenChecked {
                preview: boolean(args, "preview", true)?,
                expected,
            },
        });
    }
    let expected = expected.ok_or("虚拟屏未开启")?;
    if args.contains_key("display_id")
        && number(args, "display_id", 0, 1, i32::MAX as u64)? != expected.display_id as u64
    {
        return Err("display_id与当前副屏不一致".into());
    }
    if action != "close" {
        virtual_screen::verify(&expected)?;
    }
    *target = Some(if action == "close" {
        Target {
            display_id: expected.display_id,
            width: expected.width,
            height: expected.height,
            service_generation: platform::automation::capabilities().service_generation,
            display_generation: Some(expected.generation),
        }
    } else {
        platform::automation::target(expected.display_id)?
    });
    match action.as_str() {
        "close" => Ok(Operation::Virtual {
            expected: Some(expected.clone()),
            action: virtual_screen::Action::Close { expected },
        }),
        "preview" => {
            if !args.contains_key("visible") {
                return Err("preview缺少visible".into());
            }
            Ok(Operation::Virtual {
                expected: Some(expected.clone()),
                action: virtual_screen::Action::Preview {
                    expected,
                    visible: boolean(args, "visible", false)?,
                },
            })
        }
        "screenshot" => Ok(Operation::VirtualScreenshot {
            expected,
            width: number(args, "max_width", 1080, 320, 2160)? as u32,
            quality: number(args, "quality", 72, 1, 100)? as u8,
        }),
        "open_app" => prepare_launch(
            call,
            ctx,
            target.as_ref().ok_or("副屏目标未固定")?,
            platform::applications::resolve(&text(args, "target", 512, false)?)?,
        ),
        _ => Err("虚拟屏动作未实现".into()),
    }
}
fn identity(i: &Identity) -> Value {
    json!({"display_id":i.display_id,"generation":i.generation,"width":i.width,"height":i.height,"density":i.density})
}
pub(super) fn virtual_status(status: &virtual_screen::Status) -> Value {
    json!({"ok":true,"identity":status.identity.as_ref().map(identity),"closing":status.closing,"pending_operations":status.pending_operations,
        "has_frame":status.has_frame,"last_frame_at_ms":status.last_frame_at_ms,"preview_requested":status.preview_requested,
        "preview_window_owned":status.preview_window_owned,"preview_first_draw_received":status.preview_first_draw_received,"warning":status.error})
}
type Payload = (Value, Option<Image>, Option<OperationKey>);
pub(super) fn execute(
    prepared: &Prepared,
    operation: OperationKey,
    cancel: Arc<CancelToken>,
) -> Result<Payload, String> {
    if cancel.is_cancelled() {
        return Err("平台工具已取消，未派发新动作".into());
    }
    if let Some(target) = &prepared.target {
        // Closing a resource that already entered cleanup remains available.
        if !matches!(
            prepared.operation,
            Operation::Virtual {
                action: virtual_screen::Action::Close { .. },
                ..
            }
        ) {
            platform::automation::verify_target(target)?;
        }
    }
    match &prepared.operation {
        Operation::DeviceInfo => Ok((platform::system::device_info(&cancel)?, None, None)),
        Operation::OpenUrl(url) => {
            platform::system::open_url(&operation, url, cancel)?;
            Ok((
                json!({"ok":true,"launch_request_accepted":true,"page_loaded_verified":false}),
                None,
                None,
            ))
        }
        Operation::Accessibility(action) => {
            let target = prepared.target.clone().ok_or("工具没有固定目标")?;
            let result = platform::automation::execute(operation, target, action.clone(), cancel)?;
            Ok(match result {
                ActionResult::Dump(d) => (
                    json!({"ok":true,"text":d.text,"nodes":d.nodes,"visits":d.visits,"incomplete":d.incomplete,"elapsed_ms":d.elapsed_ms}),
                    None,
                    None,
                ),
                ActionResult::CurrentApp(package) => {
                    (json!({"ok":true,"package":package}), None, None)
                }
                ActionResult::Completed { description } => (
                    json!({"ok":true,"accepted":true,"gesture_completion_observed":matches!(action,Action::Tap{..}|Action::Swipe{..}),"message":description}),
                    None,
                    None,
                ),
                ActionResult::TextWritten {
                    readback_verified,
                    description,
                } => (
                    json!({"ok":true,"accepted":true,"readback_verified":readback_verified,"message":description}),
                    None,
                    None,
                ),
                ActionResult::Screenshot(shot) => (
                    json!({"ok":true,"display_id":shot.target.display_id,"generation":shot.target.display_generation}),
                    Some(Image {
                        jpeg: shot.jpeg,
                        width: shot.width,
                        height: shot.height,
                    }),
                    None,
                ),
            })
        }
        Operation::Virtual { expected, action } => {
            if virtual_screen::status().identity != *expected {
                return Err("虚拟屏在审批后变更，未派发操作".into());
            }
            virtual_screen::request(operation.clone(), action.clone())?;
            Ok((json!({"pending":true}), None, Some(operation)))
        }
        Operation::VirtualStatus => Ok((virtual_status(&virtual_screen::status()), None, None)),
        Operation::VirtualScreenshot {
            expected,
            width,
            quality,
        } => {
            let shot = virtual_screen::screenshot(expected, *width, *quality, &cancel)?;
            Ok((
                json!({"ok":true,"identity":identity(&shot.identity),"received_at_ms":shot.received_at_ms}),
                Some(Image {
                    jpeg: shot.jpeg,
                    width: shot.width,
                    height: shot.height,
                }),
                None,
            ))
        }
        Operation::Clipboard(action) => {
            let result = platform::clipboard::execute(&operation, action.clone(), cancel)?;
            Ok((
                json!({"ok":true,"text":result.text,"write_accepted":result.write_accepted}),
                None,
                None,
            ))
        }
        Operation::Applications { query, limit } => Ok((
            json!({"ok":true,"applications":platform::applications::list(query,*limit)?}),
            None,
            None,
        )),
        Operation::Launch(app) => {
            platform::applications::launch(
                &operation,
                prepared.target.clone().ok_or("应用启动目标未固定")?,
                app.clone(),
                cancel,
            )?;
            Ok((
                json!({"ok":true,"launch_request_accepted":true,"placement_verified":false,"component":app.component}),
                None,
                None,
            ))
        }
        Operation::Saved {
            reference,
            width,
            quality,
        } => {
            let bytes = prepared
                .screenshots
                .as_ref()
                .ok_or("历史截图存储未接入")?
                .read(reference, 12 * 1024 * 1024)?;
            use sha2::Digest;
            if format!("{:x}", sha2::Sha256::digest(&bytes)) != reference.sha256 {
                return Err("历史截图修订已变更".into());
            }
            let shot = platform::attachments::normalize_capture(&bytes, None, *width, *quality)?;
            if cancel.is_cancelled() {
                return Err("历史截屏读取已取消".into());
            }
            Ok((
                json!({"ok":true,"saved_reference":reference.reference}),
                Some(Image {
                    jpeg: shot.jpeg,
                    width: shot.width,
                    height: shot.height,
                }),
                None,
            ))
        }
        Operation::Wait(ms) => {
            let deadline = Instant::now() + Duration::from_millis(*ms);
            while Instant::now() < deadline {
                if cancel.is_cancelled() {
                    return Err("界面等待已取消".into());
                }
                std::thread::park_timeout(Duration::from_millis(10));
            }
            Ok((json!({"ok":true,"waited_ms":ms}), None, None))
        }
        Operation::Privileged { request, result } => process(prepared, request, result, cancel),
    }
}
fn run_request(
    prepared: &Prepared,
    request: &FrozenRequest,
    cancel: Arc<CancelToken>,
) -> Result<crate::shell_runner::Outcome, String> {
    if cancel.is_cancelled() {
        return Err("特权操作已取消，未创建进程".into());
    }
    if RELAYS
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            (n < 16).then_some(n + 1)
        })
        .is_err()
    {
        return Err("取消转接工作槽达到16个预算".into());
    }
    let flag = Arc::new(AtomicBool::new(false));
    let done = Arc::new(AtomicBool::new(false));
    let stop = done.clone();
    let relay = flag.clone();
    let worker = std::thread::Builder::new()
        .name("rust-ui-cancel-relay".into())
        .spawn(move || {
            struct Permit;
            impl Drop for Permit {
                fn drop(&mut self) {
                    RELAYS.fetch_sub(1, Ordering::AcqRel);
                }
            }
            let _permit = Permit;
            while !stop.load(Ordering::Acquire) {
                if cancel.is_cancelled() {
                    relay.store(true, Ordering::Release);
                    break;
                }
                std::thread::park_timeout(Duration::from_millis(10));
            }
        });
    if worker.is_err() {
        RELAYS.fetch_sub(1, Ordering::AcqRel);
        return Err("取消转接线程无法创建".into());
    }
    struct Stop(Arc<AtomicBool>);
    impl Drop for Stop {
        fn drop(&mut self) {
            self.0.store(true, Ordering::Release);
        }
    }
    let _stop = Stop(done);
    if let Some(target) = &prepared.target {
        platform::automation::verify_target(target)?;
    }
    let executor = prepared.privileged.as_ref().ok_or("固定特权适配未接入")?;
    let _feedback = platform::input_feedback::begin(request, prepared.target.as_ref());
    let result = executor.execute(request, flag);
    let same_route = match (&result.route, &request.route) {
        (Route::AppUid, Route::AppUid) => true,
        (Route::Root { generation: a }, Route::Root { generation: b })
        | (Route::Shizuku { generation: a }, Route::Shizuku { generation: b }) => a == b,
        _ => false,
    };
    if !same_route
        || !result.identity_confirmed
        || result.exit_code != Some(0)
        || result.cancel_requested
        || result.timed_out
        || !result.primary_exit_confirmed
        || result.error.is_some()
    {
        return Err("固定特权操作未确认成功；可能已有作用，未切换其它身份或屏幕".into());
    }
    if let Some(target) = &prepared.target {
        platform::automation::verify_target(target)?;
    }
    Ok(result)
}
fn process(
    prepared: &Prepared,
    request: &FrozenRequest,
    result: &ProcessResult,
    cancel: Arc<CancelToken>,
) -> Result<Payload, String> {
    let output = run_request(prepared, request, cancel.clone())?;
    if output.truncated
        && !matches!(
            result,
            ProcessResult::Accepted | ProcessResult::TextAccepted
        )
    {
        return Err("读取结果被截断或未完整排空，未把部分输出当完整画面".into());
    }
    let target = prepared.target.as_ref().ok_or("特权UI目标未固定")?;
    match result {
        ProcessResult::Accepted => {
            if output
                .stdout
                .lines()
                .chain(output.stderr.lines())
                .any(|s| s.trim_start().starts_with("Error"))
            {
                return Err("系统工具拒绝操作，未改投其它屏幕".into());
            }
            Ok((
                json!({"ok":true,"accepted":true,"effect_verified":false,"display_id":target.display_id,"route":format!("{:?}",request.route),"group_cleanup_confirmed":output.group_cleanup_confirmed}),
                None,
                None,
            ))
        }
        ProcessResult::TextAccepted => Ok((
            json!({"ok":true,"accepted":true,"readback_verified":false,"message":"固定特权路线已接受字符输入，字段原文未核对，请读屏确认，勿重复追加"}),
            None,
            None,
        )),
        ProcessResult::Dump(max) => {
            let tree = ui_tree::parse(&output.stdout)?;
            Ok((tree.dump(*max), None, None))
        }
        ProcessResult::Click { text, exact } => {
            let tree = ui_tree::parse(&output.stdout)?;
            let (x, y) = tree.find(text, *exact)?;
            if x >= target.width || y >= target.height {
                return Err("匹配元素坐标超出已批准屏幕，未点击".into());
            }
            let mut click = request.clone();
            click.argv = input(target, vec!["tap".into(), x.to_string(), y.to_string()]);
            click.max_output_bytes = 32 * 1024;
            let _ = run_request(prepared, &click, cancel)?;
            Ok((
                json!({"ok":true,"accepted":true,"effect_verified":false,"display_id":target.display_id}),
                None,
                None,
            ))
        }
        ProcessResult::CurrentApp => Ok((
            json!({"ok":true,"package":ui_tree::current_package(&output.stdout,target.display_id)?}),
            None,
            None,
        )),
        ProcessResult::Png { width, quality } => {
            let bytes = decode_base64(&output.stdout)?;
            if bytes.len() < 24 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" || &bytes[12..16] != b"IHDR"
            {
                return Err("固定截屏输出不是完整PNG".into());
            }
            let shot = platform::attachments::normalize_capture(
                &bytes,
                Some((target.width, target.height)),
                *width,
                *quality,
            )?;
            if cancel.is_cancelled() {
                return Err("截屏编码已取消，未发布图像".into());
            }
            platform::automation::verify_target(target)?;
            Ok((
                json!({"ok":true,"display_id":target.display_id,"route":format!("{:?}",request.route)}),
                Some(Image {
                    jpeg: shot.jpeg,
                    width: shot.width,
                    height: shot.height,
                }),
                None,
            ))
        }
    }
}
fn decode_base64(text: &str) -> Result<Vec<u8>, String> {
    if text.len() > 16 * 1024 * 1024 {
        return Err("截屏Base64超过16MiB".into());
    }
    let mut output = Vec::new();
    let mut block = [0u8; 4];
    let mut count = 0;
    let mut ended = false;
    for byte in text.bytes() {
        if byte.is_ascii_whitespace() {
            continue;
        }
        if ended {
            return Err("截屏Base64尾部含额外内容".into());
        }
        block[count] = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => 64,
            _ => return Err("截屏Base64字符无效".into()),
        };
        count += 1;
        if count == 4 {
            if block[0] >= 64 || block[1] >= 64 || block[2] == 64 && block[3] != 64 {
                return Err("截屏Base64填充无效".into());
            }
            output.push(block[0] << 2 | block[1] >> 4);
            if block[2] < 64 {
                output.push(block[1] << 4 | block[2] >> 2);
            }
            if block[3] < 64 {
                output.push(block[2] << 6 | block[3]);
            }
            ended = block[2] == 64 || block[3] == 64;
            count = 0;
            if output.len() > 12 * 1024 * 1024 {
                return Err("截屏压缩数据超过12MiB".into());
            }
        }
    }
    if count != 0 || output.is_empty() {
        return Err("截屏Base64不完整".into());
    }
    Ok(output)
}
