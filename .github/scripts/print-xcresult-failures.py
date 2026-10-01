#!/usr/bin/env python3
"""Print failed test cases and their assertion messages from an .xcresult bundle.

Usage: print-xcresult-failures.py <bundle.xcresult>
xcodebuild's console output carries test names only; the assertion text lives in the bundle.
"""
import json
import subprocess
import sys


def walk(node, trail):
    if isinstance(node, dict):
        kind = node.get("nodeType")
        if kind == "Test Case" and node.get("result") != "Passed":
            trail = trail + [node.get("name", "?")]
            print("===", node.get("result", "?").upper() + ":", " / ".join(trail), "(", node.get("duration", "?"), ")")
        elif kind == "Repetition":
            print("   -", node.get("name", ""), node.get("result", ""), "(", node.get("duration", "?"), ")")
        elif kind == "Failure Message":
            print("      ", node.get("name", ""))
        for child in node.get("children", []):
            walk(child, trail)
    elif isinstance(node, list):
        for child in node:
            walk(child, trail)


def main():
    bundle = sys.argv[1]
    raw = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", bundle],
        check=True, capture_output=True, text=True,
    ).stdout
    walk(json.loads(raw).get("testNodes", []), [])


if __name__ == "__main__":
    main()
