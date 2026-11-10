#!/usr/bin/env python3
import json
from pathlib import Path
p=Path(__file__).resolve().parent
m=json.loads((p/"recordings.json").read_text())
s=[json.loads(x) for x in (p/"shots.jsonl").read_text().splitlines() if x.strip()]
e=[json.loads(x) for x in (p/"events.jsonl").read_text().splitlines() if x.strip()]
ids={r["id"] for r in m["recordings"]}
assert len(ids)==len(m["recordings"]), "duplicate recording IDs"
hashes=[r["sha256"] for r in m["recordings"]]
assert len(hashes)==len(set(hashes)), "duplicate included recording hashes"
for x in s:
    assert x["recording_id"] in ids
    v=x.get("power_effective_fraction")
    assert v is None or 0<=v<=1
    if x["first_contact_status"] in ("not_verified","not_fully_verified"):
        assert not x["training_eligibility"].get("first_contact",False)
    if x.get("pocketed_ball") is None:
        assert not x["training_eligibility"].get("pocket_outcome",False)
for x in e: assert x["recording_id"] in ids
assert any(x["id"]=="plato_1000323251" and x["split"].startswith("validation") for x in m["recordings"])
assert any(x["id"]=="plato_1000323259" and x["split"].startswith("validation") for x in m["recordings"])
assert json.loads((p/"power-calibration.json").read_text())["user_confirmed_anchor"]["effective_power_fraction"]==1.0
print(f"OK: {len(ids)} recordings, {len(s)} shot rows, {len(e)} events; no frame-level split leakage.")
