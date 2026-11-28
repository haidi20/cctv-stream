"""Write the Python YOLO reference boxes `ai-engine` is compared against.

The Rust detector and this script run the *same* weights over the *same* frames,
so any difference the parity harness reports is a port difference in letterbox,
output decoding, NMS, or coordinate mapping - not a model difference.

Usage (host only; ultralytics and numpy must be installed):

    python tools/model-export/python_reference.py \
        --model "$env:TEMP\yolo11n.onnx" \
        --frames "$env:TEMP\cctv-parity\frames" \
        --output "$env:TEMP\cctv-parity\python.json" \
        --conf 0.25 --iou 0.7

Frames are raw RGB888 named `frame-<n>-<w>x<h>.rgb` (the layout the Kotlin frame
pump and `ai-engine` use) or ordinary images, which ultralytics reads itself.

The output is JSON in the schema `rust-core/ai-engine/tests/parity_with_python.rs`
expects, with coordinates normalised to `0..1` so one reference file is valid for
every resolution:

    [{"frame": "frame-0001-640x480.rgb",
      "boxes": [{"x": 0.1, "y": 0.2, "w": 0.1, "h": 0.3, "confidence": 0.87}]}]

Nothing here is committed output: frames, model, and `python.json` stay outside
the repo. Only the person class is exported.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

PERSON_CLASS = 0
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="ONNX weights to run")
    parser.add_argument("--frames", required=True, help="directory of frames")
    parser.add_argument("--output", required=True, help="path of python.json")
    parser.add_argument(
        "--conf",
        type=float,
        default=0.25,
        help="confidence floor; pass the same value to CCTV_PARITY_THRESHOLD",
    )
    parser.add_argument(
        "--iou", type=float, default=0.7, help="NMS IoU; ultralytics default"
    )
    parser.add_argument(
        "--imgsz", type=int, default=640, help="inference size; must match Rust"
    )
    return parser.parse_args()


def frame_size(name: str) -> tuple[int, int]:
    """Read `<w>x<h>` out of a `frame-<n>-<w>x<h>.rgb` name."""
    dimensions = Path(name).stem.rsplit("-", 1)[-1]
    width, height = dimensions.split("x")
    return int(width), int(height)


def load_raw_frame(path: Path):
    """Decode a raw RGB888 frame into an `(h, w, 3)` uint8 array."""
    import numpy as np

    width, height = frame_size(path.name)
    expected = width * height * 3
    payload = np.fromfile(path, dtype=np.uint8)
    if payload.size != expected:
        raise ValueError(
            f"{path.name} holds {payload.size} bytes, expected {expected} "
            f"for {width}x{height} RGB888"
        )
    return payload.reshape((height, width, 3))


def frame_paths(directory: Path) -> list[Path]:
    """Every supported frame in the directory, name order."""
    wanted = IMAGE_SUFFIXES | {".rgb"}
    frames = [path for path in sorted(directory.iterdir()) if path.suffix.lower() in wanted]
    if not frames:
        raise SystemExit(f"no .rgb or image frames in {directory}")
    return frames


def export(model_path: str, frames: list[Path], conf: float, iou: float, imgsz: int):
    """Run the model once per frame and return normalised person boxes."""
    try:
        from ultralytics import YOLO
    except ImportError as error:
        raise SystemExit(
            "ultralytics is not installed: run `pip install ultralytics` (pulls "
            "PyTorch). Only this reference side needs it; the Rust harness and "
            "`cargo test` do not."
        ) from error

    model = YOLO(model_path)
    entries = []
    for path in frames:
        if path.suffix.lower() == ".rgb":
            width, height = frame_size(path.name)
            # Ultralytics flips numpy input BGR -> RGB itself (`preprocess`), and
            # the raw frames here are already RGB, so hand it BGR: passing RGB
            # would run the model on swapped channels and turn a port difference
            # into a colour difference.
            source = load_raw_frame(path)[:, :, ::-1].copy()
        else:
            source = str(path)
            width, height = 0, 0
        result = model.predict(
            source=source, conf=conf, iou=iou, imgsz=imgsz, verbose=False
        )[0]
        if width == 0:
            height, width = result.orig_shape

        boxes = []
        for box in result.boxes:
            if int(box.cls.item()) != PERSON_CLASS:
                continue
            x1, y1, x2, y2 = (float(value) for value in box.xyxy[0].tolist())
            boxes.append(
                {
                    "x": round(x1 / width, 6),
                    "y": round(y1 / height, 6),
                    "w": round((x2 - x1) / width, 6),
                    "h": round((y2 - y1) / height, 6),
                    "confidence": round(float(box.conf.item()), 6),
                }
            )
        entries.append({"frame": path.name, "boxes": boxes})
    return entries


def main() -> int:
    args = parse_args()
    directory = Path(args.frames)
    if not directory.is_dir():
        raise SystemExit(f"{directory} is not a directory")

    frames = frame_paths(directory)
    entries = export(args.model, frames, args.conf, args.iou, args.imgsz)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(entries, indent=2), encoding="utf-8")

    total = sum(len(entry["boxes"]) for entry in entries)
    print(f"wrote {total} boxes over {len(entries)} frames to {output}")
    print(
        "compare with: "
        f'CCTV_PARITY_MODEL="{args.model}" CCTV_PARITY_DIR="{output.parent}" '
        f"CCTV_PARITY_THRESHOLD={args.conf} cargo test -p ai-engine --test parity_with_python -- --nocapture"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
