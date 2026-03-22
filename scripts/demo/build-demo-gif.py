#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
from typing import List, Tuple

try:
    from PIL import Image, ImageDraw
except Exception as exc:  # pragma: no cover
    print(f"Pillow is required to build GIF: {exc}", file=sys.stderr)
    sys.exit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build demo GIF from steps.json")
    parser.add_argument("--output-dir", required=True, help="Demo output directory")
    parser.add_argument("--steps-file", default="", help="Optional custom steps.json path")
    parser.add_argument("--gif-file", default="", help="Optional output gif path")
    return parser.parse_args()


def load_steps(steps_file: str) -> List[dict]:
    if not os.path.exists(steps_file):
        raise FileNotFoundError(f"steps file not found: {steps_file}")
    with open(steps_file, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise ValueError("steps.json must be an array")
    return data


def parse_step_order(step: dict, index: int, screenshot: str) -> int:
    raw_order = step.get("order")
    if isinstance(raw_order, int):
        return raw_order
    match = re.match(r"^(\d+)-", os.path.basename(screenshot))
    if match:
        return int(match.group(1))
    return index + 1


def resolve_frames(output_dir: str, steps: List[dict]) -> List[Tuple[int, str, int, str, str]]:
    frames: List[Tuple[int, str, int, str, str]] = []
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        if step.get("includeInGif", True) is not True:
            continue
        screenshot = str(step.get("screenshot", "")).strip()
        if not screenshot:
            continue
        file_name = os.path.basename(screenshot)
        screenshot_path = os.path.join(output_dir, "screenshots", file_name)
        if not os.path.exists(screenshot_path):
            continue
        duration = int(step.get("durationMs", 1200) or 1200)
        title = str(step.get("title", "")).strip() or str(step.get("id", "")).strip() or "Step"
        caption = str(step.get("caption", "")).strip()
        order = parse_step_order(step, index, screenshot)
        frames.append((order, screenshot_path, duration, title, caption))
    frames.sort(key=lambda item: item[0])
    return frames


def annotate_frame(image: Image.Image, order: int, title: str, caption: str) -> Image.Image:
    frame = image.convert("RGB")
    draw = ImageDraw.Draw(frame)
    overlay_h = 74 if caption else 44
    draw.rectangle([(0, 0), (frame.width, overlay_h)], fill=(0, 0, 0))
    draw.text((10, 8), f"{order:02d}. {title}", fill=(255, 255, 255))
    if caption:
        draw.text((10, 40), caption[:120], fill=(220, 220, 220))
    return frame


def main() -> int:
    args = parse_args()
    output_dir = os.path.abspath(args.output_dir)
    steps_file = os.path.abspath(args.steps_file) if args.steps_file else os.path.join(output_dir, "steps.json")
    gif_file = os.path.abspath(args.gif_file) if args.gif_file else os.path.join(output_dir, "demo.gif")

    steps = load_steps(steps_file)
    frames_meta = resolve_frames(output_dir, steps)
    if not frames_meta:
        raise RuntimeError("No valid frames found to build GIF")

    rendered_frames: List[Image.Image] = []
    durations: List[int] = []
    base_size = None
    for order, frame_path, duration, title, caption in frames_meta:
        img = Image.open(frame_path)
        if base_size is None:
            base_size = img.size
        if img.size != base_size:
            img = img.resize(base_size)
        rendered_frames.append(annotate_frame(img, order, title, caption))
        durations.append(duration)

    first, rest = rendered_frames[0], rendered_frames[1:]
    first.save(
        gif_file,
        save_all=True,
        append_images=rest,
        duration=durations,
        loop=0,
        optimize=False,
    )
    print(gif_file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
