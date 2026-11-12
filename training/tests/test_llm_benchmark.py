from chernika_rl.llm_benchmark import _extract_json


def test_extracts_plain_json():
    value = _extract_json('{"pocket":"top_right","angle_offset_rad":0.2,"power_percent":44}')
    assert value["pocket"] == "top_right"
    assert value["power_percent"] == 44


def test_extracts_json_from_code_fence():
    value = _extract_json('```json\n{"angle_offset_rad":-0.1,"power_percent":52}\n```')
    assert value["angle_offset_rad"] == -0.1
    assert value["power_percent"] == 52
