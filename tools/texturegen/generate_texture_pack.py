#!/usr/bin/env python3
"""
Generate a simple CafeMC resource pack with Keno number tiles and model overrides.

Usage:
  python3 tools/texturegen/generate_texture_pack.py
"""

from __future__ import annotations

import json
from pathlib import Path
import shutil
import platform
import os

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "generated" / "resourcepack"
ZIP_OUT = ROOT / "generated" / "CafeMC-Casino-ResourcePack.zip"
PACK_FOLDER_NAME = "CafeMC-Casino-ResourcePack"
NAMESPACE = "cafemc"

PACK_FORMAT = 69  # 1.21.9-1.21.10

BASE_CMD = 91000
VARIANT_OFFSETS = {
    "neutral": 0,
    "selected": 100,
    "hit": 200,
    "miss": 300,
}

VARIANT_COLORS = {
    "neutral": ("#8B8F97", "#D5D9E0", "#2B2E34"),
    "selected": ("#D3A21A", "#F5DF6B", "#5A4300"),
    "hit": ("#2FA24F", "#90F0A4", "#0F5222"),
    "miss": ("#B84040", "#F49A9A", "#5A1717"),
}


def ensure_dirs() -> dict[str, Path]:
    textures = OUT / "assets" / NAMESPACE / "textures" / "item" / "keno"
    models = OUT / "assets" / NAMESPACE / "models" / "item" / "keno"
    mc_items = OUT / "assets" / "minecraft" / "items"
    mc_item_models = OUT / "assets" / "minecraft" / "models" / "item"
    for p in (textures, models, mc_items, mc_item_models):
        p.mkdir(parents=True, exist_ok=True)
    return {
        "textures": textures,
        "models": models,
        "mc_items": mc_items,
        "mc_item_models": mc_item_models,
    }


def write_pack_mcmeta() -> None:
    data = {
        "pack": {
            "description": "CafeMC Auto-Generated Casino Textures",
            # 1.21.9+ uses min/max format checks for compatibility.
            "min_format": PACK_FORMAT,
            "max_format": PACK_FORMAT,
        }
    }
    (OUT / "pack.mcmeta").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def font() -> ImageFont.ImageFont:
    try:
        return ImageFont.truetype("DejaVuSans-Bold.ttf", 20)
    except Exception:
        return ImageFont.load_default()


def draw_tile(number: int, variant: str, target: Path) -> None:
    bg, fg, border = VARIANT_COLORS[variant]
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((1, 1, 30, 30), radius=6, fill=bg, outline=border, width=2)

    txt = str(number)
    f = font()
    bbox = d.textbbox((0, 0), txt, font=f)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    x = (32 - tw) // 2
    y = (32 - th) // 2 - 1
    d.text((x, y), txt, font=f, fill=fg)

    img.save(target, "PNG")


def write_model(texture_ref: str, target: Path) -> None:
    model = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": texture_ref},
    }
    target.write_text(json.dumps(model, indent=2) + "\n", encoding="utf-8")


def write_minecraft_carrier_overrides(target: Path) -> None:
    overrides = []
    for n in range(1, 21):
        for variant, offset in VARIANT_OFFSETS.items():
            cmd = BASE_CMD + offset + n
            overrides.append(
                {
                    "predicate": {"custom_model_data": cmd},
                    "model": f"{NAMESPACE}:item/keno/{variant}_{n}",
                }
            )

    data = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "minecraft:item/paper"},
        "overrides": overrides,
    }
    target.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def write_minecraft_item_definition(target: Path) -> None:
    entries = []
    for n in range(1, 21):
        for variant, offset in VARIANT_OFFSETS.items():
            cmd = BASE_CMD + offset + n
            entries.append(
                {
                    "threshold": float(cmd),
                    "model": {
                        "type": "minecraft:model",
                        "model": f"{NAMESPACE}:item/keno/{variant}_{n}",
                    },
                }
            )
    entries.sort(key=lambda e: e["threshold"])
    data = {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:custom_model_data",
            "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
            "entries": entries,
        }
    }
    target.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def write_mapping_json(target: Path) -> None:
    mapping: dict[str, dict[str, int]] = {}
    for variant, offset in VARIANT_OFFSETS.items():
        mapping[variant] = {str(n): BASE_CMD + offset + n for n in range(1, 21)}
    target.write_text(json.dumps(mapping, indent=2) + "\n", encoding="utf-8")


def detect_minecraft_resourcepacks_dir() -> Path | None:
    system = platform.system().lower()
    home = Path.home()

    if system == "darwin":
        return home / "Library" / "Application Support" / "minecraft" / "resourcepacks"

    if system == "windows":
        appdata = os.environ.get("APPDATA")
        if appdata:
            return Path(appdata) / ".minecraft" / "resourcepacks"
        return home / "AppData" / "Roaming" / ".minecraft" / "resourcepacks"

    # Linux fallback
    return home / ".minecraft" / "resourcepacks"


def install_folder_pack() -> Path | None:
    resourcepacks = detect_minecraft_resourcepacks_dir()
    if resourcepacks is None:
        return None

    resourcepacks.mkdir(parents=True, exist_ok=True)
    target = resourcepacks / PACK_FOLDER_NAME
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(OUT, target)
    return target


def main() -> None:
    dirs = ensure_dirs()
    write_pack_mcmeta()

    for n in range(1, 21):
        for variant in VARIANT_OFFSETS:
            tex_name = f"{variant}_{n}.png"
            model_name = f"{variant}_{n}.json"
            draw_tile(n, variant, dirs["textures"] / tex_name)
            write_model(f"{NAMESPACE}:item/keno/{variant}_{n}", dirs["models"] / model_name)

    # Legacy predicate path (older versions)
    write_minecraft_carrier_overrides(dirs["mc_item_models"] / "paper.json")
    # Modern 1.21.4+ path
    write_minecraft_item_definition(dirs["mc_items"] / "paper.json")
    write_mapping_json(OUT / "keno_model_data_map.json")
    if ZIP_OUT.exists():
        ZIP_OUT.unlink()
    shutil.make_archive(str(ZIP_OUT.with_suffix("")), "zip", OUT)
    installed = install_folder_pack()
    print(f"Generated resource pack folder: {OUT}")
    print(f"Generated resource pack zip: {ZIP_OUT}")
    if installed is not None:
        print(f"Installed folder pack to: {installed}")


if __name__ == "__main__":
    main()
