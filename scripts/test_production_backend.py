"""Run the required pilot backend contracts and reject missing or skipped JUnit cases."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CLASSES = [
    "ProductionTrafficGateTest",
    "billing.SubscriptionBillingEngineTest",
    "billing.subscription.SubscriptionBillingConfigTest",
    "platform.PlatformBillingRoutesTest",
    "billing.SubscriptionBillingPostgresTest",
    "billing.ProductionBillingPostgresTest",
]


def main():
    classes = ["com.hookah.platform.backend." + name for name in CLASSES]
    directory = ROOT / "backend/app/build/test-results/test"
    for name in classes:
        (directory / f"TEST-{name}.xml").unlink(missing_ok=True)
    selectors = [arg for name in classes for arg in ("--tests", name)]
    subprocess.run(["./gradlew", "--no-daemon", "--no-build-cache", "--max-workers=1",
                    ":backend:app:test", "--rerun", *selectors, "--console=plain"], cwd=ROOT, check=True)
    for name in classes:
        count = check_report(directory / f"TEST-{name}.xml", name)
        print(f"{name}: {count} passed")


def check_report(report, name):
    suite = ET.parse(report).getroot()
    cases = suite.findall("testcase")
    if (suite.tag != "testsuite" or suite.get("name") != name or not cases
            or int(suite.attrib["tests"]) != len(cases)
            or any(int(suite.attrib[k]) for k in ("errors", "failures", "skipped"))):
        raise SystemExit("Incomplete or unsuccessful pilot suite: " + name)
    for case in cases:
        if case.get("classname") != name or any(child.tag in ("skipped", "failure", "error") for child in case):
            raise SystemExit("Incomplete or unsuccessful pilot case: " + name)
    return len(cases)


if __name__ == "__main__":
    main()
