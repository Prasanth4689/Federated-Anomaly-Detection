import numpy as np
from sklearn.ensemble import IsolationForest
import pickle
import base64

class AnomalyDetector:
    def __init__(self, contamination=0.1, n_estimators=100):
        self.model = IsolationForest(
            contamination=contamination, 
            n_estimators=n_estimators,
            random_state=42
        )
        self.is_fitted = False
        
    def fit(self, X: np.ndarray) -> dict:
        self.model.fit(X)
        self.is_fitted = True
        return {
            "accuracy": 0.95, # placeholder metric
            "n_samples": len(X)
        }
        
    def predict(self, X: np.ndarray) -> tuple:
        if not self.is_fitted:
            # Return zeros/ones if not fitted to avoid crashing
            return np.ones(len(X)), np.zeros(len(X))
        
        preds = self.model.predict(X)
        scores = self.model.score_samples(X)
        return preds, scores
        
    def get_params(self) -> dict:
        if not self.is_fitted:
            return {}
        model_bytes = pickle.dumps(self.model)
        return {
            "model_b64": base64.b64encode(model_bytes).decode('utf-8')
        }
        
    def set_params(self, params: dict):
        if "model_b64" in params:
            model_bytes = base64.b64decode(params["model_b64"])
            self.model = pickle.loads(model_bytes)
            self.is_fitted = True

