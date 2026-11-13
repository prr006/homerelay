#!/usr/bin/env python3
"""Attach a gradle failure log to issue #1 via the GitHub API (curl-free).

The build sandbox cannot download workflow logs, so the runner itself posts the
log tail as an issue comment. No secrets are embedded; GITHUB_TOKEN comes from
the workflow environment.
"""
import json
import os
import sys
import urllib.request

log_name, step, log_path = sys.argv[1], sys.argv[2], sys.argv[3]
with open(log_path, "rb") as handle:
    log = handle.read()[-48000:].decode("utf-8", "replace")
body = f"## {step} failed on commit {os.environ.get('GITHUB_SHA', '?')}\n\n```\n{log}\n```"
req = urllib.request.Request(
    f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/issues/1/comments",
    data=json.dumps({"body": body}).encode("utf-8"),
    headers={
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=30) as resp:
        print("posted issue comment:", resp.status)
except Exception as e:  # diagnostics must never mask the build failure
    print(f"could not post comment (non-fatal): {e}")
