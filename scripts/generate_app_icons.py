#!/usr/bin/env python3
"""Regenerate QuotaDog desktop icons (PNG / ICNS / ICO).

The mark is the remaining-quota moon used across the app. macOS Dock icons set
through Java's `-Xdock:icon` are drawn as the source bitmap with no system
squircle mask, so this script bakes a transparent superellipse plate into the
desktop assets. iOS / Android launcher icons are left alone: those platforms
apply their own masks.
"""

from __future__ import annotations

import io
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ICONS_DIR = ROOT / "composeApp" / "icons"
WINDOW_ICON = (
    ROOT
    / "composeApp"
    / "src"
    / "commonMain"
    / "composeResources"
    / "drawable"
    / "quotadog_app_icon.png"
)

# Match the existing launcher / tray moon.
PLATE = (245, 245, 245, 255)
REMAIN = (17, 17, 17, 255)
CRESCENT = (184, 184, 184, 255)
EDGE = (0, 0, 0, 28)

MASTER_SIZE = 1024
SUPERSAMPLE = 4
SQUIRCLE_N = 5.0
# Keep a 1px ring of transparent pixels so LANCZOS never samples a clipped edge.
PLATE_INSET = 4.0
# Old square export used a ~72% moon. The same mark on a squircle reads larger
# because the gray corners are gone; 60% restores typical macOS icon padding.
MOON_DIAMETER_RATIO = 0.60


def _grid(size: int) -> tuple[np.ndarray, np.ndarray]:
    yy, xx = np.mgrid[0:size, 0:size]
    return xx.astype(np.float64) + 0.5, yy.astype(np.float64) + 0.5


def squircle_distance(size: int, n: float, inset: float) -> np.ndarray:
    xx, yy = _grid(size)
    center = size / 2.0
    radius = max(center - inset, 1.0)
    return np.abs((xx - center) / radius) ** n + np.abs((yy - center) / radius) ** n


def moon_masks(size: int, diameter_ratio: float) -> tuple[np.ndarray, np.ndarray]:
    xx, yy = _grid(size)
    center = size / 2.0
    radius = center * diameter_ratio
    # Same terminator as the AWT / AppKit menu-bar moon: left 80% remaining.
    ellipse_x = 0.6 * radius
    dx = xx - center
    dy = yy - center
    in_circle = dx * dx + dy * dy <= radius * radius
    in_ellipse = (dx * dx) / (ellipse_x * ellipse_x) + (dy * dy) / (radius * radius) <= 1.0
    remain = in_circle & ((dx <= 0.0) | in_ellipse)
    crescent = in_circle & ~remain
    return remain, crescent


def _downscale_mask(mask: np.ndarray, final_size: int) -> np.ndarray:
    image = Image.fromarray((mask.astype(np.uint8)) * 255, mode="L")
    return np.asarray(
        image.resize((final_size, final_size), Image.Resampling.LANCZOS),
        dtype=np.float64,
    ) / 255.0


def compose_desktop_icon(size: int = MASTER_SIZE) -> Image.Image:
    work = size * SUPERSAMPLE
    plate = _downscale_mask(squircle_distance(work, SQUIRCLE_N, PLATE_INSET * SUPERSAMPLE) <= 1.0, size)
    remain, crescent = moon_masks(work, MOON_DIAMETER_RATIO)
    remain_a = _downscale_mask(remain, size)
    crescent_a = _downscale_mask(crescent, size)

    rgba = np.zeros((size, size, 4), dtype=np.float64)
    for i, channel in enumerate(PLATE):
        rgba[:, :, i] = plate * channel

    for mask, color in ((crescent_a, CRESCENT), (remain_a, REMAIN)):
        src = np.empty_like(rgba)
        for i, channel in enumerate(color):
            src[:, :, i] = mask * channel
        out_a = src[:, :, 3] + rgba[:, :, 3] * (1.0 - src[:, :, 3] / 255.0)
        for i in range(3):
            rgba[:, :, i] = np.where(
                out_a == 0,
                0,
                (src[:, :, i] * src[:, :, 3] + rgba[:, :, i] * rgba[:, :, 3] * (1.0 - src[:, :, 3] / 255.0))
                / np.maximum(out_a, 1e-6),
            )
        rgba[:, :, 3] = out_a

    # Hairline so the pale plate still reads on a light Dock.
    dist = squircle_distance(size, SQUIRCLE_N, PLATE_INSET)
    edge = plate * np.clip((dist - 0.965) / 0.035, 0.0, 1.0)
    edge_src = np.zeros_like(rgba)
    for i, channel in enumerate(EDGE):
        edge_src[:, :, i] = edge * channel
    out_a = edge_src[:, :, 3] + rgba[:, :, 3] * (1.0 - edge_src[:, :, 3] / 255.0)
    for i in range(3):
        rgba[:, :, i] = np.where(
            out_a == 0,
            0,
            (
                edge_src[:, :, i] * edge_src[:, :, 3]
                + rgba[:, :, i] * rgba[:, :, 3] * (1.0 - edge_src[:, :, 3] / 255.0)
            )
            / np.maximum(out_a, 1e-6),
        )
    rgba[:, :, 3] = out_a

    return Image.fromarray(np.clip(np.rint(rgba), 0, 255).astype(np.uint8), mode="RGBA")


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def write_icns(path: Path, master: Image.Image) -> None:
    # PNG-based ICNS entries used by modern macOS (10.8+).
    sizes = {
        "ic11": 32,
        "ic12": 64,
        "ic07": 128,
        "ic13": 256,
        "ic08": 256,
        "ic14": 512,
        "ic09": 512,
        "ic10": 1024,
    }
    chunks: list[bytes] = []
    for ostype, side in sizes.items():
        data = png_bytes(master.resize((side, side), Image.Resampling.LANCZOS))
        chunks.append(ostype.encode("ascii") + struct.pack(">I", 8 + len(data)) + data)
    body = b"".join(chunks)
    path.write_bytes(b"icns" + struct.pack(">I", 8 + len(body)) + body)


def write_ico(path: Path, master: Image.Image) -> None:
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    master.save(path, format="ICO", sizes=sizes)


def main() -> int:
    ICONS_DIR.mkdir(parents=True, exist_ok=True)
    master = compose_desktop_icon()
    png_512 = master.resize((512, 512), Image.Resampling.LANCZOS)

    png_path = ICONS_DIR / "QuotaDog.png"
    icns_path = ICONS_DIR / "QuotaDog.icns"
    ico_path = ICONS_DIR / "QuotaDog.ico"
    master.save(png_path, format="PNG")
    write_icns(icns_path, master)
    write_ico(ico_path, master)
    WINDOW_ICON.parent.mkdir(parents=True, exist_ok=True)
    png_512.save(WINDOW_ICON, format="PNG")

    print(f"wrote {png_path.relative_to(ROOT)} ({master.size[0]}x{master.size[1]} RGBA)")
    print(f"wrote {icns_path.relative_to(ROOT)}")
    print(f"wrote {ico_path.relative_to(ROOT)}")
    print(f"wrote {WINDOW_ICON.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
