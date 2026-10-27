import pickle
import base64
import numpy as np

class FedAvgAggregator:
    def __init__(self):
        self.model_version = 0
        self.round_history = []
        
    def aggregate(self, client_params: list[dict], weights: list[float], next_version: int = None) -> dict:
        """
        Trust-Weighted FedAvg:
        - Each client's contribution is scaled by its trust weight (0-1 range, derived from trust score / 100)
        - Higher trust nodes contribute more to the global model
        - Low-trust nodes (potentially compromised) have minimal impact
        """
        # The version is only advanced by the caller once the new model has been applied
        # (a failed or empty aggregation must not consume a version number).
        version = next_version if next_version is not None else self.model_version + 1

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
        # Select base model with highest capacity
        global_model = max(models, key=lambda m: getattr(m, "max_samples_", 0))
        combined_estimators = []
        combined_features = []
        combined_decision_path_lengths = []
        combined_avg_path_lengths = []
        
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
                    if hasattr(model, "_decision_path_lengths") and idx < len(model._decision_path_lengths):
                        combined_decision_path_lengths.append(model._decision_path_lengths[idx])
                    if hasattr(model, "_average_path_length_per_tree") and idx < len(model._average_path_length_per_tree):
                        combined_avg_path_lengths.append(model._average_path_length_per_tree[idx])
                
        if combined_estimators:
            global_model.estimators_ = combined_estimators
            global_model.estimators_features_ = combined_features
            global_model.n_estimators = len(combined_estimators)
            if combined_decision_path_lengths:
                global_model._decision_path_lengths = combined_decision_path_lengths
            if combined_avg_path_lengths:
                global_model._average_path_length_per_tree = combined_avg_path_lengths
            if hasattr(global_model, "offset_"):
                global_model.offset_ = float(sum(getattr(m, "offset_", -0.5) * w for m, w in zip(models, valid_weights)))
            if hasattr(global_model, "_max_samples"):
                global_model._max_samples = max(getattr(m, "_max_samples", 256) for m in models)
            
        # Record round metadata
        self.round_history.append({
            "version": version,
            "n_participants": len(models),
            "weights": valid_weights,
            "n_trees": len(combined_estimators)
        })
            
        global_model_bytes = pickle.dumps(global_model)
        
        return {
            "model_b64": base64.b64encode(global_model_bytes).decode('utf-8'),
            "round_info": {
                "version": version,
                "participants": len(models),
                "trust_weights": valid_weights,
                "total_trees": len(combined_estimators)
            }
        }
