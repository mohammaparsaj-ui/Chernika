import math

from chernika_rl.physics import (
    Ball, BALL_RADIUS_PX, POCKETS, TABLE_LEFT, TABLE_TOP,
    TABLE_RIGHT, TABLE_BOTTOM, simulate, wrap_angle,
)


def test_calibration_uses_six_measured_pockets():
    assert len(POCKETS) == 6
    assert POCKETS[0].name == "top_left"
    assert POCKETS[3].name == "middle_right"
    assert TABLE_LEFT == 120.0
    assert TABLE_TOP == 169.0
    assert TABLE_RIGHT == 490.0
    assert TABLE_BOTTOM == 895.0


def test_head_on_collision_records_first_contact():
    cue = Ball(180.0, 400.0, 0, cue=True)
    target = Ball(250.0, 400.0, 1)
    result = simulate([cue, target], 0.0, 35.0)
    assert result.first_contact_number == 1
    assert result.ball_collisions >= 1
    final = result.final_position(1)
    assert final is not None and final[0] > target.x


def test_aligned_top_right_pot_is_detected():
    target = Ball(430.0, 300.0, 3)
    pocket = POCKETS[1]
    dx, dy = pocket.x - target.x, pocket.y - target.y
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ghost_x = target.x - ux * (2 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2 * BALL_RADIUS_PX + 1.0)
    cue = Ball(ghost_x - ux * 200.0, ghost_y - uy * 200.0, 0, cue=True)
    result = simulate([cue, target], math.atan2(uy, ux), 65.0)
    assert result.first_contact_number == 3
    assert result.pocketed(3)
    assert not result.cue_scratch


def test_simulation_is_deterministic():
    balls = [Ball(200.0, 500.0, 0, cue=True), Ball(280.0, 500.0, 1)]
    a = simulate(balls, 0.0, 40.0)
    b = simulate(balls, 0.0, 40.0)
    assert a == b


def test_wrap_angle_stays_in_range():
    value = wrap_angle(5.0 * math.pi)
    assert -math.pi <= value <= math.pi


def test_aligned_bottom_right_pot_is_detected():
    target = Ball(430.0, 760.0, 4)
    pocket = POCKETS[5]
    dx, dy = pocket.x - target.x, pocket.y - target.y
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    ghost_x = target.x - ux * (2 * BALL_RADIUS_PX + 1.0)
    ghost_y = target.y - uy * (2 * BALL_RADIUS_PX + 1.0)
    cue = Ball(ghost_x - ux * 200.0, ghost_y - uy * 200.0, 0, cue=True)
    result = simulate([cue, target], math.atan2(uy, ux), 65.0)
    assert result.first_contact_number == 4
    assert result.pocketed(4)
    assert not result.cue_scratch
