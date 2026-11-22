"""Run the required pilot backend contracts and reject missing or skipped JUnit cases."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RECOVERY_HOLD_CASE = "disaster recovery hold survives restart and denies even matching activation and ingress tickets"
REQUIRED_GATE_CASES = (
    RECOVERY_HOLD_CASE,
    "recovering poller checks backlog without acknowledging or processing commands",
    "confirmed update cannot be claimed again by a restarted router",
    "closed admission leaves queued outbox rows untouched even when the worker ticks",
    "prepared activation polls without dispatching booking commands or starting outbox",
    "only a successful getUpdates from this worker admits and extended failure revokes",
)
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
        if (case.get("classname") != name or case.get("status", "run") not in ("run", "passed")
                or any(child.tag in ("skipped", "failure", "error") for child in case)):
            raise SystemExit("Incomplete or unsuccessful pilot case: " + name)
    if name == "com.hookah.platform.backend.ProductionTrafficGateTest":
        names = [case.get("name", "").removesuffix("()") for case in cases]
        for required in REQUIRED_GATE_CASES:
            if names.count(required) != 1:
                raise SystemExit("Missing or duplicate production admission testcase: " + required)
    return len(cases)


if __name__ == "__main__":
    main()
