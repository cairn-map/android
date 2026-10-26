"""Reproduce the bundled offline EGM96 model from PROJ's pinned public GTX grid."""

import argparse
import gzip
import hashlib
import io
from pathlib import Path
import struct


SOURCE_SHA256 = "c02a6eb70a7a78efebe5adf3ade626eb75390e170bb8b3f36136a2c28f5326a0"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/resources/app/cairn/trek/egm96-15cm.bin.gz"


def prepare(source, output):
    data = Path(source).read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError("Unexpected EGM96 source grid SHA-256")
    if struct.unpack(">4d2i", data[:40]) != (-90, -180, .25, .25, 721, 1440):
        raise ValueError("Unexpected EGM96 source geometry")
    encoded = bytearray(struct.pack(">8sHH", b"EGM96CM1", 721, 1440))
    for (metres,) in struct.iter_unpack(">f", data[40:]):
        encoded.extend(struct.pack(">h", round(metres * 100)))
    compressed = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=compressed, mode="wb", compresslevel=9, mtime=0) as stream:
        stream.write(encoded)
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(compressed.getvalue())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="PROJ egm96_15.gtx input")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    arguments = parser.parse_args()
    prepare(arguments.source, arguments.output)
