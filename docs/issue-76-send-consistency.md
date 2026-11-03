# Issue #76 实施记录：发送准备、排队恢复与斜杠目录一致性

> 版本：0.8.655 / vc676  
> 提交：`6d6f9cf`（已 push，`main == origin/main`）  
> 设备：`676 / 0.8.655`，`lastUpdateTime 2026-11-03 18:01:22`  
> 本文件是 #76 的必要交付文档，随 0.8.655 入库；其它内部 `docs/` 笔记仍只留本机。

## 1. 七项落点

| ID | 内容 | 落点 |
|---|---|---|
| F-03 | 发送准备期切会话丢消息 | `ChatViewModel.kt:1806` 捕获 `snapshot`；`:1869-1874` 读 wire 后无 `conversationId != chatId` 提前返回，直接以原 `chatId` 调四参 `start`（`:2540`）；`:1846` 长会话先 `save(...)` 再读 |
| F-06 | PREPARING 无 per-chat 互斥 | `ChatViewModel.kt:1783` `sendGate.tryBegin`，busy 同时看 `engine.isRunning` 与 `sessions` |
| F-01 | 排队项“修改”先删后恢复 | `ChatComposer` `onEdit` 只传 `(PendingSteer, nextTurn)`；`ChatScreen.kt:243` 解码、`:262` 切会话检查、`:266-267` 读最新草稿并提交；`:1889-1891` 成功后才 retract |
| F-04 | 权限预检用错端点 | `ChatViewModel.kt:1767` `effectiveBaseUrl(settingsNow, _state.value)`；`ChatSendPolicy.kt:96` 与 `start()` 的 `choiceFor` 同链 |
| F-02 | 发送被拒仍追底 | `ChatScreen.kt:1951` `shouldJumpToLatest(accepted, body)`；`ChatSendPolicy.kt:110` |
| F-07 | 文件路径动作被拒静默 | `ChatScreen.kt:2056` 起检查 `send`，运行中走 `steer`，否则 snackbar |
| F-05 | 斜杠候选不跟动态工具 | `Tool.kt:172` `catalogRevision`，`:176` 原子自增；`ChatScreen.kt:189` 收集并作为 remember 键 |

## 2. 纯函数与测试

- `ChatSendPolicy.kt`：`SendPreparationGate`、`sendHistory`、`acceptSend`、`rollbackSend`、`effectiveBaseUrl`、`shouldJumpToLatest`
- `ChatRestorePolicy.kt`：`mergeRestoredDraft`
- 新增 4 个测试类 / 19 项：
  - `ChatSendPolicyTest` 11
  - `ChatSendEndpointTest` 3
  - `ChatRestorePolicyTest` 3
  - `ToolCatalogRevisionTest` 2

## 3. 验证

```text
:app:testReleaseUnitTest --rerun-tasks
252 类 / 1460 项 / 0 失败 / 0 错误 / 0 跳过
```

日志：`verify-out/release-0.8.655-tests-rerun.log`

APK：`app/build/outputs/apk/release/app-release.apk`  
5,757,681 B，sha256 `fcbe34d164e7a801050d3968aa393874b50bcc985a09f2b926fb2b0830e5bee1`  
aapt2 `676 / 0.8.655`；`zipalign -c -P 16 4` 通过；`apksigner` V2 `CN=Lingshu` 通过。

装机：`adb -s 192.168.0.102:33049 install -r` Success；设备哈希与本机一致。

## 4. 边界

- 没有 ViewModel 级可控 `readWire` barrier 测试：`ChatViewModel` 直接引用 `Core` 单例，测试不可注入 repository。
- F-01 / F-02 / F-07 的 UI 行为没有真机手势/截图证据。
- F-05 的真实 MCP 热连接/断开未在设备验证。
- 因此 issue #76 保持 Open；上述项不写成“真机通过”。
