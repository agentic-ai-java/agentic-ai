#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


BASE_URL = "https://central.sonatype.com/api/v1/publisher"


def request(endpoint, token, body=None, content_type=None):
    headers = {"Authorization": "Bearer " + token}
    if content_type:
        headers["Content-Type"] = content_type
    for attempt in range(2):
        try:
            req = urllib.request.Request(BASE_URL + endpoint, data=body, headers=headers, method="POST")
            with urllib.request.urlopen(req, timeout=120) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            if attempt == 0 and (exc.code == 429 or exc.code >= 500):
                time.sleep(20 if exc.code == 429 else 2)
                continue
            raise RuntimeError(f"Central API returned HTTP {exc.code}; check Portal before uploading again") from None
        except (urllib.error.URLError, TimeoutError):
            # Retrying status is safe; an interrupted upload may already have created a deployment.
            if attempt == 0 and endpoint.startswith("/status"):
                time.sleep(2)
                continue
            raise RuntimeError("Central API connection failed; check Portal before uploading again") from None


def upload(bundle_path, token):
    boundary = "argi-" + uuid.uuid4().hex
    prefix = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    body = prefix + bundle_path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    query = urllib.parse.urlencode({"name": os.environ.get("RELEASE_TAG", "ARGI"), "publishingType": "AUTOMATIC"})
    result = request("/upload?" + query, token, body, "multipart/form-data; boundary=" + boundary)
    return str(uuid.UUID(result.decode().strip()))


def save_status(record_path, result):
    # Only public deployment details are archived; credentials never leave process memory.
    record = {key: result[key] for key in ("deploymentId", "deploymentState", "errors", "purls") if key in result}
    record_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")


def wait_until_published(deployment_id, token, record_path):
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        result = json.loads(request("/status?" + urllib.parse.urlencode({"id": deployment_id}), token))
        if result.get("deploymentId") != deployment_id:
            raise RuntimeError("Central returned an unexpected deployment ID")
        save_status(record_path, result)
        state = result.get("deploymentState")
        print(f"Central deployment {deployment_id}: {state}", flush=True)
        if state == "PUBLISHED":
            return
        if state == "FAILED":
            raise RuntimeError("Central validation failed: " + json.dumps(result.get("errors", {})))
        time.sleep(10)
    raise RuntimeError(f"Central deployment {deployment_id} did not finish; query Portal before retrying")


def main():
    parser = argparse.ArgumentParser(description="Upload a validated signed bundle to Central and wait for publication")
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    username = os.environ["CENTRAL_USERNAME"]
    password = os.environ["CENTRAL_PASSWORD"]
    if not username or not password:
        raise ValueError("Central credentials must be nonempty")
    token = base64.b64encode((username + ":" + password).encode()).decode()
    record_path = args.bundle.parent / "deployment.json"
    if record_path.exists():
        # Resume polling the recorded deployment instead of repeating an upload.
        deployment_id = str(uuid.UUID(json.loads(record_path.read_text())["deploymentId"]))
    else:
        deployment_id = upload(args.bundle, token)
        save_status(record_path, {"deploymentId": deployment_id, "deploymentState": "UPLOADED"})
    print(f"Central deployment ID: {deployment_id}", flush=True)
    wait_until_published(deployment_id, token, record_path)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError) as exc:
        print(f"Central publication failed: {exc}", file=sys.stderr)
        sys.exit(1)
