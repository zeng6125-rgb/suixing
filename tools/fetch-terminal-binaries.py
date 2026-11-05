"""Reproduce the ARM64 optional PRoot resources from pinned upstream Termux packages."""
from pathlib import Path
from urllib.request import Request, urlopen
import hashlib
import io
import tarfile

BASE = "https://packages.termux.dev/apt/termux-main/"
PACKAGES = (
    ("pool/main/p/proot/proot_5.1.107.102_aarch64.deb", "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030",
     {"usr/bin/proot": "libproot.so", "usr/libexec/proot/loader": "libproot-loader.so",
      "usr/libexec/proot/loader32": "libproot-loader32.so"}),
    ("pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb", "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7",
     {"usr/lib/libtalloc.so.2.5.0": "libtalloc.so"}),
    ("pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb", "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6",
     {"usr/lib/libandroid-shmem.so": "libandroid-shmem.so"}),
)

def data_archive(package):
    assert package[:8] == b"!<arch>\n"
    position = 8
    while position + 60 <= len(package):
        header = package[position:position + 60]
        size = int(header[48:58])
        name = header[:16].decode().strip().rstrip("/")
        body = package[position + 60:position + 60 + size]
        position += 60 + size + size % 2
        if name.startswith("data.tar"):
            return tarfile.open(fileobj=io.BytesIO(body))
    raise ValueError("Missing Debian data archive")

def install(package, checksum, files, destination):
    assert hashlib.sha256(package).hexdigest() == checksum, "Package SHA-256 mismatch"
    with data_archive(package) as archive:
        members = {member.name.removeprefix("./").removeprefix("data/data/com.termux/files/"): member
                   for member in archive if member.isfile()}
        for source, name in files.items():
            data = archive.extractfile(members[source]).read()
            # Android extracts lib*.so only. Preserve ELF offsets while renaming the
            # dynamic SONAME/DT_NEEDED (no code or loader addresses are changed).
            if name in ("libproot.so", "libtalloc.so"):
                data = data.replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
            (destination / name).write_bytes(data)
            print(name, hashlib.sha256(data).hexdigest())

if __name__ == "__main__":
    destination = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    for filename, checksum, files in PACKAGES:
        request = Request(BASE + filename, headers={"User-Agent": "Suixing dependency preparation"})
        package = urlopen(request, timeout=60).read()
        install(package, checksum, files, destination)
