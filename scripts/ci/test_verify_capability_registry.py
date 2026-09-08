# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

from verify_capability_registry import verify_registry


def test_capability_registry_is_valid() -> None:
    verify_registry()
