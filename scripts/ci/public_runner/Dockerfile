# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

FROM ubuntu@sha256:224a1869083a311ef3f13648a154ba79832fbef6364d31493642ca03082da254

ARG RUNNER_VERSION=2.337.0
ARG RUNNER_SHA256=70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        ca-certificates curl git gpg jq libatomic1 libicu74 libkrb5-3 libssl3 openssl unzip xz-utils zip zlib1g \
    && rm -rf /var/lib/apt/lists/* \
    && curl --fail --location --proto "=https" --tlsv1.2 \
        --output /tmp/actions-runner.tar.gz \
        "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" \
    && printf "%s  %s\n" "${RUNNER_SHA256}" /tmp/actions-runner.tar.gz | sha256sum --check --strict \
    && mkdir --mode=0755 /opt/runner-dist \
    && tar --extract --gzip --file /tmp/actions-runner.tar.gz --directory /opt/runner-dist \
    && rm /tmp/actions-runner.tar.gz \
    && useradd --create-home --home-dir /home/runner --uid 10001 runner

COPY --chmod=0755 scripts/ci/public_runner/entrypoint.sh /usr/local/bin/public-runner-entrypoint

USER runner
WORKDIR /runner
ENTRYPOINT ["/usr/local/bin/public-runner-entrypoint"]
