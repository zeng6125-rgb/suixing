# #61 / PR-0 — Native Android PTY compatibility probe

Scope: JNI + CMake + `TerminalNative` + device-only instrumentation test.
**No terminal UI, no agent-facing tool, no Shizuku/Root, no PRoot/Linux** in this stage.

## Build

```bash
./gradlew :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
# Device/emulator must be connected:
./gradlew :app:connectedDebugAndroidTest
```

## Device matrix — results must be collected, not inferred from compilation

- API 26, 29, 33, 35 and 37 where available; arm64 device and emulator.
- Verify `test -t 0` from the child is true.
- Verify `echo`, UTF-8 Chinese echo, `Ctrl-C` during a foreground `sleep`, and prompt recovery.
- Verify `TIOCSWINSZ` with `stty size` when the command is available; otherwise run an ioctl readback probe.
- Verify `exit 7` returns status 7; closing 30 sessions leaves no zombies/FD increase.
- Document Android W^X failures for running a downloaded executable from app-writable filesDir. Do not lower target SDK.
- Record device, ROM, API, ABI, exact commit, logs and whether test was actually run.

## Ownership/lifecycle risks to resolve before PR-1

1. This test-only JNI bridge returns `[masterFd,pid]` and does not yet manage read/write/wait lifetimes. **Do not expose it to UI or tools** until TerminalSessionManager guarantees single reader/owner, serialized terminate & wait, and proper timeout / SIGKILL escalation.
2. Child calls only `chdir`, `execve` or `_exit` after `forkpty`: no JNI or managed/locking APIs inside the forked Android ART process.
3. `nativeRead` is bounded (poll timeout ≤10s, chunk ≤16KiB); not a VT renderer. TextView-only terminals are NOT a substitute for VT/ANSI emulation.
4. This PR uses /system/bin/sh, App UID, a minimal environment without app secrets; packaged libbash can be evaluated later. Its control sequences, interpreter path and local file access must be reviewed before any Agent approval.
5. The native subprocess may be disallowed or behave differently under OEM seccomp/SELinux; support must be proven by real-device runs before implementing Linux/PRoot.
