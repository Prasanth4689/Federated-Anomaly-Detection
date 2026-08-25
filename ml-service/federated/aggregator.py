import pickle
import base64
import numpy as np

class FedAvgAggregator:
    def __init__(self):
        self.model_version = 0
        self.round_history = []
        
    def aggregate(self, client_params: list[dict], weights: list[float]) -> dict:
        """
        Trust-Weighted FedAvg:
        - Each client's contribution is scaled by its trust weight (0-1 range, derived from trust score / 100)
        - Higher trust nodes contribute more to the global model
        - Low-trust nodes (potentially compromised) have minimal impact
        """
        self.model_version += 1
        
        if not client_params or not weights:
            return {}
        
        # Normalize trust weights
        total_weight = sum(weights)
        if total_weight == 0:
            norm_weights = [1.0 / len(weights)] * len(weights)
        else:
            norm_weights = [w / total_weight for w in weights]
            
        models = []
        valid_weights = []
        for params, weight in zip(client_params, norm_weights):
            if "model_b64" in params:
                model_bytes = base64.b64decode(params["model_b64"])
                model = pickle.loads(model_bytes)
                models.append(model)
                valid_weights.append(weight)
                
        if not models:
            return {}
            
        # Trust-Weighted tree selection:
        # Sample trees from each model proportional to its trust weight
        global_model = models[0]
        combined_estimators = []
        combined_features = []
        
        target_n_trees = 100  # Target number of trees in the global model
        
        for model, weight in zip(models, valid_weights):
            if hasattr(model, "estimators_"):
                n_trees_from_client = max(1, int(weight * target_n_trees))
                # Sample trees weighted by trust
                n_available = len(model.estimators_)
                indices = np.random.choice(
                    n_available, 
                    size=min(n_trees_from_client, n_available), 
                    replace=n_trees_from_client > n_available
                )
                for idx in indices:
                    combined_estimators.append(model.estimators_[idx])
                    combined_features.append(model.estimators_features_[idx])
                
        if combined_estimators:
            global_model.estimators_ = combined_estimators
            global_model.estimators_features_ = combined_features
            global_model.n_estimators = len(combined_estimators)
            
        # Record round metadata
        self.round_history.append({
            "version": self.model_version,
            "n_participants": len(models),
            "weights": valid_weights,
            "n_trees": len(combined_estimators)
        })
            
        global_model_bytes = pickle.dumps(global_model)
        
        return {
            "model_b64": base64.b64encode(global_model_bytes).decode('utf-8'),
            "round_info": {
                "version": self.model_version,
                "participants": len(models),
                "trust_weights": valid_weights,
                "total_trees": len(combined_estimators)
            }
        }
