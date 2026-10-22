const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.join(__dirname, '..');
const workflow = name => fs.readFileSync(path.join(root, '.github/workflows', name), 'utf8');

test('privileged merge workflow is replaced by native GitHub auto-merge', () => {
  assert.equal(fs.existsSync(path.join(root, '.github/workflows/auto-merge.yml')), false);
});

test('PR checks have a stable required status with build, test and lint', () => {
  const checks = workflow('checks.yml');
  assert.match(checks, /name: Android checks/);
  assert.match(checks, /on:\s*\n\s*pull_request:/);
  assert.match(checks, /jobs:\s*\n\s*checks:/);
  assert.match(checks, /python3 -m unittest discover/);
  assert.match(checks, /node --test "\$suite"/);
  assert.match(checks, /assembleDebug testDebugUnitTest lintDebug/);
  assert.match(checks, /bash scripts\/lint-workflows\.sh/);
});

test('native merge pushes to main and starts signed release normally', () => {
  const release = workflow('release.yml');
  assert.match(release, /push:\s*\n\s*branches: \[main\]/);
  assert.match(release, /Build and verify the signed release/);
  assert.match(release, /Publish APK and update manifest together/);
  assert.match(release, /inputs\.validation_run_id/);
  assert.match(release, /--source-commit "\$SOURCE_COMMIT"/);
});

test('release notes are generated from GitHub changes and updated on draft retries', () => {
  const release = workflow('release.yml');
  const prepare = fs.readFileSync(path.join(root, 'scripts/prepare-release.py'), 'utf8');
  assert.match(release, /releases\/generate-notes/);
  assert.match(release, /target_commitish="\$SOURCE_COMMIT"/);
  assert.match(release, /gh release create "\$TAG" --draft[^\n]*--notes-file dist\/release-notes\.md/);
  assert.match(release, /gh release edit "\$TAG" --notes-file dist\/release-notes\.md/);
  assert.match(release, /if \[ ! -s dist\/release-notes\.md \]/);
  assert.doesNotMatch(prepare, /release-notes\.md|Install this APK/);
});

test('administrator configuration requires strict checks and enables native auto-merge', () => {
  const settings = fs.readFileSync(path.join(root, 'scripts/configure_native_automerge.py'), 'utf8');
  assert.match(settings, /REQUIRED_CHECK = "checks"/);
  assert.match(settings, /strict_required_status_checks_policy/);
  assert.match(settings, /"allow_auto_merge": True/);
  assert.match(settings, /"delete_branch_on_merge": True/);
  assert.match(settings, /RULESET_NAME = "main"/);
  assert.match(settings, /"required_approving_review_count"\] = 0/);
  assert.match(settings, /"require_code_owner_review"\] = False/);
});
