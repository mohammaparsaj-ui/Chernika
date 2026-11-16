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
