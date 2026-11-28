"""Generate a tiny deterministic YOLO-shaped ONNX model for pipeline tests.

The model ignores its input and emits one confident person box at the centre
of the model input. It is only a transport/runtime fixture: it does not prove
detection accuracy, only that preprocessing, ONNX Runtime, tensor decoding,
NMS, and the FFI path agree on the tensor layout.

Usage:
    python tools/onnx-test/gen_synthetic_model.py --size 640 --output model.onnx

`--attributes` changes the number of values per prediction, so the same
fixture can stand in for a person-only fine-tune (`--attributes 5`) or a
stock COCO export (the default 84).
"""

from __future__ import annotations

import argparse
from pathlib import Path

import onnx
from onnx import TensorProto, helper


def build(size: int, confidence: float, attributes: int = 84) -> onnx.ModelProto:
    values = [0.0] * attributes
    values[0] = size / 2.0
    values[1] = size / 2.0
    values[2] = size / 4.0
    values[3] = size / 2.0
    values[4] = confidence

    output = helper.make_tensor_value_info(
        "output", TensorProto.FLOAT, [1, attributes, 1]
    )
    input_value = helper.make_tensor_value_info(
        "input", TensorProto.FLOAT, [1, 3, size, size]
    )
    constant = helper.make_node(
        "Constant",
        inputs=[],
        outputs=["output"],
        value=helper.make_tensor(
            "prediction", TensorProto.FLOAT, [1, attributes, 1], values
        ),
    )
    graph = helper.make_graph([constant], "synthetic-yolo", [input_value], [output])
    model = helper.make_model(
        graph,
        opset_imports=[helper.make_opsetid("", 17)],
        producer_name="cctv-stream-test",
    )
    model.ir_version = 10
    onnx.checker.check_model(model)
    return model


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--size", type=int, default=640)
    parser.add_argument("--confidence", type=float, default=0.9)
    parser.add_argument("--attributes", type=int, default=84)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.size < 32 or args.size > 1280 or args.size % 32 != 0:
        raise SystemExit("--size must be a multiple of 32 between 32 and 1280")
    if args.attributes < 5:
        raise SystemExit("--attributes must be at least 5 (4 box + 1 score)")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(build(args.size, args.confidence, args.attributes), args.output)
    print(
        f"wrote {args.output} ({args.size}x{args.size} input, "
        f"{args.attributes} attributes)"
    )


if __name__ == "__main__":
    main()
