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
"""Wrapper around the AppEngine Admin API REST Client."""

from googleapiclient import discovery
from typing import Dict, Iterable, Set, Tuple


class PagingError(Exception):
    """"""
    def __init__(self, *args):
        super().__init__(self, 'Expecting full result, got paged response:',
                         *args)


class ScalingError(Exception):
    """"""
    def __init__(self, *args):
        super().__init__(self, 'Expecting exactly one value, found', *args)


def _parse_services_response(response):

    return dict([(service['id'], (service['name'],
                                  service.get('split',
                                              {}).get('allocations',
                                                      {}).keys()))
                 for service in response.get('services', [])])


class AppEngineAdmin:
    """Wrapper"""

    # Nomulus services under management by this class.
    SERVICES = ('default', 'pubapi', 'backend', 'tools')

    SCALINGS = set(['automaticScaling', 'basicScaling', 'manualScaling'])

    # A large page size that forces all list() calls to return the full result in one shot.
    # This value is greater than
    # the max allowed services and versions in the app and the number of
    # instances in each version.
    PAGE_SIZE = 250

    def __init__(self, project: str) -> None:
        print(f'Admin client called on {project}.')
        self._project = project
        self._services = discovery.build('appengine',
                                         'v1beta').apps().services()

    def _fetch_services(self) -> Dict[str, Tuple[str, Dict[str, float]]]:
        response = self._services.list(
            appsId=self._project, pageSize=AppEngineAdmin.PAGE_SIZE).execute()

        if 'nextPageToken' in response:
            raise PagingError('_fetch_services')

        return response

    def get_services(self) -> Dict[str, Tuple[str, Dict[str, float]]]:
        return _parse_services_response(self._fetch_services())

    def get_available_versions(self) -> Dict[str, Set[str]]:
        """"""

        per_service_versions = {}
        for services_id in AppEngineAdmin.SERVICES:
            response = self._services.versions().list(
                appsId=self._project,
                servicesId=services_id,
                pageSize=AppEngineAdmin.PAGE_SIZE).execute()

            if 'nextPageToken' in response:
                raise PagingError('_fetch_services')

            per_service_versions[services_id] = set(
                [version['id'] for version in response.get('versions', [])])

        return per_service_versions

    def get_service_instance_count(self, versions: Dict[str, Iterable[str]]):
        """"""

        print('Gettign per-service instance counts')
        return dict([
            (service,
             sum([
                 self.get_service_version_instance_count(service, version)
                 for version in versions
                 if self.is_version_manual_scaling(service, version)
             ])) for service, versions in versions.items()
        ])

    def get_service_version_instance_count(self, service_id: str,
                                           version: str):
        """"""

        print(f"Getting instance count for {service_id}/{version}")
        response = self._services.versions().instances().list(
            appsId=self._project,
            servicesId=service_id,
        )

        if 'nextPageToken' in response:
            raise PagingError('_fetch_services')

        return len(response.get('instances', []))

    def is_version_manual_scaling(self, service_id, versiond_id):
        """"""

        response = self._services.versions().get(
            appsId=self._project,
            servicesId=service_id,
            versionsId=versiond_id).execute()

        scaling = AppEngineAdmin.SCALINGS.intersection(response.keys())

        if len(scaling) != 1:
            raise ScalingError(*scaling)

        print(f'{service_id}/{versiond_id} has {scaling}')
        return 'manualScaling' in scaling

    """def list_services(self) -> None:

        reply = self.service.apps().services().list(
            appsId=self._project).execute()
        print(reply)

    def list_versions(self) -> None:
        reply = self.service.apps().services().versions().list(
            appsId=self._project, servicesId = 'default').execute()
        print(reply)
    """
