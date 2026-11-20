# Chernika AI v0.3.2 — Model diagnostics

This release investigates errors where LiteRT-LM loads Qwen3 but native inference
fails with 'Failed to invoke the compiled model' or task state 7.

- CPU backend limited to 2 threads, 1024-token KV cache, and 192 output tokens.
- On inference failure, the corrupted Conversation/Engine are closed before retry.
- Accepts original Qwen3 INT8 (~614 MB) and lighter Qwen3 no-think INT4 (~347 MB).
- Verifies SHA-256 during import/download rather than merely checking file size.
- 'Verify model checksum + RAM' gives integrity and current available RAM.
- Model import, download and verification are not proof of successful inference.

Official source files:
https://huggingface.co/litert-community/Qwen3-0.6B/blob/main/Qwen3-0.6B.litertlm
SHA256 c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108
https://huggingface.co/litert-community/Qwen3-0.6B-int4/blob/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm
SHA256 2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139

WARNING: APKs built on separate GitHub-hosted runners can use different debug
signing keys. Uninstall may be required. Uninstall deletes imported models in
private app storage, so retain your original file in Downloads.

Cloud mode continues to work with the separately configured free gateway.
If the verified INT4 model still fails, the underlying LiteRT runtime/device
compatibility must be investigated with device logs. No guarantee of resolution.
