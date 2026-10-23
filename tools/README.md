# tools/

只放**验证与诊断**用的小工具，不参与 APK 构建（`app/` 之外，Gradle 不会打包它们）。

## `verify_device.sh`

真机验证一条龙。**它不改应用、不安装、不操作屏幕**，只读取与打印：

```bash
bash tools/verify_device.sh
```

三步：

1. **找设备** —— USB，或无线调试（脚本会跑 `adb mdns services` 列出可连的服务；
   `adb connect` 需要你自己执行，脚本**只打印不代劳**）；
2. **取 `pin.log`** —— 应用内「流式上翻痕迹」那一份（有 root 就走 `su -c cat`；没有就提示改用
   应用内「设置 → 性能 → 流式上翻痕迹 → 复制」）；
3. **打印帧数据的取法与判读要点** —— `gfxinfo` 需要**用手指**滑动产生，脚本不代劳
   （`adb input swipe` 测不出卡顿）。

输出都落在 `verify-out/`（`pin.log` / `pin.err`），可以直接贴进 issue。

## 这份 `pin.log` 能回答什么

| 痕迹 | 含义 |
|---|---|
| `arm h=… status=… top=…` 出现且之后 `release … drift=0` | 钉住期间**没动** ⇒ 你看到的"动"发生在解除之后 |
| `drift` 明显非 0，且期间有重试/错误提示 | 很可能是"状态行比气泡还高"那个已知例外 |
| `drift` 明显非 0，且没有错误提示 | **真问题**，说明还有别的东西在推 |
| 完全没有 `arm` 行 | 钉住没生效 ⇒ 看 `arm-timeout` 里的 `natural=` |
