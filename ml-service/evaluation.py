"""Fixed-dataset evaluation of a deployed global model version.

The dataset (eval/eval_dataset.json) is frozen and identified by a content hash. Scoring the same
deployed models on it always gives the same result, so a version's accuracy only changes when the
model or the dataset changes.
"""
import hashlib
import json
import os

from utils.feature_engineering import extract_features

DATASET_PATH = os.environ.get(
    "ML_EVAL_DATASET", os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval", "eval_dataset.json"))


def load_dataset(path=DATASET_PATH):
    """Returns (dataset_id, batches) or (None, None) if the file is missing or altered."""
    try:
        with open(path, "r", encoding="utf-8") as f:
            ds = json.load(f)
        body = {k: v for k, v in ds.items() if k != "dataset_id"}
        canonical = json.dumps(body, sort_keys=True, separators=(",", ":"))
        expected = "eval-v1-" + hashlib.sha256(canonical.encode()).hexdigest()[:12]
        if ds.get("dataset_id") != expected:
            print(f"WARNING: evaluation dataset {path} does not match its id; evaluation disabled", flush=True)
            return None, None
        return ds["dataset_id"], ds["batches"]
    except Exception as e:
        print(f"WARNING: evaluation dataset unavailable ({e}); evaluation disabled", flush=True)
        return None, None


def _flow(port, nbytes):
    # Same fields the backend sends for a single-packet flow
    return {"duration_ms": 0, "packet_count": 1, "total_bytes": nbytes, "avg_packet_size": nbytes,
            "packets_per_sec": 0, "bytes_per_sec": 0, "dest_port": port}


def evaluate(model_for_node, dataset_id, batches):
    """Score each batch with the model deployed on its destination node (as the live IDS does)."""
    if not dataset_id:
        return None
    import numpy as np
    tp = fp = fn = tn = 0
    by_node = {}
    for batch in batches:
        by_node.setdefault(batch["node"], []).append(batch["flows"])
    for node, node_batches in sorted(by_node.items()):
        det = model_for_node(node)
        # Batch features and scaling exactly as the live IDS computes them per batch; the scaled
        # rows are then scored in one model call (Isolation Forest scores rows independently).
        Xs = np.vstack([det.scale(extract_features([_flow(p, b) for p, b, _ in fl])) for fl in node_batches])
        labels = [label for fl in node_batches for _, _, label in fl]
        preds, _ = det.predict_scaled(Xs)
        for label, pred in zip(labels, preds):
            flagged = bool(pred == -1)
            if label: tp, fn = tp + flagged, fn + (not flagged)
            else: fp, tn = fp + flagged, tn + (not flagged)
    n = tp + fp + fn + tn
    pct = lambda a, b: round(100.0 * a / b, 1) if b else 0.0
    precision, recall = pct(tp, tp + fp), pct(tp, tp + fn)
    f1 = round(2 * precision * recall / (precision + recall), 1) if precision + recall else 0.0
    return {"dataset_id": dataset_id, "n": int(n), "tp": int(tp), "fp": int(fp), "fn": int(fn), "tn": int(tn),
            "accuracy": pct(tp + tn, n), "precision": precision, "recall": recall, "f1": f1}
