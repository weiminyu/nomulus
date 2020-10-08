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
"""Helper for managing Nomulus deployment records on GCS."""

from typing import Iterable

from google.cloud import storage

import common


class GcsClient:
    """Manages Nomulus deployment records on GCS."""
    def __init__(self, project: str, gcs_client=None) -> None:
        """Initializes the instance for a GCP project.

        Attributes:
            project: The GCP project with Nomulus deployment records.
            gcs_client: Optional API client to use.
        """

        self._project = project
        self._client = gcs_client if gcs_client else storage.Client(
            self._project)

    def _get_deploy_bucket_name(self):
        return f'{self._project}-deployed-tags'

    @staticmethod
    def _get_version_map_name(env: str):
        return f'nomulus.{env}.versions'

    def get_versions_by_release(self, env: str,
                                nom_tag: str) -> Iterable[common.Service]:
        """Returns AppEngine version ids of a given Nomulus release tag.

        Fetches the version mapping file maintained by the deployment process
        and parses its content into a collection of Service instances.
        Each line in the file is in this format
        '${RELEASE_TAG},{APP_ENGINE_SERVICE_ID},{APP_ENGINE_VERSION}'.

        A release may map to multiple versions in a service if it has been
        deployed multiple times. This is not intended behavior and may only
        happen by mistake.

        Args:
            env: The environment of the deployed release, e.g., sandbox.
            nom_tag: The Nomulus release tag.

        Returns:
            An immutable collection of Service instances.
        """

        file_content = self._client.get_bucket(
            self._get_deploy_bucket_name()).get_blob(
                GcsClient._get_version_map_name(env)).download_as_text()

        version_map = {}
        for line in file_content.splitlines(False):
            tag, service, appengine_version = line.split(',')
            if nom_tag == tag:
                version_map.setdefault(service, set()).add(appengine_version)

        return tuple([
            common.Service(service, versions)
            for service, versions in version_map.items()
        ])
