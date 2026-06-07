#!/usr/bin/env python3
"""Fail if the committed OpenAPI spec differs (semantically) from the live one.

springdoc does not guarantee stable map/list ordering across runs, so a raw byte
diff produces false positives. We canonicalise both specs (recursively sort dict
keys and lists) before comparing, which catches real API changes — added/removed
/changed operations, parameters, schemas — without serialization-order noise.

Usage: check_openapi_drift.py <live-spec.json> <committed-spec.json>
"""
import json
import sys


def canon(o):
    if isinstance(o, dict):
        return {k: canon(v) for k, v in sorted(o.items())}
    if isinstance(o, list):
        return sorted((canon(x) for x in o), key=lambda v: json.dumps(v, sort_keys=True))
    return o


def main(live_path, committed_path):
    with open(live_path) as f:
        live = json.load(f)
    with open(committed_path) as f:
        committed = json.load(f)

    if canon(live) == canon(committed):
        print("OpenAPI spec is up to date.")
        return 0

    print("::error::Committed openapi.json is out of date vs the live API. "
          "Regenerate it from /v3/api-docs and commit openapi.json (and openapi.yaml).")
    live_paths, committed_paths = set(live.get("paths", {})), set(committed.get("paths", {}))
    extra, missing = sorted(live_paths - committed_paths), sorted(committed_paths - live_paths)
    if extra:
        print("Paths in the API but missing from the committed spec:", extra)
    if missing:
        print("Paths in the committed spec but not in the API:", missing)
    if not extra and not missing:
        print("Path set matches; an operation, parameter or schema changed.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
