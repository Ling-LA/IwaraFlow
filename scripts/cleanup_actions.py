"""Keep 50 APK/promo builds and one maintenance run; never touch Releases."""

import datetime
import json
import os
import time
import urllib.error
import urllib.request


BUILD_PATHS = frozenset({".github/workflows/build-apk.yml", ".github/workflows/record-promo.yml"})
CLEANUP_WORKFLOW = "cleanup-actions.yml"


def select_old_runs(runs, keep, current_run_id):
    # Snapshot all pages before deleting: deleting while paging skips records.
    unique = {}
    for run in runs:
        run_id = int(run["id"])
        created = datetime.datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
        if run_id <= 0 or created.tzinfo is None or not run.get("status"):
            raise ValueError("Invalid workflow run metadata")
        unique[run_id] = (created, run)
    ordered = sorted(unique, key=lambda key: (unique[key][0], key), reverse=True)
    return [unique[key][1] for key in ordered[keep:]
            if key != current_run_id and unique[key][1]["status"] == "completed"]


class GitHub:
    def __init__(self, token, repository):
        if repository != "Ling-LA/IwaraFlow":
            raise ValueError("Cleanup is restricted to Ling-LA/IwaraFlow")
        self.base = f"https://api.github.com/repos/{repository}/actions/"
        self.headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "IwaraFlow-actions-cleanup",
        }

    def request(self, path, method="GET"):
        for attempt in range(4):
            try:
                request = urllib.request.Request(self.base + path, headers=self.headers, method=method)
                with urllib.request.urlopen(request, timeout=30) as response:
                    body = response.read()
                    return json.loads(body) if body else None
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return None
                if error.code not in (429, 500, 502, 503, 504) or attempt == 3:
                    raise RuntimeError(f"GitHub {method} {path}: HTTP {error.code}") from None
                delay = min(60, max(2 ** attempt, int(error.headers.get("Retry-After", "0"))))
            except (urllib.error.URLError, TimeoutError):
                if attempt == 3:
                    raise RuntimeError(f"GitHub {method} {path}: connection failed") from None
                delay = 2 ** attempt
            time.sleep(delay)

    def list_runs(self):
        runs = []
        page = 1
        while True:
            result = self.request(f"runs?per_page=100&page={page}")
            batch = result["workflow_runs"]  # Invalid/incomplete responses must abort cleanup.
            runs.extend(batch)
            if len(batch) < 100:
                return runs
            page += 1


def cleanup(api, current_run_id, dry_run, log=print, pause=time.sleep):
    workflow = api.request(f"workflows/{CLEANUP_WORKFLOW}")
    cleanup_path = f".github/workflows/{CLEANUP_WORKFLOW}"
    if not workflow or workflow["path"] != cleanup_path:
        raise ValueError("Unexpected or missing cleanup workflow")
    cleanup_id = int(workflow["id"])
    all_runs = api.list_runs()
    # Only these two explicitly approved build workflows share the 50-run limit.
    # Other workflows are ignored, including any added in the future.
    builds = [run for run in all_runs if run.get("path") in BUILD_PATHS]
    maintenance = [run for run in all_runs if run.get("path") == cleanup_path]
    if any(int(run["workflow_id"]) != cleanup_id for run in maintenance):
        raise ValueError("Cleanup workflow scope mismatch")
    if any(int(run["workflow_id"]) <= 0 for run in builds):
        raise ValueError("Invalid build workflow ID")
    plans = []
    # Validate both complete snapshots before any destructive requests.
    for label, keep, runs in [("APK/promo builds", 50, builds), (CLEANUP_WORKFLOW, 1, maintenance)]:
        old = select_old_runs(runs, keep, current_run_id)
        plans.append((label, old))
        log(f"{label}: {len(runs)} runs, keep newest {keep}, {len(old)} old completed runs")

    deleted = 0
    for label, old in plans:
        for run in old:
            run_id = int(run["id"])
            workflow_id = int(run["workflow_id"])
            if dry_run:
                log(f"Would delete {label} run {run_id} ({run['created_at']})")
                continue
            # A previously completed run might have been re-run since the snapshot.
            fresh = api.request(f"runs/{run_id}")
            if fresh is None:
                continue
            if (int(fresh["id"]) != run_id or int(fresh["workflow_id"]) != workflow_id
                    or fresh.get("path") != run["path"]):
                raise ValueError("Run scope changed; refusing deletion")
            if fresh["status"] != "completed":
                log(f"Skipped active run {run_id}; retry at next cleanup")
                continue
            api.request(f"runs/{run_id}", method="DELETE")
            deleted += 1
            log(f"Deleted {label} run {run_id}")
            pause(1)  # Space mutating requests to avoid secondary rate limits.
    return deleted


def main():
    api = GitHub(os.environ["GH_TOKEN"], os.environ["GITHUB_REPOSITORY"])
    dry_run = os.environ.get("DRY_RUN", "true").lower() == "true"
    deleted = cleanup(api, int(os.environ["GITHUB_RUN_ID"]), dry_run)
    summary = f"Cleanup finished: dry_run={dry_run}, deleted={deleted}. APK/promo builds retained: newest 50; active runs deferred.\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)


if __name__ == "__main__":
    main()
