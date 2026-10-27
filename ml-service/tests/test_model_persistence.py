"""Model persistence and versioning. Run from ml-service/:  python -m pytest -q tests"""
import importlib
import os
import random
import sys

import numpy as np
import pytest
from fastapi import HTTPException

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def flows(n, seed, small=False):
    rnd = random.Random(seed)
    out = []
    for i in range(n):
        b = rnd.randint(32, 63) if small else rnd.randint(128, 1128)
        out.append({"flow_id": f"{seed}-{i}", "duration_ms": 0, "packet_count": 1, "total_bytes": b,
                    "avg_packet_size": b, "packets_per_sec": 0, "bytes_per_sec": 0,
                    "dest_port": rnd.choice([80, 443, 3306, 1883]), "batch_key": f"w{i // 3}"})
    return out


@pytest.fixture
def ml(tmp_path, monkeypatch):
    monkeypatch.setenv("ML_STATE_PATH", str(tmp_path / "ml_state.pkl"))

    def start():  # (re)start the service: a fresh import loads whatever state is on disk
        sys.modules.pop("main", None)
        return importlib.import_module("main")
    return start


def run_round(m, base_version=0, nodes=("a", "b", "c")):
    params, weights = [], []
    for k, node in enumerate(nodes):
        r = m.train(m.TrainRequest(node_id=node, flow_data=flows(120, seed=k)))
        params.append(r["model_params"])
        weights.append(1.0)
    return m.fedavg(m.FedAvgRequest(client_models=params, weights=weights, base_version=base_version))


def test_round_saves_and_restart_loads_same_model(ml):
    m = ml()
    assert m.aggregator.model_version == 0
    r = run_round(m)
    assert r["model_version"] == 1 and r["persisted"] is True
    probe = flows(30, seed=99) + flows(10, seed=98, small=True)
    before = m.predict(m.PredictRequest(node_id="a", flow_data=probe))
    assert before["model_version"] == 1

    m2 = ml()  # restart
    assert m2.aggregator.model_version == 1, "restart must not reset or advance the version"
    assert m2.model_checksum == r["model_checksum"]
    after = m2.predict(m2.PredictRequest(node_id="a", flow_data=probe))
    assert after["model_version"] == 1
    assert [p["anomaly_score"] for p in after["predictions"]] == pytest.approx(
        [p["anomaly_score"] for p in before["predictions"]])
    assert [p["is_anomaly"] for p in after["predictions"]] == [p["is_anomaly"] for p in before["predictions"]]


def test_failed_aggregation_keeps_model_and_version(ml):
    m = ml()
    r = run_round(m)
    m.train(m.TrainRequest(node_id="a", flow_data=flows(50, seed=7)))  # candidate staged
    with pytest.raises(HTTPException):
        m.fedavg(m.FedAvgRequest(client_models=[{}], weights=[1.0], base_version=1))
    assert m.aggregator.model_version == 1 and m.model_checksum == r["model_checksum"]
    assert m.local_models["a"].version == 1, "deployed model must survive a failed round"
    assert ml().aggregator.model_version == 1  # nothing new persisted


def test_version_never_goes_backwards(ml):
    m = ml()
    r = run_round(m, base_version=7)  # caller already recorded v7 (e.g. ML state was lost)
    assert r["model_version"] == 8
    assert run_round(m, base_version=0)["model_version"] == 9


def test_unreadable_state_starts_fresh(ml, tmp_path):
    (tmp_path / "ml_state.pkl").write_bytes(b"not a pickle")
    m = ml()
    assert m.aggregator.model_version == 0 and not m.global_model.is_fitted


def test_untrained_predictions_have_no_version(ml):
    m = ml()
    r = m.predict(m.PredictRequest(node_id="x", flow_data=flows(5, seed=1)))
    assert r["model_version"] is None
