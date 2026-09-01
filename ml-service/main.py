from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
from typing import List, Dict, Any
import uvicorn
from fastapi.responses import FileResponse
import tempfile
import csv

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
    X = extract_features(req.flow_data)
    
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
    X = extract_features(req.flow_data)
    
    predictions, scores = model.predict(X)
    
    # Get feature contributions for explainability
    contributions = []
    if model.is_fitted:
        from utils.feature_engineering import get_feature_contributions
        contributions = get_feature_contributions(model.model, X)
    
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

@app.get("/export")
def export(simulation_id: str = Query(...)):
    # Mocking database export for now
    fd, path = tempfile.mkstemp(suffix=".csv")
    with open(path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(["simulation_id", "flow_id", "status"])
        writer.writerow([simulation_id, "1", "mock_data"])
    return FileResponse(path, media_type="text/csv", filename=f"sim_{simulation_id}.csv")

@app.get("/health")
def health():
    return {"status": "ok"}
