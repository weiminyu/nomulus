# Copyright 2020 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Script to rollback the Nomulus server on AppEngine."""

import argparse
import dataclasses
import sys
from typing import FrozenSet, Optional, Tuple

import appengine
import common
import gcs

HELP_TEXT = 'Script to roll back the Nomulus server on AppEngine.'


@dataclasses.dataclass(frozen=True)
class Argument:
    """Describes a command line argument.

    This class is for use with argparse.ArgumentParser. Except for the
    'arg_names' attribute which specifies the argument name and/or flags, all
    other attributes must match an accepted parameter in the parser's
    add_argument() method.
    """

    arg_names: Tuple[str, ...]
    help: str
    required: bool = True
    choices: Optional[Tuple[str, ...]] = None

    def get_arg_attrs(self):
        return dict((k, v) for k, v in vars(self).items() if k != 'arg_names')


ARGUMENTS = (Argument(('--dev_project', '-d'),
                      'The GCP project with Nomulus deployment records.'),
             Argument(('--project', '-p'),
                      'The GCP project where the Nomulus server is deployed.'),
             Argument(('--env', '-e'),
                      'The name of the Nomulus server environment.',
                      choices=('production', 'sandbox', 'crash', 'alpha')),
             Argument(('--target_release', '-t'),
                      'The release to be deployed.'))


@dataclasses.dataclass(frozen=True)
class _RollbackPlan:
    """Data needed for rolling back one service.

    An instance holds the configurations of both the currently serving
    version(s) and the rollback target in a service.

    Attributes:
        target_version: The version to roll back to.
        serving_versions: The currently serving versions to be stopped. This
            set may be empty. It may also have multiple versions.
    """
    target_version: common.VersionConfig
    serving_versions: FrozenSet[common.VersionConfig]

    def __post_init__(self):
        """Validates the instance."""
        if self.serving_versions:
            for config in self.serving_versions:
                assert config.service_id == self.target_version.service_id


def _ensure_versions_available(
    requested_versions: FrozenSet[common.VersionKey],
    all_configs: FrozenSet[common.VersionConfig]
) -> FrozenSet[common.VersionConfig]:
    """Find configurations for requested versions."""

    keys_with_configs = requested_versions.intersection(all_configs)
    return all_configs.intersection(keys_with_configs)


def _generate_plan(
        target_versions: FrozenSet[common.VersionConfig],
        serving_versions: FrozenSet[common.VersionConfig]
) -> Tuple[_RollbackPlan]:
    """Generates a rollback plan for each service."""

    targets_by_service = {}
    for version in target_versions:
        targets_by_service.setdefault(version.service_id, set()).add(version)

    serving_by_service = {}
    for version in serving_versions:
        serving_by_service.setdefault(version.service_id, set()).add(version)

    if targets_by_service.keys() != appengine.AppEngineAdmin.SERVICES:
        cannot_rollback = appengine.AppEngineAdmin.SERVICES.difference(
            targets_by_service.keys())
        raise common.ServiceStateError(
            f'Target version(s) not found for {cannot_rollback}')

    plan = []
    for service_id, versions in targets_by_service.items():
        serving_versions = serving_by_service.get(service_id, set())

        if versions.intersection(serving_versions):
            print(f'{service_id} is already running the target version. '
                  'No rollback necessary')
            continue

        chosen_version = next(iter(versions))
        plan.append(_RollbackPlan(chosen_version, frozenset(serving_versions)))

    return tuple(plan)


def rollback(dev_project: str, project: str, env: str,
             target_release: str) -> None:
    """Rolls back a Nomulus server to the target release.

    Args:
        dev_project: The GCP project with deployment records.
        project: The GCP project of the Nomulus server.
        env: The environment name of the Nomulus server.
        target_release: The tag of the release to be brought up.
    """

    gcs_client = gcs.GcsClient(dev_project)
    target_versions = gcs_client.get_versions_by_release(env, target_release)

    appengine_admin = appengine.AppEngineAdmin(project)
    serving_versions = appengine_admin.get_serving_versions()

    all_version_configs = appengine_admin.get_version_configs(
        target_versions.union(serving_versions))

    target_configs = _ensure_versions_available(target_versions,
                                                all_version_configs)
    serving_configs = _ensure_versions_available(serving_versions,
                                                 all_version_configs)

    rollback_plan = _generate_plan(target_configs, serving_configs)

    print(rollback_plan)


def main() -> int:
    parser = argparse.ArgumentParser(prog='nom_rollback',
                                     description=HELP_TEXT)
    for flag in ARGUMENTS:
        parser.add_argument(*flag.arg_names, **flag.get_arg_attrs())

    args = parser.parse_args()

    rollback(**vars(args))

    return 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as ex:
        print(str(ex))
        sys.exit(1)
