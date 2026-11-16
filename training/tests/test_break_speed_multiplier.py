import pytest

from chernika_rl.physics import Ball, simulate


def test_unit_speed_multiplier_preserves_default_simulation():
    cue = Ball(300.0, 780.0, 0, cue=True)
    target = Ball(300.0, 500.0, 1)

    default = simulate([cue, target], -1.5707963267948966, 80.0)
    explicit = simulate(
        [cue, target],
        -1.5707963267948966,
        80.0,
        cue_speed_multiplier=1.0,
    )

    assert explicit == default


@pytest.mark.parametrize("multiplier", [0.0, -1.0, float("inf"), float("nan")])
def test_invalid_speed_multiplier_is_rejected(multiplier):
    cue = Ball(300.0, 780.0, 0, cue=True)
    target = Ball(300.0, 500.0, 1)

    with pytest.raises(ValueError):
        simulate(
            [cue, target],
            -1.5707963267948966,
            80.0,
            cue_speed_multiplier=multiplier,
        )
