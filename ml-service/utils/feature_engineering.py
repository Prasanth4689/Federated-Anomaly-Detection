import numpy as np
from sklearn.preprocessing import StandardScaler

# Feature names for explainability
FEATURE_NAMES = [
    "duration_ms", "packet_count", "total_bytes", "avg_packet_size",
    "packets_per_sec", "bytes_per_sec",
    "byte_variance", "port_diversity", "small_packet_ratio"
]

def extract_features(flow_data: list[dict]) -> np.ndarray:
    """
    Extract network flow features for anomaly detection.
    
    Base features: duration_ms, packet_count, total_bytes, avg_packet_size,
                   packets_per_sec, bytes_per_sec
    Derived features: byte_variance, port_diversity, small_packet_ratio
    """
    features = []
    for flow in flow_data:
        duration = float(flow.get("duration_ms", 0) or 0)
        packet_count = float(flow.get("packet_count", 0) or 0)
        total_bytes = float(flow.get("total_bytes", 0) or 0)
        avg_packet_size = float(flow.get("avg_packet_size", 0) or 0)
        packets_per_sec = float(flow.get("packets_per_sec", 0) or 0)
        bytes_per_sec = float(flow.get("bytes_per_sec", 0) or 0)

        # Derived features
        byte_variance = abs(total_bytes - avg_packet_size * packet_count) if packet_count > 0 else 0
        port_diversity = 1.0  # placeholder; could be computed from dest_port spread
        small_packet_ratio = 1.0 if avg_packet_size < 100 and packet_count > 10 else 0.0

        row = [
            duration, packet_count, total_bytes, avg_packet_size,
            packets_per_sec, bytes_per_sec,
            byte_variance, port_diversity, small_packet_ratio
        ]
        features.append(row)
        
    if not features:
        return np.zeros((0, len(FEATURE_NAMES)))
        
    X = np.array(features)
    if len(X) > 1:
        X = StandardScaler().fit_transform(X)
    return X


def get_feature_contributions(model, X: np.ndarray) -> list[dict]:
    """
    Compute per-feature anomaly contribution using Isolation Forest path lengths.
    This is a lightweight alternative to SHAP for explainability.
    """
    if not hasattr(model, 'estimators_') or len(X) == 0:
        return []

    contributions = []
    for i in range(len(X)):
        sample = X[i].reshape(1, -1)
        
        # Get the anomaly score
        score = float(model.score_samples(sample)[0])
        
        # Compute feature importance via perturbation
        feature_impacts = {}
        for j, name in enumerate(FEATURE_NAMES[:X.shape[1]]):
            perturbed = sample.copy()
            perturbed[0, j] = 0  # zero out feature
            perturbed_score = float(model.score_samples(perturbed)[0])
            impact = abs(score - perturbed_score)
            feature_impacts[name] = round(impact, 4)
        
        # Sort by impact (descending)
        sorted_impacts = dict(sorted(feature_impacts.items(), key=lambda x: x[1], reverse=True))
        
        contributions.append({
            "anomaly_score": round(score, 4),
            "top_features": dict(list(sorted_impacts.items())[:3]),  # top 3
            "all_features": sorted_impacts
        })
    
    return contributions
