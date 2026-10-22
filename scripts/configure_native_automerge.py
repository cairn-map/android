#!/usr/bin/env python3
"""Configure native auto-merge and protect Cairn's main branch (requires gh admin auth)."""

import argparse
import json
import subprocess
import sys

REPOSITORY = "dayblox/cairn"
RULESET_NAME = "main"
REQUIRED_CHECK = "checks"
# Repository-wide: no squash/rebase, so native auto-merge always creates
# a two-parent commit suitable for release validation reuse.
MERGE_SETTINGS = {
    "allow_merge_commit": True,
    "allow_squash_merge": False,
    "allow_rebase_merge": False,
    "allow_auto_merge": True,
    "delete_branch_on_merge": True,
}


def api(method, endpoint, payload=None):
    command = ["gh", "api", "--method", method, endpoint]
    if payload is not None:
        command.extend(["--input", "-"])
    result = subprocess.run(
        command, input=json.dumps(payload) if payload is not None else None,
        text=True, capture_output=True, check=True,
    )
    return json.loads(result.stdout)


def updated_ruleset(original):
    """Preserve other rules, but require checks on current main before merging."""
    if original["name"] != RULESET_NAME or original["target"] != "branch":
        raise ValueError("Unexpected ruleset; refusing to change it")
    refs = original["conditions"]["ref_name"]["include"]
    if "refs/heads/main" not in refs:
        raise ValueError("The selected ruleset does not protect main")
    rules = json.loads(json.dumps(original["rules"]))
    required = next((rule for rule in rules if rule["type"] == "required_status_checks"), None)
    if required is None:
        required = {"type": "required_status_checks", "parameters": {}}
        rules.append(required)
    params = required["parameters"]
    params["strict_required_status_checks_policy"] = True
    statuses = params.setdefault("required_status_checks", [])
    if not any(check.get("context") == REQUIRED_CHECK for check in statuses):
        statuses.append({"context": REQUIRED_CHECK})
    # PRs are still automatically mergeable with zero required human approvals.
    # Review discussions must be resolved before any merge.
    pr = next((rule for rule in rules if rule["type"] == "pull_request"), None)
    if pr is None:
        raise ValueError("Expected existing pull-request rule")
    pr["parameters"]["required_review_thread_resolution"] = True
    pr["parameters"]["required_approving_review_count"] = 0
    # This is a single-maintainer repository: the PR author cannot self-approve.
    # Never require code-owner or human approval unless a second trusted reviewer exists.
    pr["parameters"]["require_code_owner_review"] = False
    pr["parameters"]["dismiss_stale_reviews_on_push"] = False
    pr["parameters"]["require_last_push_approval"] = False
    pr["parameters"]["allowed_merge_methods"] = ["merge"]
    return {
        "name": original["name"],
        "target": original["target"],
        "enforcement": "active",
        "conditions": original["conditions"],
        "rules": rules,
        "bypass_actors": [],  # Enforce checks for admins as well as ordinary contributors.
    }


def configure(repository, dry_run=False):
    info = api("GET", f"repos/{repository}")
    if info["full_name"].lower() != repository.lower() or info["default_branch"] != "main":
        raise ValueError("Unexpected repository or default branch; refusing to update")
    rulesets = api("GET", f"repos/{repository}/rulesets")
    matches = [rule for rule in rulesets if rule["name"] == RULESET_NAME
               and rule["target"] == "branch" and rule["enforcement"] == "active"]
    if len(matches) != 1:
        raise ValueError("Expected one active main ruleset; review repository settings")
    rule_id = matches[0]["id"]
    original = api("GET", f"repos/{repository}/rulesets/{rule_id}")
    updated = updated_ruleset(original)
    print(f"Repository: {repository}")
    print(f"Ruleset: {RULESET_NAME} ({rule_id}); required: {REQUIRED_CHECK}; strict: yes")
    print("No bypass; solo maintainer: zero required approvals and no code-owner gate")
    print("Allow merge commits ONLY; disable squash/rebase; keep auto-merge and branch deletion")
    if dry_run:
        print("Dry run: no settings changed")
        return
    api("PUT", f"repos/{repository}/rulesets/{rule_id}", updated)
    api("PATCH", f"repos/{repository}", MERGE_SETTINGS)
    rule = api("GET", f"repos/{repository}/rulesets/{rule_id}")
    after = api("GET", f"repos/{repository}")
    statuses = next(r["parameters"] for r in rule["rules"]
                    if r["type"] == "required_status_checks")
    assert statuses["strict_required_status_checks_policy"]
    assert any(c["context"] == REQUIRED_CHECK
               for c in statuses["required_status_checks"])
    assert not rule["bypass_actors"]
    live_pr = next(r["parameters"] for r in rule["rules"] if r["type"] == "pull_request")
    assert live_pr["required_approving_review_count"] == 0
    assert live_pr["require_code_owner_review"] is False
    assert live_pr["require_last_push_approval"] is False
    assert live_pr["allowed_merge_methods"] == ["merge"]
    if any(after.get(key) is not expected for key, expected in MERGE_SETTINGS.items()):
        raise RuntimeError("GitHub did not retain merge-commit-only repository settings")
    print("Verified merge-commit-only settings. Native auto-merge must also be enabled on each PR.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=REPOSITORY)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        configure(args.repository, args.dry_run)
    except (subprocess.CalledProcessError, ValueError, KeyError, RuntimeError) as error:
        print(f"Cannot configure GitHub settings: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
