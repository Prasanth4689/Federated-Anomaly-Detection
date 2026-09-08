import numpy as np
from sklearn.preprocessing import StandardScaler

# Feature names for explainability
FEATURE_NAMES = [
    "duration_ms", "packet_count", "total_bytes", "avg_packet_size",
    "packets_per_sec", "bytes_per_sec",
    "byte_variance", "port_diversity", "small_packet_ratio"
]

# Persistent scalers per node — fitted during training, reused during prediction
_scalers: dict[str, StandardScaler] = {}

def extract_features(flow_data: list[dict], node_id: str = None, fit_scaler: bool = False) -> np.ndarray:
    """
    Extract network flow features for anomaly detection.

    Base features: duration_ms, packet_count, total_bytes, avg_packet_size,
                   packets_per_sec, bytes_per_sec
    Derived features: byte_variance, port_diversity, small_packet_ratio
    """
    if not flow_data:
        return np.zeros((0, len(FEATURE_NAMES)))

    features = []

    # Compute batch-level statistics for derived features
    all_dest_ports = [int(flow.get("dest_port", 0) or 0) for flow in flow_data]
    unique_ports = len(set(all_dest_ports)) if all_dest_ports else 1
    total_flows = max(1, len(flow_data))
    batch_port_diversity = unique_ports / total_flows  # 0-1 range

    # Compute batch-level mean avg_packet_size for variance calc
    avg_sizes = [float(flow.get("avg_packet_size", 0) or 0) for flow in flow_data]
    batch_mean_size = np.mean(avg_sizes) if avg_sizes else 0.0

    for flow in flow_data:
        duration = float(flow.get("duration_ms", 0) or 0)
        packet_count = float(flow.get("packet_count", 0) or 0)
        total_bytes = float(flow.get("total_bytes", 0) or 0)
        avg_packet_size = float(flow.get("avg_packet_size", 0) or 0)
        packets_per_sec = float(flow.get("packets_per_sec", 0) or 0)
        bytes_per_sec = float(flow.get("bytes_per_sec", 0) or 0)

        # Derived features — now computed from real data
        # byte_variance: deviation of this flow's avg_packet_size from batch mean
        byte_variance = abs(avg_packet_size - batch_mean_size) if packet_count > 0 else 0.0

        # port_diversity: batch-level ratio of unique dest ports to total flows
        port_diversity = batch_port_diversity

        # small_packet_ratio: fraction indicating if traffic is mostly small packets
        small_packet_ratio = 1.0 if avg_packet_size < 100 and packet_count > 10 else 0.0

        row = [
            duration, packet_count, total_bytes, avg_packet_size,
            packets_per_sec, bytes_per_sec,
            byte_variance, port_diversity, small_packet_ratio
        ]
        features.append(row)

    X = np.array(features, dtype=np.float64)

    # Handle NaN/Inf
    X = np.nan_to_num(X, nan=0.0, posinf=0.0, neginf=0.0)

    # Scaling: use persisted scaler if available
    scaler_key = node_id or "__global__"

    if fit_scaler:
        # Training: fit and store scaler
        if len(X) > 1:
            scaler = StandardScaler()
            X = scaler.fit_transform(X)
            _scalers[scaler_key] = scaler
    else:
        # Prediction: reuse fitted scaler if available
        if scaler_key in _scalers:
            X = _scalers[scaler_key].transform(X)
        elif "__global__" in _scalers:
            X = _scalers["__global__"].transform(X)
        elif len(X) > 1:
            # Fallback: fit new scaler (first prediction before any training)
            scaler = StandardScaler()
            X = scaler.fit_transform(X)

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
