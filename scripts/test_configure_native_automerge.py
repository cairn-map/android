import copy
import unittest

from configure_native_automerge import MERGE_SETTINGS, updated_ruleset


class NativeAutoMergeRulesetTest(unittest.TestCase):
    def test_only_merge_commits_are_configured(self):
        self.assertEqual(MERGE_SETTINGS, {
            "allow_merge_commit": True,
            "allow_squash_merge": False,
            "allow_rebase_merge": False,
            "allow_auto_merge": True,
            "delete_branch_on_merge": True,
        })

    def fixture(self):
        return {
            "name": "main",
            "target": "branch",
            "enforcement": "active",
            "conditions": {"ref_name": {"include": ["refs/heads/main"], "exclude": []}},
            "bypass_actors": [{"actor_id": 5, "actor_type": "RepositoryRole",
                               "bypass_mode": "always"}],
            "rules": [
                {"type": "required_status_checks", "parameters": {
                    "strict_required_status_checks_policy": False,
                    "required_status_checks": [{"context": "another-check"}],
                }},
                {"type": "pull_request", "parameters": {
                    "required_approving_review_count": 0,
                    "required_review_thread_resolution": False,
                    "require_code_owner_review": True,
                    "dismiss_stale_reviews_on_push": True,
                    "require_last_push_approval": True,
                    "allowed_merge_methods": ["merge", "squash", "rebase"],
                }},
            ],
        }

    def test_requires_real_checks_and_resolved_conversations(self):
        original = self.fixture()
        snapshot = copy.deepcopy(original)
        result = updated_ruleset(original)
        self.assertEqual(original, snapshot)
        status = result["rules"][0]["parameters"]
        self.assertTrue(status["strict_required_status_checks_policy"])
        self.assertEqual([{"context": "another-check"}, {"context": "checks"}],
                         status["required_status_checks"])
        self.assertTrue(result["rules"][1]["parameters"]["required_review_thread_resolution"])
        self.assertEqual(0, result["rules"][1]["parameters"]["required_approving_review_count"])
        self.assertFalse(result["rules"][1]["parameters"]["require_code_owner_review"])
        self.assertFalse(result["rules"][1]["parameters"]["dismiss_stale_reviews_on_push"])
        self.assertFalse(result["rules"][1]["parameters"]["require_last_push_approval"])
        self.assertEqual(["merge"], result["rules"][1]["parameters"]["allowed_merge_methods"])
        self.assertEqual([], result["bypass_actors"])

    def test_configuration_is_idempotent(self):
        once = updated_ruleset(self.fixture())
        twice = updated_ruleset(once)
        self.assertEqual(once, twice)
        self.assertEqual(1, sum(
            item["context"] == "checks"
            for item in twice["rules"][0]["parameters"]["required_status_checks"]))

    def test_rejects_wrong_target_or_branch(self):
        ruleset = self.fixture()
        ruleset["conditions"]["ref_name"]["include"] = ["refs/heads/develop"]
        with self.assertRaises(ValueError):
            updated_ruleset(ruleset)
        ruleset = self.fixture()
        ruleset["name"] = "another-ruleset"
        with self.assertRaises(ValueError):
            updated_ruleset(ruleset)

    def test_requires_pull_request_rule(self):
        ruleset = self.fixture()
        ruleset["rules"] = ruleset["rules"][:1]
        with self.assertRaises(ValueError):
            updated_ruleset(ruleset)


if __name__ == "__main__":
    unittest.main()
