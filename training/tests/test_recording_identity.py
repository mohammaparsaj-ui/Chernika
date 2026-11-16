from chernika_rl.recording_identity import identify_user_recording


def test_configured_usernames_tag_recording_as_user_owned_case_insensitively():
    for username in ("my_player", "ALT_2", "accountx"):
        result = identify_user_recording([f"player: {username}"], [username])
        assert result["owner"] == "user"
        assert result["safe_to_use_as_user_demo"] is True
        assert result["duplicate_check_required"] is True


def test_username_substrings_do_not_create_false_positive():
    result = identify_user_recording(["player: my_playerx"], ["my_player"])
    assert result["owner"] == "unidentified"
    assert result["safe_to_use_as_user_demo"] is False


def test_unknown_recording_is_not_discarded_or_mislabelled():
    result = identify_user_recording(["player: opponent123"], ["my_player"])
    assert result["owner"] == "unidentified"
    assert result["duplicate_check_required"] is True
    assert result["shot_player_attribution_verified"] is False
