#!/usr/bin/env python3
"""Assemble the downloadable ReaderApp components for the NovelBook_Cloud bucket."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUTPUT = ROOT / "app" / "build" / "outputs" / "tramtruyen" / "novelbook-cloud-upload"
BUCKET = "https://huggingface.co/buckets/uri410/NovelBook_Cloud/resolve/"
PACKAGE_VERSION = "2026.11.21.2"
ADBLOCK_VERSION = "2026.11.21.2"
GECKO_APKS = ROOT / "app" / "build" / "outputs" / "tramtruyen" / "zip-20261121-138" / "delivery" / "TramTruyen-Gecko.apks"


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe(path: Path, version: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {
        "path": path.relative_to(OUTPUT).as_posix(),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }
    if version:
        result["version"] = version
    return result


def build_parser_package() -> Path:
    parser_root = ASSETS / "parser_v4"
    catalog = read_json(parser_root / "catalog.json")
    source_dirs: dict[str, Path] = {}
    for directory in (parser_root / "sources").iterdir():
        if not directory.is_dir():
            continue
        manifest_path = directory / "manifest.json"
        if not manifest_path.is_file():
            continue
        manifest = read_json(manifest_path)
        source_id = str(manifest.get("id", directory.name))
        if source_id in source_dirs:
            raise ValueError(f"Trùng parser id: {source_id}")
        source_dirs[source_id] = directory

    packed_sources: dict[str, Any] = {}
    for entry in catalog.get("sources", []):
        source_id = str(entry.get("id", ""))
        directory = source_dirs.get(source_id)
        if not source_id or directory is None:
            raise ValueError(f"Không tìm thấy thư mục parser {source_id!r}")
        manifest = read_json(directory / "manifest.json")
        preferred = directory / f"{source_id}.js"
        scripts = list(directory.glob("*.js"))
        script_path = preferred if preferred.is_file() else (scripts[0] if len(scripts) == 1 else None)
        if script_path is None:
            raise ValueError(f"Không xác định được script của {source_id}")
        script = script_path.read_text(encoding="utf-8")
        packed_sources[source_id] = {
            "directory": directory.name,
            "manifest": manifest,
            "catalog_entry": entry,
            "script_file": script_path.name,
            "script_sha256": hashlib.sha256(script.encode("utf-8")).hexdigest(),
            "script": script,
        }

    legacy = read_json(ASSETS / "parser.json")
    bundle = {
        "format": "tramtruyen-web-parser-bundle",
        "format_version": 2,
        "package_version": PACKAGE_VERSION,
        "counts": {
            "parser_v4_sources": len(packed_sources),
            "legacy_site_configs": len(legacy),
        },
        "parser_v4": {"catalog": catalog, "sources": packed_sources},
        "legacy_parser": legacy,
    }
    output = OUTPUT / "parsers" / "WebParser.json"
    write_json(output, bundle)
    return output


def main() -> None:
    if not GECKO_APKS.is_file():
        raise FileNotFoundError(f"Thiếu module Gecko APKS: {GECKO_APKS}")

    for old in (OUTPUT / "extensions", OUTPUT / "parsers", OUTPUT / "runtime", OUTPUT / "network"):
        if old.exists():
            shutil.rmtree(old)

    adblock_dir = OUTPUT / "extensions" / "adblock"
    adblock_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(
        ROOT / "app" / "build" / "outputs" / "tramtruyen" / "adblock-extension" / "delivery" / "AdBlockExtension.json",
        adblock_dir / "AdBlockExtension.json",
    )
    for name in ("adblock_rules.json", "adblock_cosmetic.js", "browser_ad_guard.js"):
        shutil.copy2(ASSETS / name, adblock_dir / name)

    parser_file = build_parser_package()
    gecko_dir = OUTPUT / "runtime" / "gecko"
    gecko_dir.mkdir(parents=True, exist_ok=True)
    gecko_file = gecko_dir / "GeckoBrowser.apks.zip"
    shutil.copy2(GECKO_APKS, gecko_file)

    tts_dir = OUTPUT / "tts" / "piper"
    tts_dir.mkdir(parents=True, exist_ok=True)
    models_file = ASSETS / "reader_catalog" / "tts" / "models.json"
    models = read_json(models_file)
    old_prefix = "https://huggingface.co/buckets/uri410/NovelReaderApp/resolve/TTS/piper/"
    new_prefix = BUCKET + "tts/piper/models/"
    old_demo_prefix = "https://huggingface.co/buckets/uri410/NovelReaderApp/resolve/TTS/demos/"
    new_demo_prefix = BUCKET + "tts/piper/demos/"
    for model in models:
        for key in ("download_url", "config_url", "demo_url"):
            value = model.get(key)
            if isinstance(value, str) and value.startswith(old_prefix):
                model[key] = new_prefix + value[len(old_prefix):]
        demo = model.get("demo_url")
        if isinstance(demo, str) and demo.startswith(old_demo_prefix):
            model["demo_url"] = new_demo_prefix + demo[len(old_demo_prefix):]
    write_json(tts_dir / "models.json", models)
    write_json(models_file, models)

    dns_file = OUTPUT / "network" / "dns" / "providers.json"
    write_json(dns_file, {
        "format": "tramtruyen-dns-providers",
        "version": 1,
        "providers": [
            {"id": "cloudflare", "name": "Cloudflare", "private_dns": "one.one.one.one", "ipv4": ["1.1.1.1", "1.0.0.1"]},
            {"id": "google", "name": "Google Public DNS", "private_dns": "dns.google", "ipv4": ["8.8.8.8", "8.8.4.4"]},
        ],
    })

    components = {
        "adblock": describe(adblock_dir / "AdBlockExtension.json", ADBLOCK_VERSION),
        "parsers": describe(parser_file, PACKAGE_VERSION),
        "gecko": describe(gecko_file, "1.015"),
        "tts_piper": {
            "catalog": describe(tts_dir / "models.json", "1"),
            "voice_count": len(models),
            "demos": [
                describe(OUTPUT / "tts" / "piper" / "demos" / Path(model["demo_url"]).name)
                for model in models
                if model.get("demo_url") and (OUTPUT / "tts" / "piper" / "demos" / Path(model["demo_url"]).name).is_file()
            ],
            "shared_espeak": {
                "path": "tts/piper/shared/espeak/tts-data.zip",
                "bytes": 17_849_373,
                "sha256": "000f96193a48015b865cfb67616ad256b613a466aead6f2d3f182a14ff4a5015",
            },
        },
        "dns": describe(dns_file, "1"),
    }
    manifest = {
        "format": "tramtruyen-download-components",
        "schema_version": 1,
        "components": components,
    }
    write_json(OUTPUT / "manifest.json", manifest)

    readme = """# NovelBook — thành phần tải bổ sung

`manifest.json` liệt kê đường dẫn, phiên bản, kích thước và SHA-256 của từng gói.

- `extensions/adblock/`: gói extension chặn quảng cáo và các tệp nguồn.
- `parsers/WebParser.json`: parser v4 và cấu hình parser cũ; không chứa bộ script ThienSiteJs.
- `runtime/gecko/GeckoBrowser.apks.zip`: module APKS; app đổi lại đuôi `.apks` khi cài.
- `tts/piper/`: danh mục model Piper; model nằm dưới `models/<id>/`, file nghe thử ở `demos/`, dữ liệu eSpeak ở `shared/espeak/`.
- `network/dns/providers.json`: cấu hình Cloudflare và Google Public DNS.
"""
    (OUTPUT / "README.md").write_text(readme, encoding="utf-8")
    parser_bundle = read_json(parser_file)
    print(json.dumps({
        "output": str(OUTPUT),
        "parser_sources": parser_bundle["counts"]["parser_v4_sources"],
        "legacy_configs": parser_bundle["counts"]["legacy_site_configs"],
        "tts_models": len(models),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
