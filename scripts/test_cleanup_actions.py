import unittest
from unittest.mock import patch

from cleanup_actions import GitHub, cleanup, select_old_runs


def run(run_id, workflow_id=1, status="completed"):
    filename = {1: "build-apk.yml", 2: "cleanup-actions.yml", 3: "record-promo.yml", 4: "unrelated.yml"}[workflow_id]
    return {"id": run_id, "workflow_id": workflow_id, "status": status,
            "path": f".github/workflows/{filename}",
            "created_at": "2026-10-22T00:00:00Z"}


class FakeAPI:
    def __init__(self, count=51):
        self.builds = [run(i) for i in range(1, count + 1)]
        self.maintenance = [run(1001, 2), run(1002, 2, "in_progress")]
        self.deleted = []
        self.refreshed = {}

    def list_runs(self):
        return self.builds + self.maintenance

    def request(self, path, method="GET"):
        if method == "DELETE":
            self.deleted.append(int(path.split("/")[1]))
            return None
        if path.startswith("workflows/"):
            filename = path.split("/")[1]
            return {"id": 1 if filename == "build-apk.yml" else 2,
                    "path": f".github/workflows/{filename}"}
        run_id = int(path.split("/")[1])
        if run_id in self.refreshed:
            return self.refreshed[run_id]
        return next(item for item in self.builds + self.maintenance if item["id"] == run_id)


class RetentionTests(unittest.TestCase):
    def clean(self, api, dry_run=False):
        return cleanup(api, 1002, dry_run, log=lambda _: None, pause=lambda _: None)

    def test_fifty_or_fewer_builds_are_preserved(self):
        for count in (0, 1, 49, 50):
            api = FakeAPI(count)
            self.clean(api)
            self.assertEqual([1001], api.deleted)

    def test_only_excess_builds_and_old_maintenance_are_deleted(self):
        api = FakeAPI(151)
        self.assertEqual(102, self.clean(api))
        self.assertEqual(list(range(101, 0, -1)) + [1001], api.deleted)

    def test_creation_time_wins_over_run_id_and_duplicates(self):
        older, newer = run(20), run(10)
        older["created_at"] = "2026-10-21T00:00:00Z"
        self.assertEqual([older], select_old_runs([newer, older, newer], 1, 0))

    def test_active_old_run_is_deferred_without_deleting_a_newer_run(self):
        api = FakeAPI(51)
        api.builds[0]["status"] = "in_progress"
        self.clean(api)
        self.assertEqual([1001], api.deleted)

    def test_rerun_started_after_listing_is_preserved(self):
        api = FakeAPI()
        api.refreshed[1] = run(1, status="queued")
        self.clean(api)
        self.assertEqual([1001], api.deleted)

    def test_current_cleanup_is_protected_even_when_not_newest(self):
        api = FakeAPI()
        api.maintenance = [run(1002, 2), run(1003, 2)]
        self.clean(api)
        self.assertEqual([1], api.deleted)

    def test_already_removed_run_is_harmless(self):
        api = FakeAPI()
        api.refreshed[1] = None
        self.clean(api)
        self.assertEqual([1001], api.deleted)

    def test_dry_run_never_deletes(self):
        api = FakeAPI()
        self.assertEqual(0, self.clean(api, dry_run=True))
        self.assertEqual([], api.deleted)

    def test_incomplete_listing_aborts_before_any_deletion(self):
        api = FakeAPI()
        api.maintenance = [{"id": 1001, "workflow_id": 2, "path": ".github/workflows/cleanup-actions.yml"}]
        with self.assertRaises(KeyError):
            self.clean(api)
        self.assertEqual([], api.deleted)

    def test_invalid_workflow_id_aborts_before_any_deletion(self):
        api = FakeAPI()
        api.builds[0]["workflow_id"] = 0
        with self.assertRaises(ValueError):
            self.clean(api)
        self.assertEqual([], api.deleted)

    def test_promo_builds_share_the_fifty_run_limit(self):
        api = FakeAPI(49)
        api.builds.extend([run(50, 3), run(51, 3)])
        self.clean(api)
        self.assertEqual([1, 1001], api.deleted)

    def test_unrelated_workflows_are_neither_counted_nor_deleted(self):
        api = FakeAPI(50)
        api.builds.extend([run(0, 4), run(900, 4)])
        self.clean(api)
        self.assertEqual([1001], api.deleted)

    def test_changed_workflow_path_cannot_be_deleted(self):
        api = FakeAPI()
        api.refreshed[1] = dict(run(1), path=".github/workflows/unrelated.yml")
        with self.assertRaises(ValueError):
            self.clean(api)
        self.assertEqual([], api.deleted)

    def test_fresh_scope_mismatch_aborts_before_any_deletion(self):
        api = FakeAPI()
        api.refreshed[1] = run(1, workflow_id=3)
        with self.assertRaises(ValueError):
            self.clean(api)
        self.assertEqual([], api.deleted)

    def test_pagination_collects_every_page(self):
        api = GitHub("test", "Ling-LA/IwaraFlow")
        with patch.object(api, "request", side_effect=[
            {"workflow_runs": [run(i) for i in range(100)]},
            {"workflow_runs": [run(100)]},
        ]) as request:
            self.assertEqual(101, len(api.list_runs()))
        self.assertEqual("runs?per_page=100&page=2", request.call_args.args[0])

    def test_other_repositories_are_rejected(self):
        with self.assertRaises(ValueError):
            GitHub("test", "someone/another-repo")


if __name__ == "__main__":
    unittest.main()
