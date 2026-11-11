"""Prepare the pinned Android/Bionic GNU Bash and its shared libraries."""
import hashlib
import io
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen

BASE = "https://packages.termux.dev/apt/termux-main/"
PARTS = (
    ("b/bash", "5.3.20", "usr/bin/bash", "libbash.so"),
    ("r/readline", "8.3.6", "usr/lib/libreadline.so.8.3", "libreadline.so"),
    ("liba/libandroid-support", "29-1", "usr/lib/libandroid-support.so", "libandroid-support.so"),
    ("libi/libiconv", "1.19.1", "usr/lib/libiconv.so", "libiconv.so"),
    ("n/ncurses", "6.6.20260307+really6.5.20250830", "usr/lib/libncursesw.so.6.5", "libncursesw.so"),
)
ARCHITECTURES = {
    "aarch64": ("arm64-v8a", (
        "26b5b3ab5b0ac0e3b372ac1e7ddd89270519c1eb25b88fe4175485c2de8086b2",
        "616cc73009bc0223f61caed8dd09455714455aa5ea5e180126e2f69c4c775136",
        "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517",
        "a1c6323eff4f660e16ae6f5832356444b3269c19a4f61146355a49e7c4be5ed1",
        "f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99")),
    "arm": ("armeabi-v7a", (
        "ccd12672eb2d6f2c99d8c6340919b7145aa12a18d8a8ffbe794683227c889e61",
        "4b3bdacdbee97503a9062a81e5e460ff5c4f3b35fed4b1cb3a612c5b8c79e64f",
        "67150f1cb050d4852cb21d2b138676c68920b2ec7f059795b287b75cf2cf6cb6",
        "d0c21741ba47c89d8a86c23cf434376b116d4c0c27834cf35cbe4e35f7523132",
        "a729f77be4109f8123aeb59702e49bf2d30f67a213d305c276859af52e12a3bb")),
    "i686": ("x86", (
        "175d2de5b7502f49083338f501c6e411b70bbdd72e86d972eb54b25b96b6cb46",
        "137aa813623a11717f19b32c24fce0f2d016b12a440bc52ab8b5f3f7c55c6363",
        "95ceacac23ac78e3d5a366daa44d04a3540424d8b73d2aab5538b05f0df869d8",
        "69fa1a69ccdf1d3914298cdf263363b312392c56805dd52e089e8f58b6967a38",
        "e2f53b7d22e7d9980acc9ecebf5c52e5399572334f758f9890540d532bf0389d")),
    "x86_64": ("x86_64", (
        "f8c984e68269df3df2efae9f2835a9d9af381a8f80d08b34160772bc16cd0910",
        "e9684798d13ede05f785315f68d3ff739563db2abef0e73db4dc9b8e17ded368",
        "665900760c05959ec076082a0941f05bd452f301e5a70bdfa7383fd3404d44d5",
        "3eb0c454676ad24d26c79601721b2eb3f9485c611ad0179fb86b17229344f46d",
        "3f30f53c6a41c8450d146d4d42611a44be6ba0a8de1e8bb4dc903109a5fc3ff1")),
}


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


def relocate(data):
    prefix = b"/data/data/com.termux/files/usr/lib"
    for old, new in (
        (prefix + b":" + prefix + b"\0", b"$ORIGIN\0"),
        (prefix + b"\0", b"$ORIGIN\0"),
        (b"libreadline.so.8\0", b"libreadline.so\0"),
        (b"libncursesw.so.6\0", b"libncursesw.so\0"),
    ):
        # Fixed-size ELF string edits preserve offsets and machine code.
        data = data.replace(old, new + b"\0" * (len(old) - len(new)))
    return data


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    cache = root / "verify-out/android-bash-preparation"
    cache.mkdir(parents=True, exist_ok=True)
    for architecture, (abi, checksums) in ARCHITECTURES.items():
        destination = root / "app/src/main/jniLibs" / abi
        destination.mkdir(parents=True, exist_ok=True)
        for (part, version, source, filename), checksum in zip(PARTS, checksums):
            package_name = f"{part.split('/')[-1]}_{version}_{architecture}.deb"
            cached = cache / package_name
            if not cached.exists():
                request = Request(BASE + f"pool/main/{part}/{package_name}",
                                  headers={"User-Agent": "JAgent dependency preparation"})
                cached.write_bytes(urlopen(request, timeout=45).read())
            package = cached.read_bytes()
            assert hashlib.sha256(package).hexdigest() == checksum, f"Unexpected package: {package_name}"
            with data_archive(package) as archive:
                member = next(item for item in archive if item.isfile() and
                              item.name.removeprefix("./").removeprefix("data/data/com.termux/files/") == source)
                (destination / filename).write_bytes(relocate(archive.extractfile(member).read()))
            print(f"{abi}/{filename}")
