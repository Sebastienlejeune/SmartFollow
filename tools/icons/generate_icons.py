"""Generate the PNG icons of the server web page (server/src/main/resources/web).

iOS needs PNG icons to add the page to the home screen. The icon shows the same white drop
as the Android launcher icon, on a full bright green square. Written with the standard library only.

Usage: python tools/icons/generate_icons.py
"""

import struct
import zlib
from pathlib import Path

OUTPUT_DIR = Path(__file__).resolve().parents[2] / "server" / "src" / "main" / "resources" / "web"
GREEN = (0x5D, 0xBB, 0x2E)
WHITE = (0xFF, 0xFF, 0xFF)
SUPERSAMPLING = 4


def inside_drop(x: float, y: float) -> bool:
    """Drop shape in unit coordinates: a circle with a point on top."""
    cx, cy, radius, top = 0.5, 0.58, 0.21, 0.18
    if (x - cx) ** 2 + (y - cy) ** 2 <= radius**2:
        return True
    return top <= y <= cy and abs(x - cx) <= radius * (y - top) / (cy - top)


def pixel(px: int, py: int, size: int) -> tuple[int, int, int, int]:
    # Full-bleed green background: iOS rounds the corners itself and turns transparency black.
    white = 0
    for sy in range(SUPERSAMPLING):
        for sx in range(SUPERSAMPLING):
            x = (px + (sx + 0.5) / SUPERSAMPLING) / size
            y = (py + (sy + 0.5) / SUPERSAMPLING) / size
            white += inside_drop(x, y)
    samples = SUPERSAMPLING**2
    rgb = tuple(round((GREEN[i] * (samples - white) + WHITE[i] * white) / samples) for i in range(3))
    return (*rgb, 255)


def write_png(path: Path, size: int) -> None:
    rows = b"".join(
        b"\x00" + b"".join(struct.pack("BBBB", *pixel(x, y, size)) for x in range(size)) for y in range(size)
    )

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")
    path.write_bytes(png)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for size in (180, 512):
        path = OUTPUT_DIR / f"icon-{size}.png"
        write_png(path, size)
        print(f"{path.name}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
