#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prints every line and branch a Kover XML report (JaCoCo format) leaves uncovered, so that a failed koverVerify
says where without downloading the report."""

import glob
import sys
import xml.etree.ElementTree as ElementTree


def missed(path):
    root = ElementTree.parse(path).getroot()
    for package in root.iter("package"):
        for source in package.iter("sourcefile"):
            for line in source.iter("line"):
                missed_instructions = int(line.get("mi", "0"))
                missed_branches = int(line.get("mb", "0"))
                if missed_instructions or missed_branches:
                    yield f"{package.get('name')}/{source.get('name')}:{line.get('nr')}", missed_instructions, missed_branches


def main(pattern="**/build/reports/kover/report*.xml"):
    found = False
    for path in glob.glob(pattern, recursive=True):
        for where, instructions, branches in missed(path):
            found = True
            print(f"{where} missed instructions={instructions} branches={branches}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
