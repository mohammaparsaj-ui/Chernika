"""Ownership tagging for gameplay recordings from OCR-extracted text."""
from __future__ import annotations

import re
from collections.abc import Iterable, Sequence


def identify_user_recording(
    ocr_texts: Iterable[str],
    known_usernames: Sequence[str],
) -> dict[str, object]:
    """Tag a recording when one of the user's configured usernames is visible.

    The username list is supplied at runtime rather than stored in the public
    repository. Matching is case-insensitive and token-based. This function
    classifies ownership only: it does not attribute individual shots and
    never discards a recording as a duplicate.
    """
    normalized = [str(name).strip() for name in known_usernames if str(name).strip()]
    combined = "\n".join(str(text) for text in ocr_texts if text)
    matches = []
    for username in normalized:
        pattern = rf"(?<![A-Za-z0-9_]){re.escape(username)}(?![A-Za-z0-9_])"
        if re.search(pattern, combined, flags=re.IGNORECASE):
            matches.append(username)
    return {
        "owner": "user" if matches else "unidentified",
        "matched_usernames": matches,
        "ownership_evidence": "visible_username_ocr" if matches else None,
        "safe_to_use_as_user_demo": bool(matches),
        "duplicate_check_required": True,
        "shot_player_attribution_verified": False,
    }
