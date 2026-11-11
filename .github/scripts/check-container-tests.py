"""Reject a green CI build that skipped the project's real data-store tests."""

from pathlib import Path
from xml.etree import ElementTree

required = [path.stem for path in Path("src/test/java").rglob("*IntegrationTest.java")]
if not required:
    raise SystemExit("No integration suites found in the backend test sources")

for suite in sorted(required):
    reports = list(Path("build/test-results/test").glob(f"TEST-*.{suite}.xml"))
    if len(reports) != 1:
        raise SystemExit(f"Expected one test report for {suite}, found {len(reports)}")
    root = ElementTree.parse(reports[0]).getroot()
    if int(root.attrib["tests"]) == 0 or int(root.attrib["skipped"]) != 0:
        raise SystemExit(f"{suite} must execute all database-backed tests: {root.attrib}")
    print(f"{suite}: {root.attrib['tests']} tests executed")
