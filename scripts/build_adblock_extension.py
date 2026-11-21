#!/usr/bin/env python3
"""Package the bundled ad-block rules and scripts as one downloadable JSON file."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUTPUT = ROOT / "app" / "build" / "outputs" / "tramtruyen" / "adblock-extension" / "delivery" / "AdBlockExtension.json"
FILES = (
    "adblock_rules.json",
    "adblock_cosmetic.js",
    "browser_ad_guard.js",
)


def main() -> None:
    packaged_files: dict[str, dict[str, str]] = {}
    for name in FILES:
        content = (ASSETS / name).read_text(encoding="utf-8")
        packaged_files[name] = {
            "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
            "content": content,
        }

    rules = json.loads(packaged_files["adblock_rules.json"]["content"])
    if rules.get("format") != "tramtruyen-adblock-rules-v1":
        raise SystemExit("adblock_rules.json has an unsupported format")
    rule_count = sum(
        len(rules.get(key, []))
        for key in ("blocked_hosts", "url_fragments", "query_keys")
    )
    if rule_count <= 0:
        raise SystemExit("adblock_rules.json has no usable rules")

    bundle = {
        "format": "tramtruyen-adblock-extension",
        "package_version": os.environ.get("ADBLOCK_EXTENSION_VERSION", "2026.11.21.2"),
        "counts": {"rule_count": rule_count},
        "files": packaged_files,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(bundle, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUTPUT} ({OUTPUT.stat().st_size:,} bytes, {rule_count} rules)")


if __name__ == "__main__":
    main()
