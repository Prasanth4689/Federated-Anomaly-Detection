from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Dict, Any

from models.isolation_forest import AnomalyDetector
from federated.aggregator import FedAvgAggregator
from utils.feature_engineering import extract_features

app = FastAPI(title="ML Service")

@app.get("/")
def home():
    return {"status": "ML service is running"}

@app.get("/health")
def health():
    return {"status": "healthy"}

# State
local_models: Dict[str, AnomalyDetector] = {}
aggregator = FedAvgAggregator()
global_model = AnomalyDetector()

class TrainRequest(BaseModel):
    node_id: str
    flow_data: List[Dict[str, Any]]

class PredictRequest(BaseModel):
    node_id: str
    flow_data: List[Dict[str, Any]]

class FedAvgRequest(BaseModel):
    client_models: List[Dict[str, Any]]
    weights: List[float]

class BroadcastRequest(BaseModel):
    global_model_params: Dict[str, Any]

@app.post("/train")
def train(req: TrainRequest):
    if req.node_id not in local_models:
        local_models[req.node_id] = AnomalyDetector()
    
    model = local_models[req.node_id]
    X = extract_features(req.flow_data, node_id=req.node_id, fit_scaler=True)
    
    if len(X) == 0:
        return {"model_params": {}, "training_metrics": {"n_samples": 0}}
    
    metrics = model.fit(X)
    return {
        "model_params": model.get_params(),
        "training_metrics": metrics
    }

@app.post("/predict")
def predict(req: PredictRequest):
    if req.node_id not in local_models:
        local_models[req.node_id] = AnomalyDetector()
    
    model = local_models[req.node_id]
    X = extract_features(req.flow_data, node_id=req.node_id, fit_scaler=False)
    
    if len(X) == 0:
        return {"predictions": []}
    
    predictions, scores = model.predict(X)
    
    # Get feature contributions for explainability
    contributions = []
    if model.is_fitted:
        from utils.feature_engineering import get_feature_contributions
        # Explanations are only used for flagged flows, so only compute them there
        anomalous = [i for i in range(len(X)) if predictions[i] == -1]
        contributions = [{} for _ in range(len(X))]
        if anomalous:
            for i, c in zip(anomalous, get_feature_contributions(model.model, X[anomalous])):
                contributions[i] = c
    
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
        
    return {"predictions": result}

@app.post("/fedavg")
def fedavg(req: FedAvgRequest):
    global_params = aggregator.aggregate(req.client_models, req.weights)
    global_model.set_params(global_params)
    
    return {
        "global_model_params": global_params,
        "model_version": aggregator.model_version
    }

@app.post("/broadcast")
def broadcast(req: BroadcastRequest):
    updated_nodes = []
    for node_id, model in local_models.items():
        model.set_params(req.global_model_params)
        updated_nodes.append(node_id)
        
    return {"updated_node_ids": updated_nodes}

# Flow export lives in the backend (/api/flows/export); the ML service never holds raw flows.
