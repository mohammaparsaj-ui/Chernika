"""Behavioral-cloning policy for teacher-selected pocket, angle and power."""
from __future__ import annotations

from pathlib import Path
import numpy as np
import torch
from torch import nn

DEFAULT_OBSERVATION_SIZE = 16
OBSERVATION_SIZE = DEFAULT_OBSERVATION_SIZE
POCKET_COUNT = 6


class ImitationPolicy(nn.Module):
    """Shared MLP with a pocket-selection head and continuous shot head."""
    def __init__(
        self,
        hidden_size: int = 128,
        observation_size: int = DEFAULT_OBSERVATION_SIZE,
    ):
        super().__init__()
        if observation_size < 1:
            raise ValueError("observation_size must be positive")
        self.observation_size = int(observation_size)
        self.backbone = nn.Sequential(
            nn.Linear(self.observation_size, hidden_size),
            nn.ReLU(),
            nn.Linear(hidden_size, hidden_size),
            nn.ReLU(),
        )
        self.pocket_head = nn.Linear(hidden_size, POCKET_COUNT)
        self.action_head = nn.Linear(hidden_size, 2)

    def forward(self, observation: torch.Tensor) -> torch.Tensor:
        features = self.backbone(observation)
        pocket_logits = self.pocket_head(features)
        action = torch.tanh(self.action_head(features))
        return torch.cat((pocket_logits, action), dim=-1)


def load_policy(path: str | Path, device: str = "cpu") -> ImitationPolicy:
    checkpoint = torch.load(Path(path), map_location=device, weights_only=True)
    policy = ImitationPolicy(
        hidden_size=int(checkpoint.get("hidden_size", 128)),
        observation_size=int(
            checkpoint.get("observation_size", DEFAULT_OBSERVATION_SIZE)
        ),
    )
    policy.load_state_dict(checkpoint["state_dict"])
    policy.to(device)
    policy.eval()
    return policy


def predict_top_pockets(
    policy: ImitationPolicy,
    observation: np.ndarray,
    top_k: int = 2,
    device: str = "cpu",
) -> tuple[np.ndarray, list[int]]:
    """Return the continuous action and ranked pocket candidates."""
    if not 1 <= top_k <= POCKET_COUNT:
        raise ValueError(f"top_k must be in [1, {POCKET_COUNT}]")
    values = torch.as_tensor(
        np.asarray(observation, dtype=np.float32),
        dtype=torch.float32,
        device=device,
    ).reshape(1, -1)
    with torch.no_grad():
        output = policy(values)[0]
    pocket_indices = torch.topk(output[:POCKET_COUNT], k=top_k).indices
    action = output[POCKET_COUNT:].cpu().numpy().astype(np.float32)
    return np.clip(action, -1.0, 1.0), [
        int(index) for index in pocket_indices.cpu().tolist()
    ]


def predict(
    policy: ImitationPolicy,
    observation: np.ndarray,
    device: str = "cpu",
) -> tuple[np.ndarray, int]:
    action, pockets = predict_top_pockets(
        policy, observation, top_k=1, device=device
    )
    return action, pockets[0]
