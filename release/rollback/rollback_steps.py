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
"""Defines steps in a Nomulus rollback."""

import dataclasses
import subprocess

import common


@dataclasses.dataclass
class RollbackStep:
    """Base class for all Nomulus rollback steps."""
    description: str


class CheckSchemaCompatibility(RollbackStep):
    """Checks if rollback target is compatible with the SQL schema."""
    def __init__(self, dev_project: str, nom_tag: str, sql_tag: str) -> None:
        """Performs the Server-Schema compatibility test.

        Tests the target Nomulus release with the current schema release in
        the environment. Manual intervention would be needed if this test
        fails.
        """
        super().__init__(description='Check compatibility with SQL schema.')
        self._command = (f'{common.get_nomulus_root()}/nom_build',
                         f':integration:sqlIntegrationTest',
                         f'--schema_version={sql_tag}',
                         f'--nomulus_version={nom_tag}', '--publish_repo='
                         f'gcs://{dev_project}-deployed-tags/maven')

    def show_commandline(self):
        """Describes the command that would be executed."""
        print(f'# {self.description}\n' f'{" ".join(self._command)}')

    def dry_run(self):
        """Describes the command that would be executed."""
        self.show_commandline()

    def execute(self) -> None:
        """Executes the server-schema compatibility test.

        Raises:
            ServiceStateError if test fails.
        """
        print(self._command)

        if subprocess.call(self._command) != 0:
            raise common.ServiceStateError(
                'Rollback target incompatible with SQL schema.')


class ActivateVersion(RollbackStep):
    """Activates an AppEngine version.

    Makes the given version enter the SERVING state.
    """
    def __init__(self, version: common.VersionKey):
        super().__init__(description=f'Activate {version}')
        self._version = version
        self._command = ['gcloud', 'app', 'version']

    def show_commandline(self):
        print(f'')
