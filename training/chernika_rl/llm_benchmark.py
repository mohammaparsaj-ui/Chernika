"""Benchmark raw LLM shot proposals against Chernika's physics simulator.

This is a structured-state test: every model receives the same coordinates and
must return angle offset + power. Vision and tool use are deliberately excluded
so perception is not mixed into the first reasoning benchmark.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import time
from pathlib import Path

import numpy as np
from litellm import completion

from .env import ChernikaSingleBallEnv
from .physics import (
    POCKETS, TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_BOTTOM,
    BALL_RADIUS_PX, MAX_UI_POWER_PERCENT,
)

SYSTEM_PROMPT = """You are a deterministic billiards shot-parameter agent.
Return exactly one JSON object and no markdown or explanation.
Use screen coordinates: x increases right and y increases down.
Choose a pocket, then compute the ghost-ball center as target_center minus
unit_vector(target_center -> pocket_center) times (2 * ball_radius + 1 px).
Aim the cue ball at that ghost-ball center. The required angle_offset_rad is
wrap_pi(shot_angle - direct_cue_to_target_angle), in radians, in [-pi, pi].
Choose power_percent from 0 to 97.8: enough to pot, but avoid excessive power.
Do not invent coordinates. If no direct shot is viable, choose the safest
reasonable direct pot attempt. Output schema:
{"pocket":"top_right","angle_offset_rad":0.0,"power_percent":45.0}
"""


def _provider_config(provider: str, model: str) -> tuple[str, dict]:
    if provider == "openai":
        key = os.getenv("OPENAI_API_KEY")
        if not key:
            raise RuntimeError("Set OPENAI_API_KEY")
        return f"openai/{model}", {"api_key": key}
    if provider == "anthropic":
        key = os.getenv("ANTHROPIC_API_KEY")
        if not key:
            raise RuntimeError("Set ANTHROPIC_API_KEY")
        return f"anthropic/{model}", {"api_key": key}
    if provider == "deepseek":
        key = os.getenv("DEEPSEEK_API_KEY")
        if not key:
            raise RuntimeError("Set DEEPSEEK_API_KEY")
        return f"openai/{model}", {
            "api_key": key,
            "api_base": os.getenv("DEEPSEEK_API_BASE", "https://api.deepseek.com"),
        }
    if provider == "dashscope":
        key = os.getenv("DASHSCOPE_API_KEY")
        if not key:
            raise RuntimeError("Set DASHSCOPE_API_KEY")
        return f"openai/{model}", {
            "api_key": key,
            "api_base": os.getenv(
                "DASHSCOPE_API_BASE",
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            ),
        }
    raise ValueError(f"Unsupported provider: {provider}")


def _extract_json(text: str) -> dict:
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            raise ValueError("Model did not return a JSON object")
        value = json.loads(match.group(0))
    if not isinstance(value, dict):
        raise ValueError("Model output must be a JSON object")
    return value


def _state_prompt(env: ChernikaSingleBallEnv) -> str:
    assert env.cue is not None and env.target is not None
    state = {
        "table_bounds_px": [TABLE_LEFT, TABLE_TOP, TABLE_RIGHT, TABLE_BOTTOM],
        "ball_radius_px": BALL_RADIUS_PX,
        "max_power_percent": MAX_UI_POWER_PERCENT,
        "cue_ball": {"x": env.cue.x, "y": env.cue.y},
        "target_ball": {"number": env.target.number,
                        "x": env.target.x, "y": env.target.y},
        "pockets": [
            {"name": p.name, "x": p.x, "y": p.y,
             "opening_width": p.opening_width, "opening_height": p.opening_height}
            for p in POCKETS
        ],
    }
    return "Choose and parameterize one shot for this state. Return only the required JSON.\n" + json.dumps(state)


def _usage_value(usage, name: str) -> int:
    if usage is None:
        return 0
    if isinstance(usage, dict):
        return int(usage.get(name, 0) or 0)
    return int(getattr(usage, name, 0) or 0)


def _call_model(model_name: str, kwargs: dict, prompt: str, timeout: int):
    started = time.perf_counter()
    response = completion(
        model=model_name,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        temperature=0.0,
        max_tokens=256,
        timeout=timeout,
        **kwargs,
    )
    latency = time.perf_counter() - started
    message = response.choices[0].message
    content = message.content
    if isinstance(content, list):
        content = "".join(getattr(part, "text", str(part)) for part in content)
    usage = getattr(response, "usage", None)
    return str(content), latency, {
        "prompt_tokens": _usage_value(usage, "prompt_tokens"),
        "completion_tokens": _usage_value(usage, "completion_tokens"),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", required=True,
                        choices=["openai", "anthropic", "deepseek", "dashscope"])
    parser.add_argument("--model", required=True,
                        help="Official model ID, e.g. gpt-6-astra or claude-opus-5-5")
    parser.add_argument("--episodes", type=int, default=20,
                        help="LLM calls are billable; default is intentionally small")
    parser.add_argument("--seed", type=int, default=20_000)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--output", default="artifacts/llm_benchmark.json")
    args = parser.parse_args()

    model_name, provider_kwargs = _provider_config(args.provider, args.model)
    env = ChernikaSingleBallEnv()
    rows = []
    for i in range(args.episodes):
        _, _ = env.reset(seed=args.seed + i)
        prompt = _state_prompt(env)
        started = time.perf_counter()
        try:
            raw, latency, usage = _call_model(model_name, provider_kwargs, prompt, args.timeout)
            parsed = _extract_json(raw)
            delta = float(parsed.get("angle_offset_rad", parsed.get("delta_angle_rad")))
            power = float(parsed["power_percent"])
            if not math.isfinite(delta) or not math.isfinite(power):
                raise ValueError("Non-finite angle or power")
            delta = max(-math.pi, min(math.pi, delta))
            power = max(0.0, min(MAX_UI_POWER_PERCENT, power))
            action = np.array([delta / math.pi,
                               2.0 * power / MAX_UI_POWER_PERCENT - 1.0], dtype=np.float32)
            _, reward, _, _, info = env.step(action)
            row = {
                "seed": args.seed + i,
                "valid": True,
                "selected_pocket": parsed.get("pocket"),
                "raw_output": raw,
                "reward": reward,
                "latency_seconds": latency,
                **usage,
                **info,
            }
        except Exception as exc:
            # Invalid output is recorded as a miss, never silently repaired into
            # a successful action. This preserves a fair raw-model benchmark.
            action = np.array([0.0, -1.0], dtype=np.float32)
            _, reward, _, _, info = env.step(action)
            row = {
                "seed": args.seed + i,
                "valid": False,
                "error": str(exc),
                "reward": reward,
                "latency_seconds": time.perf_counter() - started,
                "prompt_tokens": 0,
                "completion_tokens": 0,
                **info,
            }
        rows.append(row)
        print(f"{i + 1}/{args.episodes}: pocketed={row['success']} valid={row['valid']} "
              f"latency={row['latency_seconds']:.2f}s")

    n = max(1, len(rows))
    summary = {
        "provider": args.provider,
        "model": args.model,
        "episodes": len(rows),
        "valid_output_rate": sum(r["valid"] for r in rows) / n,
        "pocket_rate": sum(bool(r["success"]) for r in rows) / n,
        "first_contact_rate": sum(r["first_contact"] == 1 for r in rows) / n,
        "scratch_rate": sum(bool(r["cue_scratch"]) for r in rows) / n,
        "off_table_rate": sum(bool(r["off_table"]) for r in rows) / n,
        "mean_latency_seconds": sum(r["latency_seconds"] for r in rows) / n,
        "prompt_tokens": sum(r["prompt_tokens"] for r in rows),
        "completion_tokens": sum(r["completion_tokens"] for r in rows),
    }
    payload = {
        "benchmark": "Chernika raw LLM structured-state shot proposal",
        "seed_start": args.seed,
        "summary": summary,
        "trials": rows,
        "note": "No image, tool, physics feedback, or retry is given to the LLM. The simulator scores its one-shot proposal.",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
