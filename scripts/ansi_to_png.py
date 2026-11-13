#!/usr/bin/env python3
"""Render ANSI / plain terminal text from stdin to a fixed-size PNG for docs."""

from __future__ import annotations

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT = 860, 466
COLS, ROWS = 120, 30
BG = (16, 21, 19)
FG = (228, 236, 230)
MUTED = (140, 156, 148)
ACCENT = (110, 190, 150)
WARN = (220, 170, 90)
ERR = (220, 110, 110)

ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
SGR_RE = re.compile(r"\x1b\[([0-9;]*)m")


def resolve_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        "/System/Library/Fonts/Menlo.ttc",
        "/System/Library/Fonts/SFNSMono.ttf",
        "/Library/Fonts/SF-Mono-Regular.otf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
        "/usr/share/fonts/TTF/DejaVuSansMono.ttf",
    ]
    for path in candidates:
        if Path(path).is_file():
            try:
                return ImageFont.truetype(path, size=size, index=0)
            except OSError:
                continue
    return ImageFont.load_default()


def color_for(codes: list[int]) -> tuple[int, int, int]:
    if 31 in codes or 91 in codes:
        return ERR
    if 33 in codes or 93 in codes:
        return WARN
    if 32 in codes or 92 in codes or 36 in codes or 96 in codes:
        return ACCENT
    if 90 in codes or 2 in codes:
        return MUTED
    return FG


def segments(line: str) -> list[tuple[str, tuple[int, int, int]]]:
    out: list[tuple[str, tuple[int, int, int]]] = []
    color = FG
    pos = 0
    for match in SGR_RE.finditer(line):
        if match.start() > pos:
            out.append((line[pos : match.start()], color))
        codes = [int(part) for part in match.group(1).split(";") if part]
        if not codes or 0 in codes:
            color = FG
        else:
            color = color_for(codes)
        pos = match.end()
    if pos < len(line):
        out.append((line[pos:], color))
    if not out:
        out.append(("", FG))
    cleaned: list[tuple[str, tuple[int, int, int]]] = []
    for text, col in out:
        text = ANSI_RE.sub("", text)
        if text:
            cleaned.append((text, col))
    return cleaned or [("", FG)]


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} OUT.png", file=sys.stderr)
        return 2
    out_path = Path(sys.argv[1])
    raw = sys.stdin.read()
    lines = raw.splitlines()
    while lines and not lines[-1].strip():
        lines.pop()
    lines = lines[:ROWS]
    while len(lines) < ROWS:
        lines.append("")

    cell_w = WIDTH / COLS
    cell_h = HEIGHT / ROWS
    font = resolve_font(max(10, int(cell_h * 0.78)))

    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)
    for row, line in enumerate(lines):
        x = 0.0
        y = row * cell_h + 1
        for text, color in segments(line):
            draw.text((x, y), text, fill=color, font=font)
            x += len(text) * cell_w
            if x >= WIDTH:
                break

    out_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(out_path, format="PNG", optimize=True)
    print(f"wrote {out_path} ({WIDTH}x{HEIGHT})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
