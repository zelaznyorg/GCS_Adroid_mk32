#!/usr/bin/env python3
"""Decode and build Qualcomm SPLASH!! images used by the SIYI MK32."""

from __future__ import annotations

import argparse
import math
import struct
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance


MAGIC = b"SPLASH!!"
BLOCK_SIZE = 512


def find_header(data: bytes) -> int:
    offset = data.find(MAGIC)
    if offset < 0:
        raise ValueError("SPLASH!! header not found")
    return offset


def decode_rle24(payload: bytes, width: int, height: int) -> Image.Image:
    pixels: list[tuple[int, int, int]] = []
    pos = 0
    expected = width * height

    while pos < len(payload) and len(pixels) < expected:
        control = payload[pos]
        pos += 1
        count = control + 1

        if count > 128:
            if pos + 3 > len(payload):
                raise ValueError("Truncated repeated-color RLE packet")
            blue, green, red = payload[pos : pos + 3]
            pos += 3
            pixels.extend([(red, green, blue)] * (count - 128))
        else:
            byte_count = count * 3
            if pos + byte_count > len(payload):
                raise ValueError("Truncated literal RLE packet")
            for idx in range(pos, pos + byte_count, 3):
                blue, green, red = payload[idx : idx + 3]
                pixels.append((red, green, blue))
            pos += byte_count

    if len(pixels) != expected:
        raise ValueError(f"Decoded {len(pixels)} pixels, expected {expected}")

    image = Image.new("RGB", (width, height))
    image.putdata(pixels)
    return image


def decode(input_path: Path, output_path: Path) -> None:
    data = input_path.read_bytes()
    header_offset = find_header(data)
    width, height, compression, blocks = struct.unpack_from("<4I", data, header_offset + 8)
    if compression != 1:
        raise ValueError(f"Unsupported compression type: {compression}")

    payload_offset = header_offset + BLOCK_SIZE
    payload = data[payload_offset : payload_offset + blocks * BLOCK_SIZE]
    image = decode_rle24(payload, width, height)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(output_path)
    print(
        f"Decoded {input_path} -> {output_path} "
        f"({width}x{height}, RLE24, header 0x{header_offset:X}, {blocks} blocks)"
    )


def encode_rle24(image: Image.Image) -> bytes:
    pixels = list(image.convert("RGB").getdata())
    encoded = bytearray()
    pos = 0

    while pos < len(pixels):
        run_length = 1
        while (
            pos + run_length < len(pixels)
            and pixels[pos + run_length] == pixels[pos]
            and run_length < 128
        ):
            run_length += 1

        if run_length >= 2:
            red, green, blue = pixels[pos]
            encoded.append(run_length + 127)
            encoded.extend((blue, green, red))
            pos += run_length
            continue

        literal_start = pos
        pos += 1
        while pos < len(pixels) and pos - literal_start < 128:
            next_run = 1
            while (
                pos + next_run < len(pixels)
                and pixels[pos + next_run] == pixels[pos]
                and next_run < 128
            ):
                next_run += 1
            if next_run >= 2:
                break
            pos += 1

        literal = pixels[literal_start:pos]
        encoded.append(len(literal) - 1)
        for red, green, blue in literal:
            encoded.extend((blue, green, red))

    return bytes(encoded)


def build(input_path: Path, template_path: Path, output_path: Path) -> None:
    image = Image.open(input_path).convert("RGB")
    template = bytearray(template_path.read_bytes())
    header_offset = find_header(template)
    original_width, original_height = struct.unpack_from("<2I", template, header_offset + 8)
    if image.size != (original_width, original_height):
        raise ValueError(
            f"Input must be {original_width}x{original_height}, got {image.width}x{image.height}"
        )

    payload = encode_rle24(image)
    blocks = math.ceil(len(payload) / BLOCK_SIZE)
    payload_offset = header_offset + BLOCK_SIZE
    padded_size = blocks * BLOCK_SIZE
    if payload_offset + padded_size > len(template):
        raise ValueError("Encoded image does not fit in the splash partition")

    template[header_offset:] = bytes(len(template) - header_offset)
    struct.pack_into(
        "<8s4I",
        template,
        header_offset,
        MAGIC,
        image.width,
        image.height,
        1,
        blocks,
    )
    template[payload_offset : payload_offset + len(payload)] = payload

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(template)
    print(
        f"Built {output_path} from {input_path} "
        f"({image.width}x{image.height}, {len(payload)} bytes, {blocks} blocks)"
    )


