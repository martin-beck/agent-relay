# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from verify_formal_models import verify_models


def test_bounded_domain_models_are_well_formed() -> None:
    verify_models()
