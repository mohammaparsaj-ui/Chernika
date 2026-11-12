"""Behavioral-cloning policy for teacher-selected pocket, angle and power."""
from __future__ import annotations

from pathlib import Path
import numpy as np
import torch
from torch import nn

OBSERVATION_SIZE = 16
POCKET_COUNT = 6


class ImitationPolicy(nn.Module):
    """Shared MLP with a pocket-selection head and continuous shot head."""
    def __init__(self, hidden_size: int = 128):
        super().__init__()
        self.backbone = nn.Sequential(
            nn.Linear(OBSERVATION_SIZE, hidden_size),
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
    policy = ImitationPolicy(hidden_size=int(checkpoint.get("hidden_size", 128)))
    policy.load_state_dict(checkpoint["state_dict"])
    policy.to(device)
    policy.eval()
    return policy


def predict(policy: ImitationPolicy, observation: np.ndarray,
            device: str = "cpu") -> tuple[np.ndarray, int]:
    values = torch.as_tensor(np.asarray(observation, dtype=np.float32),
                             dtype=torch.float32, device=device).reshape(1, -1)
    with torch.no_grad():
        output = policy(values)[0]
    pocket_index = int(torch.argmax(output[:POCKET_COUNT]).item())
    action = output[POCKET_COUNT:].cpu().numpy().astype(np.float32)
    return np.clip(action, -1.0, 1.0), pocket_index
