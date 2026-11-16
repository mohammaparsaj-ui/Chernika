"""A separate small imitation model for the nine-ball break action."""
from __future__ import annotations

from pathlib import Path
import numpy as np
import torch
from torch import nn

OBSERVATION_SIZE = 52


class BreakPolicy(nn.Module):
    """Predict normalized break angle offset and power from a rack observation."""

    def __init__(self, hidden_size: int = 128, observation_size: int = OBSERVATION_SIZE):
        super().__init__()
        if observation_size != OBSERVATION_SIZE:
            raise ValueError(f"break policy expects {OBSERVATION_SIZE} inputs")
        self.observation_size = observation_size
        self.network = nn.Sequential(
            nn.Linear(observation_size, hidden_size),
            nn.ReLU(),
            nn.Linear(hidden_size, hidden_size),
            nn.ReLU(),
            nn.Linear(hidden_size, 2),
            nn.Tanh(),
        )

    def forward(self, observation: torch.Tensor) -> torch.Tensor:
        return self.network(observation)


def load_break_policy(path: str | Path, device: str = "cpu") -> BreakPolicy:
    checkpoint = torch.load(Path(path), map_location=device, weights_only=True)
    policy = BreakPolicy(
        hidden_size=int(checkpoint.get("hidden_size", 128)),
        observation_size=int(checkpoint.get("observation_size", OBSERVATION_SIZE)),
    )
    policy.load_state_dict(checkpoint["state_dict"])
    policy.to(device)
    policy.eval()
    return policy


def predict_break_action(
    policy: BreakPolicy,
    observation: np.ndarray,
    device: str = "cpu",
) -> np.ndarray:
    values = torch.as_tensor(
        np.asarray(observation, dtype=np.float32),
        dtype=torch.float32,
        device=device,
    ).reshape(1, -1)
    if values.shape[1] != OBSERVATION_SIZE:
        raise ValueError(f"Expected {OBSERVATION_SIZE} observation values")
    with torch.no_grad():
        action = policy(values)[0].cpu().numpy().astype(np.float32)
    return np.clip(action, -1.0, 1.0)
