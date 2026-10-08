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

import hashlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


MIGRATOR = Path(__file__).resolve().parents[1] / "scripts/migrate-maven-coordinates.py"
VERIFIER_PATH = Path(__file__).resolve().parents[1] / "scripts/verify-release-artifacts.py"
SPEC = importlib.util.spec_from_file_location("release_verifier", VERIFIER_PATH)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <!-- Preserve formatting and application coordinates. -->
  <groupId>com.example</groupId><artifactId>app</artifactId>
  <parent><groupId>io.github.agentic-ai</groupId><artifactId>argi</artifactId></parent>
  <dependencyManagement><dependencies><dependency>
    <groupId>io.github.agentic-ai</groupId><artifactId>argi-bom</artifactId>
    <version>2.1.0-dev</version>
  </dependency></dependencies></dependencyManagement>
  <dependencies>
    <dependency><groupId>io.github.agentic-ai</groupId><artifactId>argi-studio</artifactId></dependency>
    <dependency><groupId>io.github.agentic-ai</groupId><artifactId>argi-graph-persistence-jdbc</artifactId></dependency>
  </dependencies>
</project>
"""


class CentralBundleTest(unittest.TestCase):
    def write_bundle(self, directory, extra=None, omit_pom=False, corrupt=False):
        source = Path(directory) / "payload.pom"
        source.write_bytes(b"<project/>")
        name = "io/github/agentic-ai-java/argi/2.1.0-RC1/argi-2.1.0-RC1.pom"
        bundle = Path(directory) / "central-bundle.zip"
        with ZipFile(bundle, "w") as archive:
            payload = b"corrupt" if corrupt else source.read_bytes()
            if not omit_pom:
                archive.writestr(name, payload)
            for algorithm in ("md5", "sha1"):
                archive.writestr(name + "." + algorithm, hashlib.new(algorithm, payload).hexdigest())
            if extra:
                archive.writestr(extra, b"<metadata/>")
        return bundle, {name: source}

    def test_valid_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            VERIFIER.verify_bundle(*self.write_bundle(directory))

    def test_repository_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle, payloads = self.write_bundle(
                directory, extra="io/github/agentic-ai-java/argi/maven-metadata.xml"
            )
            with self.assertRaisesRegex(ValueError, "unexpected files"):
                VERIFIER.verify_bundle(bundle, payloads)

    def test_missing_pom_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "Missing or mismatched"):
                VERIFIER.verify_bundle(*self.write_bundle(directory, omit_pom=True))

    def test_changed_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "Missing or mismatched"):
                VERIFIER.verify_bundle(*self.write_bundle(directory, corrupt=True))


class MigrationTest(unittest.TestCase):
    def test_preview_then_write_preserves_external_coordinates_and_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(POM)
            subprocess.run([sys.executable, str(MIGRATOR), str(pom)], check=True)
            self.assertEqual(POM, pom.read_text())
            self.assertEqual([pom], list(Path(directory).iterdir()))
            subprocess.run([sys.executable, str(MIGRATOR), "--write", str(pom)], check=True)
            backup = pom.with_name("pom.xml.before-argi-rc1")
            self.assertEqual(POM, backup.read_text())
            updated = pom.read_text()
            self.assertEqual(3, updated.count("<groupId>io.github.agentic-ai-java</groupId>"))
            self.assertIn("<groupId>io.github.agentic-ai</groupId><artifactId>argi-graph-persistence-jdbc", updated)
            self.assertIn("<version>2.1.0-dev</version>", updated)
            self.assertIn("<!-- Preserve formatting and application coordinates. -->", updated)
            subprocess.run([sys.executable, str(MIGRATOR), "--write", str(pom)], check=True)
            self.assertEqual(updated, pom.read_text())
            self.assertEqual(POM, backup.read_text())

    def test_existing_backup_stops_write(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(POM)
            backup = pom.with_name("pom.xml.before-argi-rc1")
            backup.write_text("Earlier backup\n")
            result = subprocess.run([sys.executable, str(MIGRATOR), "--write", str(pom)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(POM, pom.read_text())
            self.assertEqual("Earlier backup\n", backup.read_text())

    def test_invalid_pom_stops_before_any_write(self):
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            broken = Path(directory) / "broken.xml"
            pom.write_text(POM)
            broken.write_text("<project>")
            result = subprocess.run([sys.executable, str(MIGRATOR), "--write", str(pom), str(broken)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(POM, pom.read_text())
            self.assertFalse(pom.with_name("pom.xml.before-argi-rc1").exists())


if __name__ == "__main__":
    unittest.main()
