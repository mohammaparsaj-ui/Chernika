# Gameplay recording intake

Raw gameplay videos are kept outside the public repository. The local intake
pipeline may use OCR text to compare visible player names with a user-supplied
private username list via \`chernika_rl.recording_identity.identify_user_recording\`.
Do not commit that private list or raw screen recordings.

Ownership classification is not shot attribution. A known username proves the
recording belongs to the user's collection, but the screen may show the user's
opponent taking a turn. Keep separate labels for:

- recording owner;
- player currently taking the shot;
- target-ball announcement;
- observed pocket notification;
- exact pre-shot ball positions;
- aim angle, power and spin;
- foul, scratch and rack result.

OCR announcements and victory messages are weak labels. Before a frame becomes
a supervised training example, a reviewer must verify the table crop, all ball
centres, the cue-ball centre, the aim line, power-bar reading, spin selector,
turn owner and post-shot outcome. Unknown values stay null; do not infer them
from the next target announcement.

For duplicate detection, compare video content or frame hashes. Similar
filenames alone must never discard a recording.


## Reviewed replay candidates

A frame-reviewed recording may be stored as a JSON manifest under
\`training/recordings/reviewed/\`. Keep the raw video local and redact player
names. Store screen-pixel coordinates, the calibrated table bounds, aim
marker, power-slider marker, target announcement and observed pocket result.
The conversion helpers in \`chernika_rl.recording_review\` map those reviewed
values into the existing 52-value observation and two-value action schema.

A replay candidate is not automatically a training example. Keep
\`training_eligible: false\` until:
1. the full pre-shot state and player turn are independently checked;
2. screen-to-physics and power-slider calibration pass a replay test;
3. the recorded action reproduces the observed first contact and pocket set
   within the simulator's accepted tolerance; and
4. enough independent racks exist for a rack-grouped split.

The October 30 user-provided recording is one completed 9-ball rack. Its
manifest is retained as a held-out review candidate only; it is not mixed into
the teacher-generated training set.
