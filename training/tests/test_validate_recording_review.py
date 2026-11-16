from chernika_rl.recording_review import parse_pocket_announcement
from chernika_rl.recording_review import parse_pocket_count_announcement


def test_count_only_break_banner_never_invents_ball_numbers():
    text = "You pocketed 2 balls"
    assert parse_pocket_announcement(text) is None
    assert parse_pocket_count_announcement(text) == 2