def compose_logo(source_path: Path, preview_path: Path, stored_path: Path) -> None:
    source = Image.open(source_path).convert("RGBA")
    # The supplied file contains a clipped wordmark at the bottom. Keep the
    # complete blue circuit/drone mark only so the boot logo stays intentional.
    mark = source.crop((70, 15, 640, 455))

    alpha = Image.new("L", mark.size)
    source_pixels = mark.load()
    alpha_pixels = alpha.load()
    for y in range(mark.height):
        for x in range(mark.width):
            red, green, blue, _ = source_pixels[x, y]
            distance_from_white = 255 - min(red, green, blue)
            alpha_pixels[x, y] = max(0, min(255, (distance_from_white - 4) * 4))
    mark.putalpha(alpha)

    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError("No visible logo pixels found")
    mark = mark.crop(bounds)
    mark.thumbnail((570, 430), Image.Resampling.LANCZOS)

    preview = Image.new("RGB", (1280, 800), (2, 8, 18))
    x = (preview.width - mark.width) // 2
    y = (preview.height - mark.height) // 2
    preview.paste(mark, (x, y), mark)
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(preview_path)

    # MK32 stores the boot framebuffer as portrait 800x1280. The physical
    # controller is landscape, so the desired view is rotated clockwise here.
    stored = preview.rotate(-90, expand=True)
    if stored.size != (800, 1280):
        raise AssertionError(f"Unexpected stored image size: {stored.size}")
    stored_path.parent.mkdir(parents=True, exist_ok=True)
    stored.save(stored_path)
    print(f"Composed preview {preview_path} and stored image {stored_path}")


def compose_background_logo(
    background_path: Path,
    logo_path: Path,
    preview_path: Path,
    stored_path: Path,
) -> None:
    background = Image.open(background_path).convert("RGB")
    target_size = (1280, 800)
    scale = max(target_size[0] / background.width, target_size[1] / background.height)
    resized_size = (
        round(background.width * scale),
        round(background.height * scale),
    )
    background = background.resize(resized_size, Image.Resampling.LANCZOS)
    crop_left = (background.width - target_size[0]) // 2
    crop_top = (background.height - target_size[1]) // 2
    preview = background.crop(
        (crop_left, crop_top, crop_left + target_size[0], crop_top + target_size[1])
    )
    # Reduce only the background brightness; the supplied logo remains untouched.
    preview = ImageEnhance.Brightness(preview).enhance(0.68)

    logo = Image.open(logo_path).convert("RGBA")
    logo.thumbnail((720, 536), Image.Resampling.LANCZOS)
    logo_x = (preview.width - logo.width) // 2
    logo_y = (preview.height - logo.height) // 2
    preview.paste(logo, (logo_x, logo_y), logo)

    preview_path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(preview_path)

    stored = preview.rotate(-90, expand=True)
    if stored.size != (800, 1280):
        raise AssertionError(f"Unexpected stored image size: {stored.size}")
    stored_path.parent.mkdir(parents=True, exist_ok=True)
    stored.save(stored_path)
    print(f"Composed preview {preview_path} and stored image {stored_path}")


def verify_roundtrip(source_path: Path, splash_path: Path) -> None:
    source = Image.open(source_path).convert("RGB")
    data = splash_path.read_bytes()
    header_offset = find_header(data)
    width, height, compression, blocks = struct.unpack_from("<4I", data, header_offset + 8)
    if compression != 1:
        raise ValueError(f"Unsupported compression type: {compression}")
    payload_offset = header_offset + BLOCK_SIZE
    decoded = decode_rle24(
        data[payload_offset : payload_offset + blocks * BLOCK_SIZE], width, height
    )
    difference = ImageChops.difference(source, decoded)
    if difference.getbbox() is not None:
        raise ValueError("Round-trip verification failed: decoded pixels differ")
    print("Round-trip verification passed: every decoded pixel matches the source")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    decode_parser = subparsers.add_parser("decode")
    decode_parser.add_argument("input", type=Path)
    decode_parser.add_argument("output", type=Path)

    build_parser = subparsers.add_parser("build")
    build_parser.add_argument("input", type=Path)
    build_parser.add_argument("template", type=Path)
    build_parser.add_argument("output", type=Path)

    compose_parser = subparsers.add_parser("compose-logo")
    compose_parser.add_argument("source", type=Path)
    compose_parser.add_argument("preview", type=Path)
    compose_parser.add_argument("stored", type=Path)

    background_parser = subparsers.add_parser("compose-background-logo")
    background_parser.add_argument("background", type=Path)
    background_parser.add_argument("logo", type=Path)
    background_parser.add_argument("preview", type=Path)
    background_parser.add_argument("stored", type=Path)

    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("source", type=Path)
    verify_parser.add_argument("splash", type=Path)

    args = parser.parse_args()
    if args.command == "decode":
        decode(args.input, args.output)
    elif args.command == "build":
        build(args.input, args.template, args.output)
    elif args.command == "compose-logo":
        compose_logo(args.source, args.preview, args.stored)
    elif args.command == "compose-background-logo":
        compose_background_logo(args.background, args.logo, args.preview, args.stored)
    elif args.command == "verify":
        verify_roundtrip(args.source, args.splash)


if __name__ == "__main__":
    main()
