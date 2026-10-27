from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Dict, Any, Optional
import base64
import hashlib
import os
import pickle
import time

from models.isolation_forest import AnomalyDetector
from federated.aggregator import FedAvgAggregator
from utils.feature_engineering import extract_features, get_feature_contributions
import evaluation

app = FastAPI(title="ML Service")

# Saved model state survives restarts. On hosts with an ephemeral disk (e.g. Render's free
# plan) point ML_STATE_PATH at a persistent disk, otherwise the file is lost on redeploy.
STATE_PATH = os.environ.get(
    "ML_STATE_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "state", "ml_state.pkl"))

# State
local_models: Dict[str, AnomalyDetector] = {}      # deployed per-node models (run the global version)
pending_models: Dict[str, AnomalyDetector] = {}    # locally trained candidates of the round in progress
aggregator = FedAvgAggregator()
global_model = AnomalyDetector()
model_checksum: Optional[str] = None
saved_at: Optional[str] = None
# Fixed-dataset evaluation of the deployed version, computed once when it is deployed
model_evaluation: Optional[dict] = None
EVAL_DATASET_ID, EVAL_BATCHES = evaluation.load_dataset()


def _checksum(params: dict) -> str:
    return hashlib.sha256(params["model_b64"].encode("utf-8")).hexdigest()[:16]


def save_state() -> bool:
    """Atomically write the current global model, version and per-node models to disk."""
    global saved_at
    stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    state = {
        "model_version": aggregator.model_version,
        "model_checksum": model_checksum,
        "model_evaluation": model_evaluation,
        "global_params": global_model.get_params(),
        "local_models": local_models,
        "saved_at": stamp,
    }
    try:
        os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
        tmp = STATE_PATH + ".tmp"
        with open(tmp, "wb") as f:
            pickle.dump(state, f)
        os.replace(tmp, STATE_PATH)  # the previous file stays intact if anything above fails
        saved_at = stamp
        return True
    except Exception as e:
        print(f"WARNING: could not save ML state to {STATE_PATH}: {e}", flush=True)
        return False


def load_state() -> None:
    """Load the saved model at startup instead of starting from an empty model."""
    global model_checksum, saved_at, model_evaluation
    if not os.path.exists(STATE_PATH):
        print(f"No saved ML state at {STATE_PATH}; starting without a global model", flush=True)
        return
    try:
        with open(STATE_PATH, "rb") as f:
            state = pickle.load(f)
        params = state["global_params"]
        if params and _checksum(params) != state["model_checksum"]:
            raise ValueError("checksum mismatch")
        if params:
            global_model.set_params(params)
        local_models.clear()
        local_models.update(state["local_models"])
        aggregator.model_version = int(state["model_version"])
        model_checksum = state["model_checksum"]
        model_evaluation = state.get("model_evaluation")
        saved_at = state.get("saved_at")
        print(f"Loaded ML state: model v{aggregator.model_version} checksum={model_checksum} "
              f"nodes={len(local_models)}", flush=True)
    except Exception as e:
        print(f"WARNING: saved ML state at {STATE_PATH} is unreadable ({e}); starting without it", flush=True)


load_state()


def model_info() -> dict:
    return {
        "model_version": aggregator.model_version,
        "model_checksum": model_checksum,
        "saved_at": saved_at,
        "nodes": sorted(local_models.keys()),
        "evaluation": model_evaluation,
    }


def model_for_node(node_id: str) -> AnomalyDetector:
    """The model a node's IDS uses: its deployed model, or the global model for a node without one."""
    det = local_models.get(node_id)
    if det is None:
        det = AnomalyDetector()
        if global_model.is_fitted:
            det.set_params(global_model.get_params())
            det.version = aggregator.model_version
    return det


def evaluate_deployed() -> Optional[dict]:
    if not global_model.is_fitted:
        return None
    result = evaluation.evaluate(model_for_node, EVAL_DATASET_ID, EVAL_BATCHES)
    if result is not None:
        result["model_version"] = aggregator.model_version
        result["model_checksum"] = model_checksum
    return result


@app.get("/")
def home():
    return {"status": "ML service is running"}

@app.get("/health")
def health():
    return {"status": "healthy", "model_version": aggregator.model_version, "model_checksum": model_checksum}

@app.get("/model")
def model():
    return model_info()

@app.get("/evaluate")
def evaluate_now():
    """Re-score the deployed version on the fixed dataset (read-only; nothing is stored)."""
    fresh = evaluate_deployed()
    return {"stored": model_evaluation, "recomputed": fresh,
            "matches": fresh is not None and model_evaluation is not None
                       and {k: fresh[k] for k in ("tp", "fp", "fn", "tn")} == {k: model_evaluation[k] for k in ("tp", "fp", "fn", "tn")}}

class TrainRequest(BaseModel):
    node_id: str
    flow_data: List[Dict[str, Any]]

class PredictRequest(BaseModel):
    node_id: str
    flow_data: List[Dict[str, Any]]

class FedAvgRequest(BaseModel):
    client_models: List[Dict[str, Any]]
    weights: List[float]
    base_version: int = 0  # latest version the caller has recorded; versions never go backwards

class BroadcastRequest(BaseModel):
    global_model_params: Dict[str, Any]

@app.post("/train")
def train(req: TrainRequest):
    # Train a candidate. The deployed model keeps serving predictions until a new global
    # model is successfully aggregated, so a failed round never leaves a half-updated node.
    X = extract_features(req.flow_data)
    if len(X) == 0:
        return {"model_params": {}, "training_metrics": {"n_samples": 0}}

    candidate = AnomalyDetector()
    metrics = candidate.fit(X)
    pending_models[req.node_id] = candidate
    return {
        "model_params": candidate.get_params(),
        "training_metrics": metrics
    }

@app.post("/predict")
def predict(req: PredictRequest):
    model = local_models.get(req.node_id)
    if model is None:  # node without its own model yet: start from the global one
        model = model_for_node(req.node_id)
        local_models[req.node_id] = model

    X = extract_features(req.flow_data)

    if len(X) == 0:
        return {"predictions": [], "model_version": None}

    predictions, scores = model.predict(X)

    # Get feature contributions for explainability
    contributions = []
    if model.is_fitted:
        # Explanations are only used for flagged flows, so only compute them there
        anomalous = [i for i in range(len(X)) if predictions[i] == -1]
        contributions = [{} for _ in range(len(X))]
        if anomalous:
            Xs = model.scale(X)
            for i, c in zip(anomalous, get_feature_contributions(model.model, Xs[anomalous])):
                contributions[i] = c

    # Which global model made these predictions (None: untrained or local-only model)
    version = getattr(model, "version", None) if model.is_fitted else None

    result = []
    for i, flow in enumerate(req.flow_data):
        pred = {
            "flow_id": flow.get("flow_id", str(i)),
            "anomaly_score": float(scores[i]),
            "is_anomaly": bool(predictions[i] == -1)
        }
        if i < len(contributions):
            pred["explanation"] = contributions[i].get("top_features", {})
        result.append(pred)

    return {"predictions": result, "model_version": version}


def apply_global(params: dict, version: int) -> List[str]:
    """Deploy the global model to every node (re-calibrating each on its own local data)."""
    updated = {}
    for node_id in set(local_models) | set(pending_models):
        det = pending_models.get(node_id) or local_models[node_id]
        det.set_params(params)
        det.version = version
        updated[node_id] = det
    local_models.update(updated)
    pending_models.clear()
    global_model.set_params(params)
    global_model.version = version
    return sorted(updated)


@app.post("/fedavg")
def fedavg(req: FedAvgRequest):
    global model_checksum, model_evaluation
    new_version = max(aggregator.model_version, req.base_version) + 1
    global_params = aggregator.aggregate(req.client_models, req.weights, next_version=new_version)
    if not global_params or "model_b64" not in global_params:
        pending_models.clear()
        # Last valid model and version stay in place
        raise HTTPException(status_code=422, detail="aggregation produced no model; previous model kept")

    updated = apply_global(global_params, new_version)
    aggregator.model_version = new_version  # advanced only after the model is in place
    model_checksum = _checksum(global_params)
    # Evaluate the newly deployed version once, on the fixed dataset, and keep it with the version
    model_evaluation = evaluate_deployed()
    persisted = save_state()

    return {
        "global_model_params": global_params,
        "model_version": new_version,
        "model_checksum": model_checksum,
        "updated_node_ids": updated,
        "persisted": persisted,
        "evaluation": model_evaluation,
    }

@app.post("/broadcast")
def broadcast(req: BroadcastRequest):
    # /fedavg already deploys each new model to every node. Re-deploying different parameters here
    # would change the model without a new version (and invalidate its evaluation), so only the
    # current model is accepted, as a no-op.
    if "model_b64" not in req.global_model_params or _checksum(req.global_model_params) != model_checksum:
        raise HTTPException(status_code=409, detail="only /fedavg may deploy a new model (it assigns the version)")
    return {"updated_node_ids": sorted(local_models), "model_version": aggregator.model_version}

# Flow export lives in the backend (/api/flows/export); the ML service never holds raw flows.
