import numpy as np
from sklearn.ensemble import IsolationForest
import pickle
import base64

# Every client builds trees from the same sub-sample size, so pooled trees share one
# path-length normaliser in the aggregated forest (mixed sizes inflate anomaly scores).
COMMON_MAX_SAMPLES = 64


class AnomalyDetector:
    def __init__(self, contamination=0.1, n_estimators=100):
        self.contamination = contamination
        self.model = IsolationForest(
            contamination=contamination,
            n_estimators=n_estimators,
            random_state=42
        )
        self.is_fitted = False
        # This node's own (already scaled) training features. Stays local; used only
        # to calibrate the global model's threshold on this node.
        self._local_X = None

    def fit(self, X: np.ndarray) -> dict:
        self.model.set_params(max_samples=min(COMMON_MAX_SAMPLES, len(X)))
        self.model.fit(X)
        self.is_fitted = True
        self._local_X = X
        # Unsupervised model: no labels here, so no accuracy. Real metrics are
        # computed by the backend from predictions vs. simulated ground truth.
        return {
            "n_samples": len(X),
            "mean_score": float(np.mean(self.model.score_samples(X)))
        }

    def predict(self, X: np.ndarray) -> tuple:
        if not self.is_fitted:
            # Return zeros/ones if not fitted to avoid crashing
            return np.ones(len(X)), np.zeros(len(X))

        # One model pass: same rule as IsolationForest.predict (score - offset_ < 0 -> anomaly)
        scores = self.model.score_samples(X)
        preds = np.where(scores - self.model.offset_ < 0, -1, 1)
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
            self._calibrate_threshold()

    def _calibrate_threshold(self):
        """Set the global model's decision threshold from this node's local data.

        The aggregated offset is an average of client thresholds and does not match
        the pooled forest's score distribution. Re-deriving it locally keeps the
        flag rate near `contamination` without sharing any raw data.
        """
        if self._local_X is None or len(self._local_X) < 10:
            return
        scores = self.model.score_samples(self._local_X)
        self.model.offset_ = float(np.percentile(scores, 100.0 * self.contamination))
