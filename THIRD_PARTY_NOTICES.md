# 随包分发的第三方组件

本应用（JAgent / suixing）随 APK 分发以下 shell、终端和可选 Linux 运行组件。各组件保留自己的许可证；许可证材料同时随 APK 放在 `assets/licenses/`。Linux rootfs 不随 APK 分发，只在用户选择安装后下载。

## 随包 shell

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

## 终端仿真与视口

- 组件：Termux `terminal-emulator`、`terminal-view` 的部分 Java 源码及选区资源，版本 v0.118.3。
- 固定源码： https://github.com/termux/termux-app/tree/5b657c6adf4304e5198951ce815fe0205dcac29c
- 上游根许可明确将 `terminal-emulator`、`terminal-view` 列为 Apache-2.0 例外，其余 Termux app 的 GPL 许可不套用到这两个模块： https://github.com/termux/termux-app/blob/5b657c6adf4304e5198951ce815fe0205dcac29c/LICENSE.md
- 源文件原有版权与 Apache-2.0 头保留；包括 Jack Palevich 及贡献者的 Android Terminal Emulator 代码。许可和来源在 `assets/licenses/termux-terminal/`。
- 本项目的修改：保留本项目 PTY、进程与会话生命周期，使用自有 `TerminalSession` 适配器；调整资源包路径；禁用终端输出触发的 OSC 52 剪贴板读写；增加保留输入模式的清屏/历史入口；将视口与输入桥接到现有页面。未导入 Termux app、原 JNI 或其进程管理实现。

## PRoot、talloc 与 Android shared memory

这些 ARM64 二进制仅用于用户主动选择的 Linux 环境。PRoot 以独立进程执行；rootfs 中的各个软件分别适用自身许可。

| APK 内路径 | 组件 | 版本 | 许可证 | 对应上游源码 |
|---|---|---|---|---|
| `lib/arm64-v8a/libproot.so`、`libproot-loader.so`、`libproot-loader32.so` | Termux PRoot | 5.1.107.102 | GPL-2.0-or-later | https://github.com/termux/proot/tree/v5.1.107.102 |
| `lib/arm64-v8a/libtalloc.so` | talloc | 2.5.0 | LGPL-3.0-or-later | https://download.samba.org/pub/talloc/talloc-2.5.0.tar.gz |
| `lib/arm64-v8a/libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem/tree/v0.7 |

包来源为 Termux 官方 `https://packages.termux.dev/apt/termux-main/`。固定包与校验值：

| 包 | SHA-256 |
|---|---|
| `proot_5.1.107.102_aarch64.deb` | `10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030` |
| `libtalloc_2.5.0_aarch64.deb` | `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7` |
| `libandroid-shmem_0.7_aarch64.deb` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` |

`tools/fetch-terminal-binaries.py` 可从上述固定包重现提取过程。Android 提取的文件须名为 `lib*.so`，因此 PRoot 的 `DT_NEEDED` 和 talloc 的 `SONAME` 中 `libtalloc.so.2` 被等长替换为 `libtalloc.so`（尾部补 NUL，保留 ELF 偏移）；未改机器代码。其它三个文件只改打包文件名，字节不变。原 Termux 打包配方见 https://github.com/termux/termux-packages/tree/master/packages 。

PRoot 版权包括 STMicroelectronics 和上游贡献者；talloc 版权包括 Andrew Tridgell（2004–2005）、Stefan Metzmacher（2006）及贡献者；libandroid-shmem 版权为 Sergii Pylypenko（2013）、Fredrik Fornwall（2017）。完整许可随包放在 `assets/licenses/proot/`、`assets/licenses/talloc/`、`assets/licenses/libandroid-shmem/`，talloc 同时包含 LGPL-3.0 与其引用的 GPL-3.0 全文。

## XZ for Java

`org.tukaani:xz:1.10` 用于解码可选 Debian rootfs 的 `.tar.xz`。上游： https://github.com/tukaani-project/xz-java/tree/v1.10 ，0BSD；原 `COPYING` 与许可全文随包位于 `assets/licenses/xz-java/`。

## 按需下载的 Linux 镜像

安装器固定以下来源和 SHA-256，完整性或本机启动检查失败时不启用环境：

| 镜像 | 固定来源 | SHA-256 |
|---|---|---|
| Alpine 3.22.2 aarch64 minirootfs | https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.2-aarch64.tar.gz | `6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489` |
| Debian 12 Bookworm aarch64，proot-distro v4.17.3 | https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz | `3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b` |

镜像内各软件的许可、版权和源码来源应依发行版包记录查询，不将整个镜像标为某一种许可证。Alpine 包信息与源码构建配方见 https://pkgs.alpinelinux.org/ 、 https://gitlab.alpinelinux.org/alpine/aports ；Debian 包与源码见 https://packages.debian.org/bookworm/ 、 https://sources.debian.org/ 。
