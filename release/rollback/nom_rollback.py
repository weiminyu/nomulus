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
from typing import Iterable, Optional, Tuple

import appengine
import common
import gcs

HELP_TEXT = 'Script to roll back the Nomulus server on AppEngine.'


@dataclasses.dataclass(frozen=True)
class Argument:
    """Describes a command line argument.

    This class is for use with argparse.ArgumentParser. Except for the
    'arg_names' property which provides the argument name and/or flags, all
    other property names must match an accepted parameter in the parser's
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


def _generate_plan(target_versions: Iterable[common.Service],
                   rollback_versions: Iterable[common.Service],
                   version_configs: Iterable[common.VersionConfig]) -> None:
    """"""
    pass


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
    rollback_versions = appengine_admin.get_serving_versions()

    version_configs = appengine_admin.get_version_configs(
        [*target_versions, *rollback_versions])
    print(version_configs)


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
