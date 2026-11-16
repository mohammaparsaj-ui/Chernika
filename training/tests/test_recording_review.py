import math

import numpy as np
import pytest

from chernika_rl.recording_review import (
    derive_reviewed_action,
    encode_reviewed_observation,
    parse_pocket_announcement,
    parse_target_announcement,
    power_marker_to_percent,
    screen_to_physics,
)
from chernika_rl.physics import MAX_UI_POWER_PERCENT, TABLE_BOTTOM, TABLE_LEFT, TABLE_RIGHT, TABLE_TOP


def test_screen_table_corners_map_to_canonical_physics_corners():
    assert screen_to_physics((143.0, 195.0)) == pytest.approx((TABLE_LEFT, TABLE_TOP))
    assert screen_to_physics((580.0, 1075.0)) == pytest.approx((TABLE_RIGHT, TABLE_BOTTOM))


def test_power_slider_endpoints_match_calibrated_ui_range():
    assert power_marker_to_percent(603.0) == pytest.approx(MAX_UI_POWER_PERCENT)
    assert power_marker_to_percent(1010.0) == pytest.approx(0.0)
    assert power_marker_to_percent(806.5) == pytest.approx(MAX_UI_POWER_PERCENT / 2.0)


def test_reviewed_action_uses_engine_angle_offset_and_power_mapping():
    cue = (300.0, 700.0)
    target = (360.0, 500.0)
    marker = (380.0, 520.0)
    result = derive_reviewed_action(cue, target, marker, 603.0)

    cue_xy = screen_to_physics(cue)
    target_xy = screen_to_physics(target)
    marker_xy = screen_to_physics(marker)
    direct = math.atan2(target_xy[1] - cue_xy[1], target_xy[0] - cue_xy[0])
    aim = math.atan2(marker_xy[1] - cue_xy[1], marker_xy[0] - cue_xy[0])
    delta = math.atan2(math.sin(aim - direct), math.cos(aim - direct))

    assert result["angle_offset_normalized"] == pytest.approx(delta / math.pi)
    assert result["power_percent"] == pytest.approx(MAX_UI_POWER_PERCENT)
    assert result["action"][1] == pytest.approx(1.0)


def test_reviewed_observation_keeps_52_value_schema_and_missing_ball_masks():
    balls = {
        1: (360.0, 500.0),
        2: (220.0, 400.0),
        9: (410.0, 300.0),
    }
    observation = encode_reviewed_observation(
        (300.0, 700.0), balls, break_pending=False, shot_count=4, max_shots=24
    )

    assert observation.shape == (52,)
    assert observation[2 + 3 * 0 + 2] == 1.0
    assert observation[2 + 3 * 1 + 2] == 1.0
    assert observation[2 + 3 * 2 + 2] == 0.0
    assert observation[29:38].tolist() == [1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
    assert observation[50] == 0.0
    assert observation[51] == pytest.approx(4.0 / 24.0)


def test_ocr_parsers_leave_uncertain_labels_null():
    assert parse_target_announcement("Hit the 6-ball") == 6
    assert parse_target_announcement("Pocket the 9-ball!") == 9
    assert parse_target_announcement("Hit the O-ball") is None
    assert parse_pocket_announcement("You pocketed the 7-ball") == [7]
    assert parse_pocket_announcement("You pocketed 2 balls") == []
    assert parse_pocket_announcement("some unrelated text") is None


def test_out_of_table_and_power_coordinates_are_rejected():
    with pytest.raises(ValueError):
        screen_to_physics((100.0, 500.0))
    with pytest.raises(ValueError):
        power_marker_to_percent(500.0)
