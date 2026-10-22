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
  assert.match(checks, /node --test scripts\/test-\*\.cjs/);
  assert.match(checks, /assembleDebug testDebugUnitTest lintDebug/);
});

test('native merge pushes to main and starts signed release normally', () => {
  const release = workflow('release.yml');
  assert.match(release, /push:\s*\n\s*branches: \[main\]/);
  assert.match(release, /Build and verify the signed release/);
  assert.match(release, /Publish APK and update manifest together/);
  assert.match(release, /inputs\.validation_run_id/);
});

test('administrator configuration requires strict checks and enables native auto-merge', () => {
  const settings = fs.readFileSync(path.join(root, 'scripts/configure_native_automerge.py'), 'utf8');
  assert.match(settings, /REQUIRED_CHECK = "checks"/);
  assert.match(settings, /strict_required_status_checks_policy/);
  assert.match(settings, /"allow_auto_merge": True/);
  assert.match(settings, /"delete_branch_on_merge": True/);
});
