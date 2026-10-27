"""Build the fixed, labelled evaluation dataset used to score every global model version.

Deterministic (fixed seed): re-running produces byte-identical output, and the dataset id is
derived from the content, so a changed dataset always gets a new id.

Traffic mirrors the simulator (TrafficGeneratorService + AttackPlanner): the same 17 nodes,
per-type ports and byte ranges, single-packet flows, and the IDS batching (one batch = the flows
one destination node receives in one 500 ms window). Labels are simulation ground truth and are
never used as model features.

Usage (from ml-service/):  python eval/build_eval_dataset.py
"""
import hashlib
import json
import os
import random

SEED = 20261028
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_dataset.json")

NODES = {
    "inet-gw": "ROUTER", "fw-1": "FIREWALL", "core-sw": "ROUTER", "web-1": "SERVER", "app-1": "SERVER",
    "db-1": "DATABASE", "vpn-gw": "ROUTER", "admin-pc": "PC", "dev-pc": "PC", "emp-pc-1": "PC",
    "emp-pc-2": "PC", "iot-gw": "ROUTER", "iot-1": "IOT_DEVICE", "iot-2": "IOT_DEVICE", "iot-3": "IOT_DEVICE",
    "agg-srv": "SERVER", "backup-srv": "SERVER",
}
EXPOSED = {"inet-gw": 443, "fw-1": 443, "vpn-gw": 443, "web-1": 443}
AUTH = {"PC": 3389, "DATABASE": 5432, "IOT_DEVICE": 23}
SERVICE = {"DATABASE": 5432, "IOT_DEVICE": 1883, "PC": 445, "ROUTER": 161, "FIREWALL": 161}

NORMAL_WINDOWS = 150
ATTACK_WINDOWS = {"DDOS": 4, "PORT": 3, "BRUTE": 4, "MALWARE": 8, "EXFIL": 6, "RECON": 8}


def dest_port(rnd, t):  # TrafficGeneratorService.getDestPortForType
    if t == "SERVER": return rnd.choice([80, 443, 8080, 8443])
    if t == "DATABASE": return rnd.choice([3306, 5432, 27017, 6379])
    if t == "IOT_DEVICE": return rnd.choice([1883, 8883, 5683])
    return 80 + rnd.randrange(400)


def normal_bytes(rnd, t):  # TrafficGeneratorService.getTypicalBytesForType
    if t == "IOT_DEVICE": return 64 + rnd.randrange(256)
    if t == "SERVER": return 256 + rnd.randrange(1200)
    if t == "DATABASE": return 512 + rnd.randrange(2000)
    return 128 + rnd.randrange(1000)


def attack_flows(rnd, kind, target):
    """(dest, dest_port, bytes) per attack packet, following the AttackPlanner rules."""
    t = NODES[target]
    if kind == "DDOS":
        port = EXPOSED.get(target, SERVICE.get(t, 443))
        return [(target, port, 32 + rnd.randrange(32)) for _ in range(50)]
    if kind == "PORT":
        start = rnd.randrange(1000)
        return [(target, p, 40 + rnd.randrange(24)) for p in range(start, start + 100)]
    if kind == "BRUTE":
        port = EXPOSED.get(target, AUTH.get(t, 22))
        return [(target, port, 128 + rnd.randrange(256)) for _ in range(30)]
    if kind == "MALWARE":
        return [("inet-gw", 443, 64 + rnd.randrange(128)) for _ in range(5)]
    if kind == "EXFIL":
        return [("inet-gw", 443, 8000 + rnd.randrange(7000)) for _ in range(10)]
    if kind == "RECON":
        sweep = sorted(n for n, k in NODES.items() if k in ("SERVER", "DATABASE", "FIREWALL") and n != target)
        return [(d, rnd.choice([80, 443]), 64) for d in sweep]
    raise ValueError(kind)


def window(rnd, attack=None):
    flows = []  # (dest, port, bytes, label)
    ids = sorted(NODES)
    for _ in range(5 + rnd.randrange(10)):
        src, dst = rnd.choice(ids), rnd.choice(ids)
        if src == dst: continue
        flows.append((dst, dest_port(rnd, NODES[dst]), normal_bytes(rnd, NODES[src]), 0))
    if attack:
        kind, target = attack
        flows += [(d, p, b, 1) for d, p, b in attack_flows(rnd, kind, target)]
    batches = {}
    for d, p, b, label in flows:
        batches.setdefault(d, []).append([p, b, label])
    return [{"node": d, "flows": batches[d]} for d in sorted(batches)]


def build():
    rnd = random.Random(SEED)
    plan = [None] * NORMAL_WINDOWS
    targets = sorted(NODES)
    for kind, n in ATTACK_WINDOWS.items():
        for i in range(n):
            plan.append((kind, targets[(i * 5 + len(kind)) % len(targets)]))
    rnd.shuffle(plan)
    batches = []
    for attack in plan:
        batches += window(rnd, attack)
    body = {"seed": SEED, "fields": ["dest_port", "bytes", "label"], "batches": batches}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"))
    body["dataset_id"] = "eval-v1-" + hashlib.sha256(canonical.encode()).hexdigest()[:12]
    return body


if __name__ == "__main__":
    ds = build()
    with open(OUT, "w", newline="\n") as f:
        json.dump(ds, f, sort_keys=True, separators=(",", ":"))
    n = sum(len(b["flows"]) for b in ds["batches"])
    attacks = sum(fl[2] for b in ds["batches"] for fl in b["flows"])
    print(f"{ds['dataset_id']}: {len(ds['batches'])} batches, {n} flows ({attacks} attack, {n - attacks} normal) -> {OUT}")
