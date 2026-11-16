//! Transcript selection and grouping. Payloads remain owned by Kotlin.
use crate::{Input, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::collections::HashMap;
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    ptr, slice,
};

struct Message {
    id: u32,
    run: u32,
    role: u32,
    flags: u32,
}
struct Row {
    first: usize,
    owner: usize,
    previous: Option<usize>,
    branch: Option<usize>,
    events: Vec<(u32, usize)>,
    render: bool,
}
fn integer(out: &mut Vec<u8>, value: usize) {
    out.extend_from_slice(&(value as u32).to_le_bytes());
}
fn plan(bytes: &[u8]) -> Result<Vec<u8>, i32> {
    let mut input = Input { bytes, at: 0 };
    let count = input.u32().ok_or(STATUS_REQUEST_ERROR)? as usize;
    let running = input.u32().ok_or(STATUS_REQUEST_ERROR)? != 0;
    let has_pending = input.u32().ok_or(STATUS_REQUEST_ERROR)? != 0;
    let pending = input.u32().ok_or(STATUS_REQUEST_ERROR)?;
    let mut messages = Vec::with_capacity(count);
    for _ in 0..count {
        messages.push(Message {
            id: input.u32().ok_or(STATUS_REQUEST_ERROR)?,
            run: input.u32().ok_or(STATUS_REQUEST_ERROR)?,
            role: input.u32().ok_or(STATUS_REQUEST_ERROR)?,
            flags: input.u32().ok_or(STATUS_REQUEST_ERROR)?,
        });
    }
    let picks = input.u32().ok_or(STATUS_REQUEST_ERROR)?;
    let mut active = HashMap::new();
    for _ in 0..picks {
        active.insert(
            input.u32().ok_or(STATUS_REQUEST_ERROR)?,
            input.u32().ok_or(STATUS_REQUEST_ERROR)?,
        );
    }
    if input.at != bytes.len() {
        return Err(STATUS_REQUEST_ERROR);
    }
    let mut turns = HashMap::with_capacity(count);
    let mut current = 0;
    for message in &messages {
        if message.role == 1 && message.flags & 1 == 0 {
            current = message.id;
        }
        turns.insert(message.id, current);
    }
    let mut runs: HashMap<u32, Vec<u32>> = HashMap::new();
    for message in &messages {
        let turn = turns[&message.id];
        if message.role == 1 || turn == 0 {
            continue;
        }
        let list = runs.entry(turn).or_default();
        if !list.contains(&message.run) {
            list.push(message.run);
        }
    }
    let chosen: HashMap<_, _> = runs
        .iter()
        .map(|(&turn, list)| {
            let wanted = active.get(&turn).copied();
            let selected = wanted
                .filter(|value| list.contains(value) || has_pending && *value == pending)
                .unwrap_or_else(|| {
                    list.iter()
                        .rev()
                        .find(|run| **run != 0)
                        .copied()
                        .unwrap_or(list[list.len() - 1])
                });
            (turn, selected)
        })
        .collect();
    let shown: Vec<_> = messages
        .iter()
        .enumerate()
        .filter_map(|(index, message)| {
            let turn = turns[&message.id];
            (message.role == 1 || turn == 0 || chosen.get(&turn).copied() == Some(message.run))
                .then_some(index)
        })
        .collect();
    let last_question = shown
        .iter()
        .rev()
        .find(|&&i| messages[i].role == 1 && messages[i].flags & 1 == 0)
        .map(|&i| messages[i].id)
        .unwrap_or(0);
    let mut tails = HashMap::new();
    for &i in &shown {
        let m = &messages[i];
        let turn = turns[&m.id];
        if !(has_pending && m.run == pending) && m.role == 2 && m.flags & 2 != 0 && turn != 0 {
            tails.insert(turn, i);
        }
    }
    let branch_for = |i: usize| {
        let m = &messages[i];
        let turn = turns[&m.id];
        if has_pending && m.run == pending || turn == 0 || tails.get(&turn) != Some(&i) {
            return None;
        }
        let siblings = &runs[&turn];
        (siblings.len() >= 2 || turn == last_question && !running).then_some(i)
    };
    let mut rows: Vec<Row> = Vec::new();
    let mut previous = None;
    let mut open: Option<usize> = None;
    for &i in &shown {
        let m = &messages[i];
        let visible = m.flags & 2 != 0;
        let render = !(has_pending && m.run == pending);
        match m.role {
            3 => {
                if let Some(row) = open {
                    rows[row].events.push((2, i));
                } else {
                    rows.push(Row {
                        first: i,
                        owner: i,
                        previous,
                        branch: None,
                        events: vec![(2, i)],
                        render,
                    });
                    open = Some(rows.len() - 1);
                    previous = Some(i);
                }
            }
            2 if visible => {
                if let Some(row) = open.filter(|&row| messages[rows[row].first].run == m.run) {
                    rows[row].events.push((1, i));
                    rows[row].owner = i;
                    if let Some(branch) = branch_for(i) {
                        rows[row].branch = Some(branch);
                    }
                } else {
                    rows.push(Row {
                        first: i,
                        owner: i,
                        previous,
                        branch: branch_for(i),
                        events: vec![(1, i)],
                        render,
                    });
                    open = Some(rows.len() - 1);
                }
                previous = Some(i);
            }
            2 => {}
            1 if m.flags & 1 != 0 => {
                if let Some(row) = open {
                    rows[row].events.push((3, i));
                } else {
                    rows.push(Row {
                        first: i,
                        owner: i,
                        previous,
                        branch: None,
                        events: vec![(3, i)],
                        render,
                    });
                }
                previous = Some(i);
            }
            _ if visible => {
                rows.push(Row {
                    first: i,
                    owner: i,
                    previous,
                    branch: None,
                    events: Vec::new(),
                    render,
                });
                previous = Some(i);
                if m.role != 4 {
                    open = None;
                }
            }
            _ => {}
        }
    }
    let mut output = Vec::new();
    integer(&mut output, rows.iter().filter(|row| row.render).count());
    for row in rows.iter().filter(|row| row.render) {
        integer(&mut output, row.first);
        integer(&mut output, row.owner);
        integer(&mut output, row.previous.unwrap_or(usize::MAX));
        if let Some(index) = row.branch {
            let turn = turns[&messages[index].id];
            let siblings = &runs[&turn];
            integer(&mut output, 1);
            integer(&mut output, turn as usize);
            integer(
                &mut output,
                siblings
                    .iter()
                    .position(|&run| run == chosen[&turn])
                    .unwrap_or(0),
            );
            integer(&mut output, usize::from(turn == last_question && !running));
            integer(&mut output, siblings.len());
            for run in siblings {
                integer(&mut output, *run as usize);
            }
        } else {
            integer(&mut output, 0);
        }
        integer(&mut output, row.events.len());
        for &(kind, source) in &row.events {
            integer(&mut output, kind as usize);
            integer(&mut output, source);
        }
    }
    Ok(output)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_transcript_plan(
    bytes: *const u8,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() || bytes.is_null() || len < 16 {
        return STATUS_REQUEST_ERROR;
    }
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    match catch_unwind(AssertUnwindSafe(|| {
        plan(unsafe { slice::from_raw_parts(bytes, len) })
    })) {
        Ok(Ok(bytes)) => {
            let bytes = bytes.into_boxed_slice();
            let len = bytes.len();
            unsafe {
                output.write(SearchBuffer {
                    data: Box::into_raw(bytes).cast(),
                    len,
                });
            }
            0
        }
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_transcript_page(
    flags: *const u8,
    count: usize,
    want: i32,
    output: *mut i32,
) {
    let flags = if count == 0 {
        &[]
    } else {
        unsafe { slice::from_raw_parts(flags, count) }
    };
    let cap = want.max(1) as usize;
    let mut ring = vec![0; cap.min(count.max(1))];
    let mut total = 0usize;
    for (i, &flag) in flags.iter().enumerate() {
        if flag != 0 {
            let slot = total % ring.len();
            ring[slot] = i;
            total += 1;
        }
    }
    let drawn = (want.max(1) as usize).min(total);
    let first = total - drawn;
    let from = if first == 0 {
        0
    } else {
        ring[first % ring.len()]
    };
    unsafe {
        output.write(from as i32);
        output.add(1).write(total as i32);
        output.add(2).write(drawn as i32);
    }
}
