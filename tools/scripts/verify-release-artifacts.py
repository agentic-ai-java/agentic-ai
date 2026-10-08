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
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile
from zipfile import BadZipFile


REPO_ROOT = Path(__file__).resolve().parents[2]
GROUP_ID = "io.github.agentic-ai-java"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def verify_signature(signature: Path, payload: Path) -> None:
    if not signature.is_file():
        raise ValueError(f"Missing release signature: {signature}")
    result = subprocess.run(["gpg", "--batch", "--verify", str(signature), str(payload)], capture_output=True)
    if result.returncode:
        raise ValueError(f"Invalid release signature: {signature}")


def verify(require_signatures: bool = False) -> None:
    root = ET.parse(REPO_ROOT / "pom.xml").getroot()
    version = root.findtext("m:properties/m:revision", namespaces=NS)
    if not version or "${" in version or version.endswith(("-dev", "-SNAPSHOT")):
        raise ValueError("Release version must be concrete and non-SNAPSHOT")
    modules = ["."] + [node.text for node in root.findall("m:modules/m:module", NS)]
    runtime_artifacts = set()
    for module in modules:
        directory = REPO_ROOT / module
        pom = ET.parse(directory / ".flattened-pom.xml").getroot()
        artifact = pom.findtext("m:artifactId", namespaces=NS)
        group = pom.findtext("m:groupId", namespaces=NS) or pom.findtext("m:parent/m:groupId", namespaces=NS)
        resolved_version = pom.findtext("m:version", namespaces=NS) or pom.findtext("m:parent/m:version", namespaces=NS)
        if group != GROUP_ID or resolved_version != version:
            raise ValueError(f"Invalid release coordinate: {module}")
        parent = pom.find("m:parent", NS)
        if parent is not None and parent.findtext("m:groupId", namespaces=NS) == GROUP_ID:
            if parent.findtext("m:version", namespaces=NS) != version:
                raise ValueError(f"Invalid release parent version: {module}")
        for node in pom.iter():
            if node.text and "${revision}" in node.text:
                raise ValueError(f"Unresolved revision in published POM: {module}")
            if node.tag.endswith("}groupId") and node.text == "io.github.agentic-ai":
                raise ValueError(f"Old Maven namespace in published POM: {module}")
        if require_signatures:
            verify_signature(directory / "target" / f"{artifact}-{version}.pom.asc", directory / ".flattened-pom.xml")
        if pom.findtext("m:packaging", default="jar", namespaces=NS) == "pom":
            continue
        runtime_artifacts.add(artifact)
        for suffix in ("", "-sources", "-javadoc"):
            jar_path = directory / "target" / f"{artifact}-{version}{suffix}.jar"
            if require_signatures:
                verify_signature(jar_path.with_name(jar_path.name + ".asc"), jar_path)
            with ZipFile(jar_path) as archive:
                if not archive.namelist() or archive.testzip() is not None:
                    raise ValueError(f"Invalid release JAR: {jar_path}")
                if suffix:
                    continue
                for name in archive.namelist():
                    if name.endswith(".class"):
                        with archive.open(name) as stream:
                            header = stream.read(8)
                        if header[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(header[6:8], "big") > 61:
                            raise ValueError(f"Class is not compatible with Java 17: {artifact}/{name}")
                if artifact == "argi-studio" and "META-INF/resources/chatui/index.html" not in archive.namelist():
                    raise ValueError("Studio release JAR must include the embedded UI")
    bom = ET.parse(REPO_ROOT / "argi-bom/.flattened-pom.xml").getroot()
    bom_version = bom.findtext("m:version", namespaces=NS)
    parent_pom = ET.parse(REPO_ROOT / ".flattened-pom.xml").getroot()
    if parent_pom.find("m:dependencyManagement/m:dependencies", NS) is None:
        raise ValueError("Published parent POM must preserve dependency management")
    managed = set()
    for dependency in bom.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
        if dependency.findtext("m:groupId", namespaces=NS) == GROUP_ID:
            managed_version = dependency.findtext("m:version", default="", namespaces=NS)
            # Maven evaluates this standard property against the published BOM itself.
            managed_version = managed_version.replace("${project.version}", bom_version or "")
            if managed_version != version:
                raise ValueError("BOM must manage the exact release version")
            managed.add(dependency.findtext("m:artifactId", namespaces=NS))
    if managed != runtime_artifacts:
        raise ValueError(f"BOM/runtime artifact mismatch: {managed ^ runtime_artifacts}")
    print(f"Release artifacts verified: {GROUP_ID}, version {version}, {len(modules)} modules, Java 17")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify Maven release artifacts and optional GPG signatures")
    parser.add_argument("--signed", action="store_true", help="Require and verify all release artifact signatures")
    args = parser.parse_args()
    try:
        verify(args.signed)
    except (OSError, ET.ParseError, ValueError, BadZipFile) as exc:
        print(f"Release artifact verification failed: {exc}", file=sys.stderr)
        sys.exit(1)
