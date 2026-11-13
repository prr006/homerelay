#!/usr/bin/env python3
"""Post a completed check-run carrying the tail of a build log.

Written for HomeRelay CI: the sandbox API relay cannot fetch workflow logs, so the
runner itself reports the failure text through the checks API. No secrets are
embedded; GH_TOKEN is provided by the workflow environment.
"""
import json
import os
import sys
import urllib.request

name, title, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(log_path, "rb") as handle:
    text = handle.read()[-55000:].decode("utf-8", "replace")
body = {
    "name": name,
    "head_sha": os.environ["GITHUB_SHA"],
    "status": "completed",
    "conclusion": "failure",
    "output": {
        "title": title,
        "summary": "Gradle failed; log tail attached as text (last ~55 KB).",
        "text": "```\n" + text + "\n```",
    },
}
req = urllib.request.Request(
    f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/check-runs",
    data=json.dumps(body).encode("utf-8"),
    headers={
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    },
    method="POST",
)
with urllib.request.urlopen(req, timeout=30) as resp:
    print("posted check-run:", resp.status)
