# 随包分发的第三方组件（GPL 合规声明）

本应用（随行 / suixing）随 APK 分发两个**静态编译的 AArch64 二进制**，用于在没有 root 的情况下
也能执行完整的 shell 命令。它们不是运行时下载的，就在 APK 里。

| APK 内路径 | 组件 | 版本 | 许可证 | 上游源码 |
|---|---|---|---|---|
| `lib/arm64-v8a/libbash.so` | GNU Bash | 5.3 | GPL-3.0-or-later | https://salsa.debian.org/debian/bash |
| `lib/arm64-v8a/libbusybox.so` | BusyBox | 1.38.0 | GPL-2.0-only | https://salsa.debian.org/debian/busybox |

仓库里对应的文件就是这两个二进制的**原样拷贝**：

- `app/src/main/jniLibs/arm64-v8a/libbash.so`
- `app/src/main/jniLibs/arm64-v8a/libbusybox.so`

## 来源与校验

两者取自 Debian 官方仓库的静态构建包（`bash-static`、`busybox-static`，arm64）：

| 文件 | SHA-256 |
|---|---|
| `bash-static_5.3-4_arm64.deb` | `5FA20B66B866E2C24C82081A47773104FDA049A8110AE9D18300EC4F0BBC33CC` |
| `busybox-static_1.38.0-3+b1_arm64.deb` | `968D1AA8F579FA1AC59C26AFA365454369E13CF29848E6400B50028FED0FFDA0` |
| 取出的 `usr/bin/bash-static` → `libbash.so` | `8C82B408892374D1F1C33BE5990DCC2E6C752DBDF104CEC53D0F999ED05F37A9` |
| 取出的 `usr/bin/busybox` → `libbusybox.so` | `F10EC6F1C3B41B6015CC0F15C11A4CE4E06FFF158E20BDBE9A527A3ED3EA752A` |

复现步骤（在任意 Linux/Windows + `tar` 环境）：

```sh
curl -O https://deb.debian.org/debian/pool/main/b/bash/bash-static_5.3-4_arm64.deb
curl -O https://deb.debian.org/debian/pool/main/b/busybox/busybox-static_1.38.0-3+b1_arm64.deb
mkdir bash_x bb_x && tar -xf bash-static_5.3-4_arm64.deb -C bash_x && tar -xf busybox-static_1.38.0-3+b1_arm64.deb -C bb_x
(cd bash_x && tar -xf data.tar.xz) && (cd bb_x && tar -xf data.tar.xz)
cp bash_x/usr/bin/bash-static app/src/main/jniLibs/arm64-v8a/libbash.so
cp bb_x/usr/bin/busybox     app/src/main/jniLibs/arm64-v8a/libbusybox.so
```

两个文件都是 ELF64 AArch64 且**完全静态**（无 `PT_DYNAMIC`、无 `PT_INTERP`），可用
`readelf -l` 复核。

## 为什么放在 `jniLibs` 而不是 assets

Android 10 起，`targetSdk >= 29` 的应用**不允许执行**自己数据目录里的文件（W^X 限制）；
唯一允许执行自身文件的目录是 `nativeLibraryDir`。所以这两个二进制以 `lib*.so` 的名字放进
`jniLibs/arm64-v8a/`，并配合 `app/build.gradle.kts` 里的：

```kotlin
packaging { jniLibs { useLegacyPackaging = true } }
```

把它们**解压到磁盘**（AGP 默认把 `.so` 留在 APK 里靠 mmap 加载，那样 `execve` 拿不到路径）。

## 源码提供

GPL 要求分发二进制时提供对应源码的获取途径。上面的上游链接指向 Debian 的源码仓库；
**如需书面提供源码**，请在本仓库开 issue 说明，我们会提供源码包或其获取方式。

本应用自身的代码以 MIT 许可发布（见 `LICENSE`）；上表中的两个组件仍分别适用其 GPL 许可。
