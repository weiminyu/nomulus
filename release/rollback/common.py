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
"""Declares data types that describe AppEngine services and versions."""

import dataclasses
from typing import Optional


class ServiceStateError(Exception):
    """Bad AppEngine state preventing rollback by this tool.

    User must manually fix the system state before trying again to roll back.

    Scenarios that may cause this error include but not limited to:
        - Target version was not deployed to AppEngine.
        - Target version has been deleted from AppEngine.
    """
    pass


@dataclasses.dataclass(frozen=True)
class VersionKey:
    """Identifier of a deployed version on AppEngine.

    AppEngine versions as deployable units are managed on per-service basis.
    Each instance of this class uniquely identifies an AppEngine version.

    This class may serve as the identity key of a subclass if the subclass
    chooses not to implement its own __eq__() method.
    """

    service_id: str
    version_id: str

    def __eq__(self, other):
        return (isinstance(other, VersionKey)
                and self.service_id == other.service_id
                and self.version_id == other.version_id)


@dataclasses.dataclass(frozen=True, eq=False)
class VersionConfig(VersionKey):
    """Rollback-related static configuration of an AppEngine version.

    Contains data found from the application-web.xml for this version.

    Attributes:
        manual_scaling_instances: The originally configure VM instances to use
            for each version that is on manual scaling. This value is needed
            when a manual-scaling version is activated again.
    """

    manual_scaling_instances: Optional[int] = None

    def is_manual_scaling(self) -> bool:
        return self.manual_scaling_instances is not None
