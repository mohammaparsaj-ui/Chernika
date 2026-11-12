# Chernika RL — Phase 1: learn to aim and control power

This is an **offline training project**, separate from the Android APK. It trains a continuous-control agent to pocket one target ball in Chernika's calibrated portrait table before the agent is allowed to control Plato.

## Why this design

- **Billiards-RL** is the reference for the SAC curriculum and its continuous action space (angle offset + strike speed): https://github.com/yongjin-shin/billiards-rl
- **CueTip** is the reference for the higher-level architecture: a language model proposes a tactical intention, while a physics simulator and optimizer verify/adjust the actual shot: https://github.com/SeanMemery/cuetip
- **LLMBilliardsMaster** is a useful raw-LLM-versus-algorithmic-agent research reference: https://github.com/PasserbyZzz/LLMBilliardsMaster. Its README reports tests with DeepSeek-V3.2, Qwen-Plus, MoonShot-K2 and GLM-4.5-Air, but also says the full LLM-planner + low-level Bayesian-optimizer integration is future work. We use it as a benchmark-design reference only; no source code is copied.
- The training environment below uses a Python mirror of Chernika's current PoolPhysics.java and PlatoTableCalibration.java, rather than training only on a different idealized table. No source code is copied from those research repositories.

**Important research caveat:** Billiards-RL's own Exp-16 README reports Phase 1 heuristic pocketing at 62.2% versus SAC at 63.6% (p=0.49), and Phase 0 heuristic at 56.9% versus SAC at 55.3% (p=0.62). RL is therefore not assumed to beat geometry; Chernika's held-out benchmark is a deployment gate, not a formality.

## Current training task

- One cue ball and one target ball; six measured Plato pockets.
- Table playable bounds: (120,169)-(490,895) in the canonical 611x1024 screenshot.
- Ball radius prior: 11.5 px.
- Pocket centers and openings are copied from the current Chernika calibration anchors.
- Physics coefficients match the current rebuild/table-simulator-gameplay branch: ball restitution 0.946, rail restitution 0.815, rolling resistance 0.0095, max cue speed 0.66 m/s, max power reading 97.8%.
- Observation: 16 normalized values — cue position, target position, and six pocket centers.
- Action: 2 continuous values — angle offset relative to the cue-to-target line, and power.
- Reward: target pocketing dominates; legal first contact is rewarded; scratches and off-table events are heavily penalized; a small distance-progress term helps learning.
- Every episode is one shot. This is a deliberately narrow skill-learning stage, not a full 9-ball policy.

**Calibration warning:** the Java physics constants are still provisional and based on a small number of Plato replays. The Python mirror is intended to follow the current Java model; it has not yet been cross-validated against a Java golden-output suite and does not claim to reproduce every live-game effect. Spin, throw, masse, ball-in-hand, combinations and multi-rail effects are not included in Phase 1.

## Install

Python 3.10+ is required. On Windows PowerShell:

    cd training
    py -3.11 -m venv .venv
    .\.venv\Scripts\Activate.ps1
    python -m pip install --upgrade pip
    pip install -e ".[dev]"

On Linux/macOS:

    cd training
    python3 -m venv .venv
    source .venv/bin/activate
    python -m pip install --upgrade pip
    pip install -e ".[dev]"

## Verify the physics port

    pytest -q

## Train the first agent

    python -m chernika_rl.train --timesteps 100000 --device auto

The best checkpoint is saved under artifacts/sac_single_ball/best_model/; the final checkpoint and training metadata are saved beside it. TensorBoard logs are under artifacts/sac_single_ball/tensorboard/.

For a quick smoke run:

    python -m chernika_rl.train --timesteps 2000 --eval-freq 500 --eval-episodes 20

## Benchmark

    python -m chernika_rl.evaluate --episodes 200

The benchmark runs the random policy, a ghost-ball + physics-scored heuristic, and SAC if a trained checkpoint exists. Each policy is tested against the same seeded table states. It reports pocket rate, first-contact rate, scratch rate, off-table rate, mean reward and mean power.

## Raw LLM benchmark (optional, billable API calls)

The raw-model test is deliberately separate from SAC. Each model receives the same structured coordinates, returns one angle offset and one power value, and gets **no simulator feedback or retry**. This isolates shot reasoning from screenshot perception. Install the optional adapter with:

    pip install -e ".[llm]"

Examples (use the provider's API key as an environment variable; never commit keys):

    # OpenAI
    export OPENAI_API_KEY=...
    python -m chernika_rl.llm_benchmark --provider openai --model gpt-6-astra --episodes 20 --seed 20000

    # Anthropic
    export ANTHROPIC_API_KEY=...
    python -m chernika_rl.llm_benchmark --provider anthropic --model claude-opus-5-5 --episodes 20 --seed 20000

    # DeepSeek
    export DEEPSEEK_API_KEY=...
    python -m chernika_rl.llm_benchmark --provider deepseek --model deepseek-v4-pro --episodes 20 --seed 20000

    # Alibaba Cloud Model Studio / Qwen
    export DASHSCOPE_API_KEY=...
    python -m chernika_rl.llm_benchmark --provider dashscope --model qwen3.7-plus --episodes 20 --seed 20000

Current candidate IDs are provider-controlled and can change: OpenAI gpt-6-astra / gpt-6.1-sol / gpt-6.1-terra; Anthropic claude-opus-5-5 / claude-sonnet-5-5 / claude-fable-5-2; DeepSeek deepseek-v4-pro / deepseek-flash; Qwen qwen3.7-plus / qwen3.8-max / qwen3.8-flash. Verify account access and current IDs before running.

For a fair comparison, run every model with the **same seed and episode count**. The report also includes the ghost-ball/physics heuristic rate on the exact same states. Once a SAC checkpoint exists, add --sac-model artifacts/sac_single_ball/best_model/best_model.zip to include SAC on those same states. The first test uses numeric state only; a separate image-input test can later measure visual table-reading without mixing it into aiming accuracy.

## Transfer plan — do not skip the gate

1. **Phase 1:** one-ball aiming and power control (this folder).
2. **Phase 2:** add distractor balls and enforce lowest-ball first contact; train on multi-ball states.
3. **Phase 3:** add cue-ball positioning, safety play, scratch avoidance, and combinations.
4. **Phase 4:** compare raw LLM proposals with the trained policy. Use the LLM for high-level target/pocket/shot intent; let the simulator optimize and reject unsafe angle/power values. CueTip is the reference for this separation.
5. **Phase 5:** export the small policy to ONNX/TFLite only after held-out evaluation, then connect it to the Android gesture adapter.

The APK must remain on its existing geometric/physics planner until the learned policy beats the heuristic on a held-out benchmark and passes real-device replay validation. No untrained model should fire live Plato shots.

## License and attribution

This training scaffold is new Chernika code. Billiards-RL and CueTip are cited as design references; check their current repository licenses and attribution terms before incorporating any of their code or assets. CueTip currently declares MIT; the Billiards-RL repository currently has no license file visible in its repository root, so this project does not copy its source.
