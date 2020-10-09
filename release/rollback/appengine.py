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
"""Helper for using the AppEngine Admin REST API."""

from typing import Any, Dict, FrozenSet

from googleapiclient import discovery
from googleapiclient import http

import common


class PagingError(Exception):
    """Error for unexpected partial results.

    List calls in this module do not handle pagination. This error is raised
    when a partial result is received.
    """
    def __init__(self, uri: str):
        super().__init__(
            self, f'Received paged response unexpectedly when calling {uri}. '
            'Consider increase PAGE_SIZE.')


class AppEngineAdmin:
    """Wrapper around the AppEngine Admin REST API client.

    This class provides wrapper methods around the RESET API for service and
    version queries and for migrating between versions.
    """

    # AppEngine services under management.
    SERVICES = frozenset(['backend', 'default', 'pubapi', 'tools'])

    # Forces 'list' calls (for services and versions) to return all
    # results in one shot, to avoid having to handl pagination. This values
    # should be greater than the maximum allowed services and versions in any
    # project (
    # https://cloud.google.com/appengine/docs/standard/python/an-overview-of-app-engine#limits).
    PAGE_SIZE = 250

    def __init__(self, project: str) -> None:
        """Initialize this instance for an AppEngine(GCP) project."""
        self._project = project
        self._services = discovery.build('appengine',
                                         'v1beta').apps().services()

    def _checked_request(self, request: http.HttpRequest) -> Dict[str, Any]:
        """Verifies that all results are returned for a request."""

        response = request.execute()

        if 'nextPageToken' in response:
            raise PagingError(request.uri)

        return response

    def get_serving_versions(self) -> FrozenSet[common.VersionKey]:
        """Returns the serving versions of every Nomulus service.

        For each service in AppEngineAdmin.SERVICES, gets the version(s)
        actually serving traffic. Services with the 'SERVING' status but no
        allocated traffic are not included.

        Returns: An immutable collection of the serving versions grouped by
            service.
        """

        response = self._checked_request(
            self._services.list(appsId=self._project,
                                pageSize=AppEngineAdmin.PAGE_SIZE))

        # Response format is specified at
        # http://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.html#list.

        versions = []
        for service in response.get('services', []):
            if service['id'] in AppEngineAdmin.SERVICES:
                versions_with_traffic = service.get('split',
                                                    {}).get('allocations',
                                                            {}).keys()
                for version in versions_with_traffic:
                    versions.append(common.VersionKey(service['id'], version))

        return frozenset(versions)

    def get_version_configs(
        self, versions: FrozenSet[common.VersionKey]
    ) -> FrozenSet[common.VersionConfig]:
        """Returns the configuration of requested versions.

        For each version in the request, gets the rollback-related data from
        its static configuration (found in appengine-web.xml).

        Args:
            versions: A collection of the Service objects, each containing the
                versions being queried in that service.

        Returns:
            The version configurations in an immutable collection.
        """

        requested_services = set([version.service_id for version in versions])

        version_configs = []
        for service_id in requested_services:
            response = self._checked_request(self._services.versions().list(
                appsId=self._project,
                servicesId=service_id,
                pageSize=AppEngineAdmin.PAGE_SIZE))

            # Format of version_list is defined at
            # https://googleapis.github.io/google-api-python-client/docs/dyn/appengine_v1beta5.apps.services.versions.html#list

            for version in response.get('versions', []):
                if common.VersionKey(service_id, version['id']) in versions:
                    # This value may be None if version is not on manual scaling.
                    manual_instances = version.get('manualScaling',
                                                   {}).get('instances')
                    version_configs.append(
                        common.VersionConfig(service_id, version['id'],
                                             manual_instances))

        return frozenset(version_configs)
