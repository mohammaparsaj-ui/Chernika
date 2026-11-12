import numpy as np

from chernika_rl.physics import POCKETS, TABLE_LEFT, TABLE_TOP, TABLE_WIDTH_PX, TABLE_HEIGHT_PX
from chernika_rl.train_imitation import augment_nine_ball_symmetries


def test_nine_ball_symmetry_augmentation_maps_geometry_labels_and_actions():
    observations = np.full((1, 52), -0.05, dtype=np.float32)
    observations[0, 0:2] = (0.20, 0.30)
    observations[0, 2:5] = (0.40, 0.60, 1.0)
    observations[0, 29] = 1.0
    observations[0, 50:52] = (0.0, 0.25)
    actions = np.asarray([[0.20, 0.80]], dtype=np.float32)
    pockets = np.asarray([0], dtype=np.int64)
    successes = np.asarray([1.0], dtype=np.float32)
    first_contacts = np.asarray([1.0], dtype=np.float32)

    x, a, p, s, f = augment_nine_ball_symmetries(
        observations, actions, pockets, successes, first_contacts
    )

    assert x.shape == (4, 52)
    assert a.shape == (4, 2)
    assert p.tolist() == [0, 1, 4, 5]
    assert np.allclose(a[:, 0], (0.20, -0.20, -0.20, 0.20))
    assert np.allclose(a[:, 1], 0.80)
    assert np.allclose(x[:, 0], (0.20, 0.80, 0.20, 0.80))
    assert np.allclose(x[:, 1], (0.30, 0.30, 0.70, 0.70))
    assert np.allclose(x[:, 2], (0.40, 0.60, 0.40, 0.60))
    assert np.allclose(x[:, 3], (0.60, 0.60, 0.40, 0.40))
    # Absent-ball sentinel coordinates must not be reflected into valid table space.
    assert np.all(x[:, 5] == -0.05)
    assert np.all(x[:, 6] == -0.05)
    assert np.allclose(s, 1.0)
    assert np.allclose(f, 1.0)

    expected_pockets = np.asarray([
        (p.x - TABLE_LEFT) / TABLE_WIDTH_PX for p in POCKETS
    ])
    expected_pockets_y = np.asarray([
        (p.y - TABLE_TOP) / TABLE_HEIGHT_PX for p in POCKETS
    ])
    assert np.allclose(x[:, 38:50:2], expected_pockets[None, :])
    assert np.allclose(x[:, 39:50:2], expected_pockets_y[None, :])
