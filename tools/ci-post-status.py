#!/usr/bin/env python3
"""Post a commit status carrying the key error line of a CI failure.

HomeRelay CI helper: the build sandbox cannot stream workflow logs, so the runner
publishes the most relevant error line as a commit status. Description is capped at
120 chars by the API. GH_TOKEN is provided by the workflow environment.
"""
import json
import os
import re
import sys
import urllib.request

context, log_path = sys.argv[1], sys.argv[2]
with open(log_path, "rb") as handle:
    text = handle.read().decode("utf-8", "replace")
lines = [l.rstrip() for l in text.splitlines() if l.strip()]
pattern = re.compile(r"(What went wrong|Could not|Execution failed|FAILED|error:|Exception|Caused by|e: )")
candidates = [l for l in lines if pattern.search(l)]
summary = candidates[-1] if candidates else (lines[-1] if lines else "unknown error")
description = summary[:140]
body = {
    "state": "failure",
    "context": context,
    "description": description,
    "target_url": f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
}
req = urllib.request.Request(
    f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/statuses/{os.environ['GITHUB_SHA']}",
    data=json.dumps(body).encode("utf-8"),
    headers={
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    },
    method="POST",
)
with urllib.request.urlopen(req, timeout=30) as resp:
    print("posted status:", resp.status, context, "->", description)
