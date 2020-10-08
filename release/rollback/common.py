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
from typing import Iterable, Optional


@dataclasses.dataclass(frozen=True)
class Service:
    """Information about an AppEngine service.

    Holds an AppEngine service id and a collection of version deployed in this
    service.

    Attributes:
        service_id: An AppEngine service id.
        version_ids: A collection of AppEngine version ids deployed in this
            service.
    """

    service_id: str
    version_ids: Iterable[str]

    def __post_init__(self) -> None:
        if not isinstance(self.version_ids, (tuple, frozenset)):
            object.__setattr__(self, 'appengine_versions',
                               frozenset(self.version_ids))


@dataclasses.dataclass(frozen=True)
class VersionConfig:
    """Configurations of an AppEngine version.

    Holds rollback-related configuration of an AppEngine version. These are the
    static configuration from each service's appengine-web.xml file.

    Attributes:
        service_id: An AppEngine service id.
        version_id: An AppEngine versions id.
        manual_scaling_instances: For a version with manual scaling, the
            configured number of instances. For all other versions, this value
            should be None.
    """

    service_id: str
    version_id: str
    manual_scaling_instances: Optional[int] = None

    def is_manual_scaling(self):
        return self.manual_scaling_instances is not None
