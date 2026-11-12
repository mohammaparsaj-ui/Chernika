import numpy as np

from chernika_rl.train_imitation import _split_indices


def test_sequential_states_from_one_rack_never_cross_validation_boundary():
    groups = np.repeat(np.arange(12, dtype=np.int64), 4)
    train_ids, validation_ids, strategy = _split_indices(
        sample_count=len(groups), seed=1234, groups=groups
    )

    assert strategy == "rack_grouped"
    train_groups = set(groups[train_ids].tolist())
    validation_groups = set(groups[validation_ids].tolist())
    assert train_groups
    assert validation_groups
    assert train_groups.isdisjoint(validation_groups)
