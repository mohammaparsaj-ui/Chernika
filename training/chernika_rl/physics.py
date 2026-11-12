"""Plato-calibrated 2-D billiards physics port for Chernika RL training.

This mirrors the current Java PoolPhysics constants and event order. Coordinates
are screenshot pixels in the canonical 611x1024 Plato portrait calibration.
It is intentionally a first-order model: no spin, throw, masse or cloth drift.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Iterable

# Current rebuild/table-simulator-gameplay calibration.
TABLE_LEFT = 120.0
TABLE_TOP = 169.0
TABLE_RIGHT = 490.0
TABLE_BOTTOM = 895.0
TABLE_WIDTH_PX = TABLE_RIGHT - TABLE_LEFT
TABLE_HEIGHT_PX = TABLE_BOTTOM - TABLE_TOP
BALL_RADIUS_PX = 11.5

# Physical scale and provisional replay-calibrated coefficients copied from
# app/src/main/java/com/cherunika/chernika/PoolPhysics.java.
TABLE_WIDTH_METERS = 0.99
TABLE_LENGTH_METERS = 1.98
BALL_RESTITUTION = 0.946
RAIL_RESTITUTION = 0.815
ROLLING_RESISTANCE = 0.0095
MAX_CUE_SPEED_MPS = 0.66
MAX_UI_POWER_PERCENT = 97.8
GRAVITY = 9.81
DT = 1.0 / 240.0
MAX_SIM_SECONDS = 40.0
MIN_MOVING_SPEED_PER_RADIUS = 0.05
MIN_COLLISION_SPEED_PER_RADIUS = 0.08
# Dense racks create simultaneous contacts. Revisit the pairs so collision
# propagation does not depend on the arbitrary ball-number iteration order.
BALL_COLLISION_ITERATIONS = 4

@dataclass(frozen=True)
class Pocket:
    name: str
    x: float
    y: float
    opening_width: float
    opening_height: float
    corner: bool

    @property
    def capture_radius(self) -> float:
        return min(self.opening_width, self.opening_height) * 0.5

# Exact measured anchors from PlatoTableCalibration.java for the canonical
# 611x1024 screenshot. These are not idealized symmetric coordinates.
POCKETS: tuple[Pocket, ...] = (
    Pocket("top_left", 118.60, 163.39, 48.0, 47.0, True),
    Pocket("top_right", 490.53, 163.43, 48.0, 47.0, True),
    Pocket("middle_left", 108.34, 531.65, 35.0, 42.0, False),
    Pocket("middle_right", 500.68, 531.62, 35.0, 42.0, False),
    Pocket("bottom_left", 118.61, 899.90, 48.0, 47.0, True),
    Pocket("bottom_right", 490.48, 899.83, 48.0, 47.0, True),
)
MAX_POCKET_CAPTURE_RADIUS = max(p.capture_radius for p in POCKETS)

@dataclass(frozen=True)
class Ball:
    x: float
    y: float
    number: int
    cue: bool = False
    radius: float = BALL_RADIUS_PX

@dataclass(frozen=True)
class BallOutcome:
    number: int
    cue: bool
    x: float
    y: float
    pocketed: bool
    off_table: bool

@dataclass(frozen=True)
class SimulationResult:
    outcomes: tuple[BallOutcome, ...]
    first_contact_number: int | None
    ball_collisions: int
    cushion_contacts: int
    simulated_seconds: float
    cue_scratch: bool
    any_ball_off_table: bool
    post_contact_cushion_contacts: int = 0
    post_contact_cushion_ball_numbers: tuple[int, ...] = ()

    def pocketed(self, number: int) -> bool:
        return any(o.number == number and not o.cue and o.pocketed for o in self.outcomes)

    def final_position(self, number: int) -> tuple[float, float] | None:
        for o in self.outcomes:
            if o.number == number and not o.cue:
                return (o.x, o.y)
        return None

class _Body:
    __slots__ = ("source", "x", "y", "vx", "vy", "pocketed", "off_table")
    def __init__(self, source: Ball):
        self.source = source
        self.x, self.y = source.x, source.y
        self.vx = self.vy = 0.0
        self.pocketed = self.off_table = False
    @property
    def radius(self) -> float:
        return self.source.radius
    def active(self) -> bool:
        return not self.pocketed and not self.off_table
    def speed(self) -> float:
        return math.hypot(self.vx, self.vy)


def _pixels_per_meter() -> float:
    return (TABLE_WIDTH_PX / TABLE_WIDTH_METERS + TABLE_HEIGHT_PX / TABLE_LENGTH_METERS) * 0.5


def _pocket_at_center(body: _Body) -> bool:
    # Exact broad-phase rejection: a ball well inside all four table edges
    # cannot be within any measured pocket's capture radius.
    margin = MAX_POCKET_CAPTURE_RADIUS + body.radius
    if (TABLE_LEFT + margin < body.x < TABLE_RIGHT - margin
            and TABLE_TOP + margin < body.y < TABLE_BOTTOM - margin):
        return False
    for pocket in POCKETS:
        effective = max(2.0, pocket.capture_radius - body.radius * 0.75)
        if math.hypot(body.x - pocket.x, body.y - pocket.y) <= effective:
            return True
    return False


def _pocket_at_rail(body: _Body, rail: str) -> bool:
    for pocket in POCKETS:
        if rail == "left":
            adjacent = abs(pocket.x - TABLE_LEFT) <= body.radius * 2.0
            half_opening = pocket.opening_height * 0.5
            along, pocket_along = body.y, pocket.y
        elif rail == "right":
            adjacent = abs(pocket.x - TABLE_RIGHT) <= body.radius * 2.0
            half_opening = pocket.opening_height * 0.5
            along, pocket_along = body.y, pocket.y
        elif rail == "top":
            adjacent = abs(pocket.y - TABLE_TOP) <= body.radius * 2.0
            half_opening = pocket.opening_width * 0.5
            along, pocket_along = body.x, pocket.x
        else:
            adjacent = abs(pocket.y - TABLE_BOTTOM) <= body.radius * 2.0
            half_opening = pocket.opening_width * 0.5
            along, pocket_along = body.x, pocket.x
        usable = max(1.0, half_opening - body.radius * 0.72)
        if adjacent and abs(along - pocket_along) <= usable:
            return True
    return False


def _resolve_rails(body: _Body) -> int:
    r = body.radius
    # Do not scan pocket anchors unless the ball actually touches a rail.
    if (body.x - r > TABLE_LEFT and body.x + r < TABLE_RIGHT
            and body.y - r > TABLE_TOP and body.y + r < TABLE_BOTTOM):
        return 0

    contacts = 0
    if body.vx < 0 and body.x - r <= TABLE_LEFT:
        if _pocket_at_rail(body, "left"):
            body.pocketed = True
            body.vx = body.vy = 0.0
            return contacts
        body.x = TABLE_LEFT + r
        body.vx = -body.vx * RAIL_RESTITUTION
        contacts += 1
    elif body.vx > 0 and body.x + r >= TABLE_RIGHT:
        if _pocket_at_rail(body, "right"):
            body.pocketed = True
            body.vx = body.vy = 0.0
            return contacts
        body.x = TABLE_RIGHT - r
        body.vx = -body.vx * RAIL_RESTITUTION
        contacts += 1

    if body.vy < 0 and body.y - r <= TABLE_TOP:
        if _pocket_at_rail(body, "top"):
            body.pocketed = True
            body.vx = body.vy = 0.0
            return contacts
        body.y = TABLE_TOP + r
        body.vy = -body.vy * RAIL_RESTITUTION
        contacts += 1
    elif body.vy > 0 and body.y + r >= TABLE_BOTTOM:
        if _pocket_at_rail(body, "bottom"):
            body.pocketed = True
            body.vx = body.vy = 0.0
            return contacts
        body.y = TABLE_BOTTOM - r
        body.vy = -body.vy * RAIL_RESTITUTION
        contacts += 1
    return contacts


def simulate(
    balls: Iterable[Ball],
    angle_radians: float,
    power_percent: float,
    *,
    cue_speed_multiplier: float = 1.0,
) -> SimulationResult:
    """Simulate one shot; multiplier is an explicit calibration hook for break tests."""
    if not math.isfinite(cue_speed_multiplier) or cue_speed_multiplier <= 0.0:
        raise ValueError("cue_speed_multiplier must be finite and positive")
    sources = list(balls)
    cue_index = next((i for i, b in enumerate(sources) if b.cue), None)
    if cue_index is None or not math.isfinite(angle_radians):
        return SimulationResult((), None, 0, 0, 0.0, False, False)
    bodies = [_Body(b) for b in sources]
    cue = bodies[cue_index]

    power = max(0.0, min(100.0, float(power_percent)))
    normalized_power = min(1.0, power / MAX_UI_POWER_PERCENT)
    pixels_per_meter = _pixels_per_meter()
    speed = (
        normalized_power * MAX_CUE_SPEED_MPS
        * float(cue_speed_multiplier) * pixels_per_meter
    )
    cue.vx = math.cos(angle_radians) * speed
    cue.vy = math.sin(angle_radians) * speed

    rolling_deceleration = ROLLING_RESISTANCE * GRAVITY * pixels_per_meter
    stop_speed = max(0.25, cue.radius * MIN_MOVING_SPEED_PER_RADIUS)
    collision_speed = max(0.25, cue.radius * MIN_COLLISION_SPEED_PER_RADIUS)
    first_contact: int | None = None
    collisions = cushion_contacts = 0
    post_contact_cushion_contacts = 0
    post_contact_cushion_ball_numbers: set[int] = set()
    elapsed = 0.0
    max_steps = math.ceil(MAX_SIM_SECONDS / DT)

    for step in range(max_steps):
        any_moving = False
        for body in bodies:
            if not body.active():
                continue
            if body.speed() > stop_speed:
                any_moving = True
            body.x += body.vx * DT
            body.y += body.vy * DT
        if not any_moving and step > 0:
            break

        # Equal-mass ball-ball collision resolution. Multiple passes are
        # required for dense rack contacts; one pass made energy propagation
        # depend on the arbitrary order of numbered balls.
        for _ in range(BALL_COLLISION_ITERATIONS):
            for i, a in enumerate(bodies):
                if not a.active():
                    continue
                for b in bodies[i + 1:]:
                    if not b.active():
                        continue
                    dx, dy = b.x - a.x, b.y - a.y
                    min_distance = a.radius + b.radius
                    distance_squared = dx * dx + dy * dy
                    if distance_squared >= min_distance * min_distance:
                        continue
                    distance = math.sqrt(max(1e-12, distance_squared))
                    nx, ny = ((1.0, 0.0) if distance < 1e-6 else (dx / distance, dy / distance))
                    overlap = min_distance - distance
                    correction = overlap * 0.5 + 0.002
                    a.x -= nx * correction
                    a.y -= ny * correction
                    b.x += nx * correction
                    b.y += ny * correction
                    relative_normal_speed = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                    if relative_normal_speed < -collision_speed:
                        impulse = -(1.0 + BALL_RESTITUTION) * relative_normal_speed * 0.5
                        a.vx -= nx * impulse
                        a.vy -= ny * impulse
                        b.vx += nx * impulse
                        b.vy += ny * impulse
                        collisions += 1
                        if first_contact is None:
                            if a.source.cue and not b.source.cue:
                                first_contact = b.source.number
                            elif b.source.cue and not a.source.cue:
                                first_contact = a.source.number

        for body in bodies:
            if not body.active():
                continue
            if _pocket_at_center(body):
                body.pocketed = True
                body.vx = body.vy = 0.0
                continue
            rail_contacts = _resolve_rails(body)
            cushion_contacts += rail_contacts
            if first_contact is not None and rail_contacts > 0:
                post_contact_cushion_contacts += rail_contacts
                if not body.source.cue:
                    post_contact_cushion_ball_numbers.add(body.source.number)
            if not body.active():
                continue
            if (body.x < TABLE_LEFT - body.radius or body.x > TABLE_RIGHT + body.radius
                    or body.y < TABLE_TOP - body.radius or body.y > TABLE_BOTTOM + body.radius):
                body.off_table = True
                body.vx = body.vy = 0.0
                continue
            current_speed = body.speed()
            if current_speed > 0.0:
                next_speed = max(0.0, current_speed - rolling_deceleration * DT)
                scale = next_speed / current_speed
                body.vx *= scale
                body.vy *= scale
            if body.speed() <= stop_speed:
                body.vx = body.vy = 0.0

        elapsed += DT
        if all((not b.active()) or b.speed() <= stop_speed for b in bodies):
            break

    outcomes = tuple(BallOutcome(b.source.number, b.source.cue, b.x, b.y,
                                 b.pocketed, b.off_table) for b in bodies)
    cue_scratch = any(o.cue and o.pocketed for o in outcomes)
    any_off_table = any(o.off_table for o in outcomes)
    return SimulationResult(
        outcomes, first_contact, collisions, cushion_contacts,
        elapsed, cue_scratch, any_off_table,
        post_contact_cushion_contacts,
        tuple(sorted(post_contact_cushion_ball_numbers)),
    )


def wrap_angle(angle: float) -> float:
    return (angle + math.pi) % (2.0 * math.pi) - math.pi
