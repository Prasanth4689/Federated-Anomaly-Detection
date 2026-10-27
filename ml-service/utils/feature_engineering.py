import numpy as np

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

    # Unscaled: each node's AnomalyDetector owns (and persists) its scaler
    return X


def get_feature_contributions(model, X: np.ndarray) -> list[dict]:
    """
    Compute per-feature anomaly contribution using Isolation Forest path lengths.
    This is a lightweight alternative to SHAP for explainability.
    """
    if not hasattr(model, 'estimators_') or len(X) == 0:
        return []

    # Batched: every perturbed copy (one per feature, that feature zeroed) is scored in a
    # single score_samples call. Same values as scoring each sample separately; each model
    # call has a fixed ~20 ms overhead, so this keeps explanations cheap.
    names = FEATURE_NAMES[:X.shape[1]]
    base_scores = model.score_samples(X)
    perturbed = np.repeat(X[np.newaxis, :, :], len(names), axis=0)  # (features, rows, cols)
    for j in range(len(names)):
        perturbed[j, :, j] = 0  # zero out feature j
    pert_scores = model.score_samples(perturbed.reshape(-1, X.shape[1])).reshape(len(names), len(X))
    impacts = np.abs(base_scores[np.newaxis, :] - pert_scores).T  # (rows, features)

    contributions = []
    for i in range(len(X)):
        score = float(base_scores[i])
        feature_impacts = {name: round(float(impacts[i, j]), 4) for j, name in enumerate(names)}

        # Sort by impact (descending)
        sorted_impacts = dict(sorted(feature_impacts.items(), key=lambda x: x[1], reverse=True))

        contributions.append({
            "anomaly_score": round(score, 4),
            "top_features": dict(list(sorted_impacts.items())[:3]),  # top 3
            "all_features": sorted_impacts
        })

    return contributions
