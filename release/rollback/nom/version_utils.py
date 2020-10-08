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
"""Tools for matching Nomulus release tags to AppEngine versions."""
import dataclasses
from google.cloud import storage
from googleapiclient import discovery
from typing import Any, Dict, Iterable, NamedTuple, Optional


@dataclasses.dataclass(frozen=True)
class ServiceVersions:
    """AppEngine versions deployed to a given service.

    Holds an AppEngine service id and a collection of version strings in this
    service.

    Example use cases:
        In get_version_map() -- it contains AppEngine versions of a Nomulus
            release deployed to a service.
        In AppEngineAdmin.getLiveVersions() -- it contains all versions
            concurrently serving in the service.

    Attributes:
         service: An AppEngine service id in the Nomulus server.
         appengine_versions: A collection of AppEngine versions in this service.
         nom_tag: When present, the Nomulus release tag of the members of
                appengine_versions.
    """

    service: str
    appengine_versions: Iterable[str]
    nom_tag: Optional[str] = dataclasses.field(default=None)

    def __post_init__(self) -> None:
        if not isinstance(self.appengine_versions, (tuple, frozenset)):
            object.__setattr__(self, 'appengine_versions',
                               frozenset(self.appengine_versions))


@dataclasses.dataclass(frozen=True)
class VersionConfig:
    """Rollback-related configurations of an AppEngine version."""

    service: str
    appengine_version: str
    manual_scaling_instance: Optional[int] = None

    def is_manual_scaling(self):
        return self.manual_scaling_instance is not None


def get_version_map(dev_project: str, env: str,
                    nom_tag: str) -> Iterable[ServiceVersions]:
    """Fetches the version mapping file on GCS.

    Fetches the version mapping file and parses its content into a collection of
    ServiceVersions instances.

    Args:
        dev_project: The GCP project that hosts the deployment infrastructure.
        env: The environment name of the deployed version, e.g., sandbox.
        nom_tag: The tag of the release whose AppEngine version strings are
          needed.

    Returns:
        A collection of ServiceVersions instances.
    """

    client = storage.Client(dev_project)
    file_content = client.get_bucket(f'{dev_project}-deployed-tags').get_blob(
        f'nomulus.{env}.versions').download_as_text()
    return _parse_version_map(file_content, nom_tag)


def _parse_version_map(mappings_text: str,
                       nom_tag: str) -> Iterable[ServiceVersions]:
    """Parses the content of the mapping file into a VersionMap instance."""

    version_map = {}
    for line in mappings_text.splitlines(False):
        tag, service, appengine_version = line.split(',')
        if nom_tag == tag:
            version_map.setdefault(service, set()).add(appengine_version)

    return tuple([
        ServiceVersions(service, versions, nom_tag)
        for service, versions in version_map.items()
    ])


class PagingError(Exception):
    """"""
    def __init__(self, *args):
        super().__init__(
            self, 'Expecting full result, got paged response for request:',
            *args)


class AppEngineAdmin:
    """Wrapper around the AppEngine Admin REST API client."""

    # AppEngine services under management.
    SERVICES = ('backend', 'default', 'pubapi', 'tools')

    # Recognized scaling schemes.
    SCALINGS = frozenset(['automaticScaling', 'basicScaling', 'manualScaling'])

    # Forces AppEngine 'list' calls (for services and versions) to return all
    # results in one shot. This values is greater than the maximum allowed services
    # and versions in any project (
    # https://cloud.google.com/appengine/docs/standard/python/an-overview-of-app-engine#limits).
    PAGE_SIZE = 250

    def __init__(self, project: str) -> None:
        self._project = project
        self._services = discovery.build('appengine',
                                         'v1beta').apps().services()

    def _checked_list(self, action: Any) -> Dict[str, Any]:
        """Verifies that all results are returned for a list call."""

        response = action.execute()

        if 'nextPageToken' in response:
            raise PagingError(str(action))

        return response

    def get_serving_versions(self) -> Iterable[ServiceVersions]:

        service_list = self._checked_list(
            self._services.list(appsId=self._project,
                                pageSize=AppEngineAdmin.PAGE_SIZE))

        return AppEngineAdmin._parse_service_list(service_list)

    @staticmethod
    def _parse_service_list(
            service_list: Dict[str, Any]) -> Iterable[ServiceVersions]:
        """Parses the response of a list_service call.

        Input format is specified at
        http://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.html#list.
        """

        return tuple([
            ServiceVersions(
                service['id'],
                service.get('split', {}).get('allocations', {}).keys())
            for service in service_list.get('services', [])
        ])

    def get_version_configs(
            self,
            versions: Iterable[ServiceVersions]) -> Iterable[VersionConfig]:
        """"""

        deduplicated_versions = {}
        for sv in versions:
            deduplicated_versions.setdefault(sv.service, set()).update(
                sv.appengine_versions)

        version_configs = []
        for service_id, appengine_versions in deduplicated_versions.items():
            version_list = self._checked_list(self._services.versions().list(
                appsId=self._project,
                servicesId=service_id,
                pageSize=AppEngineAdmin.PAGE_SIZE))

            # Format of version_list is defined at
            # https://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.versions.html#list

            for version in version_list.get('versions', []):
                if version['id'] in appengine_versions:
                    version_configs.append(
                        VersionConfig(
                            service_id, version['id'],
                            version.get('manualScaling', {}).get('instances')))

        return tuple(version_configs)
