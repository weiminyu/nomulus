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

import appengine
import argparse
import sys
from typing import Dict, List, Set

from google.cloud import storage

import common
import gcs

HELP_TEXT = 'Rolls back the Nomulus server in an AppEngine environment.'

FLAGS = [(['--dev_project', '-d'], {
    'help': 'The GCP project with the deployment infrastructure.',
    'required': True
}),
         (['--project', '-p'], {
             'help': 'The GCP project where the Nomulus server is deployed.',
             'required': True
         }),
         (['--env', '-e'], {
             'help': 'The name of the Nomulus server environment.',
             'required': True,
             'choices': ['production', 'sandbox', 'crash', 'alpha']
         }),
         (['--target_release', '-t'], {
             'help': 'The release to be deployed.',
             'required': True
         })]


def get_mappings_on_gcs(dev_project: str, env: str) -> str:
    """Download the version mapping file on GCS"""

    client = storage.Client(dev_project)
    return client.get_bucket(f'{dev_project}-deployed-tags').get_blob(
        f'nomulus.{env}.versions').download_as_text()


def find_appengine_versions(
        dev_project: str, env: str, tag: str,
        available_versions: Dict[str, Set[str]]) -> Dict[str, List[str]]:
    """Find the AppEngine versions"""

    mappings = get_mappings_on_gcs(dev_project, env)
    versions = {}
    for line in mappings.splitlines(False):
        nom_tag, service, appengine_version = line.split(',')
        if nom_tag == tag and service in available_versions and appengine_version in available_versions[
                service]:
            if appengine_version not in versions:
                versions[appengine_version] = []
            versions[appengine_version].append(service)

    return versions


def rollback(args: argparse.Namespace) -> int:
    """"""
    gcs_client = gcs.GcsClient(args.dev_project)
    target_versions = gcs_client.get_versions_by_release(
        args.env, args.target_release)
    print(target_versions)

    appengine_admin = appengine.AppEngineAdmin(args.project)
    versions_to_rollback = appengine_admin.get_serving_versions()
    print(
        appengine_admin.get_version_configs(
            [*target_versions, *versions_to_rollback]))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(prog='nom_rollback',
                                     description=HELP_TEXT)
    for flag in FLAGS:
        parser.add_argument(*flag[0], **flag[1])

    args = parser.parse_args()

    return rollback(args)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as ex:
        print(str(ex))
        sys.exit(1)
