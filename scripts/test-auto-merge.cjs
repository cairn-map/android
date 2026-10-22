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
  for (const suite of ['Python', 'Node']) {
    assert.match(checks, new RegExp(`${suite} automation tests\\n\\s+if: steps\\.automation\\.outputs\\.required != 'false'`));
  }
  assert.match(checks, /assembleDebug testDebugUnitTest lintDebug/);
  assert.match(checks, /bash scripts\/lint-workflows\.sh/);
});

test('every PR compares virtual performance before recording reusable validation', () => {
  const checks = workflow('checks.yml');
  assert.match(checks, /name: Compare virtual power performance\n\s+id: performance\n\s+timeout-minutes:/);
  assert.match(checks, /PERFORMANCE_PREVIOUS_SHA: \$\{\{ github\.event\.pull_request\.base\.sha \}\}/);
  assert.match(checks, /PERFORMANCE_CURRENT_SHA: \$\{\{ github\.event\.pull_request\.head\.sha \}\}/);
  assert.match(checks, /python3 scripts\/virtual_power\.py/);
  assert.ok(checks.indexOf('Compare virtual power performance') < checks.indexOf('Record the validated source'));
  assert.match(checks, /always\(\) && steps\.performance\.outcome != 'skipped'/);
  assert.match(checks, /head\.repo\.full_name == github\.repository/);
  assert.doesNotMatch(checks, /continue-on-error|pull_request_target/);
});

test('native merge pushes to main and starts signed release normally', () => {
  const release = workflow('release.yml');
  assert.match(release, /push:\s*\n\s*branches: \[main\]/);
  assert.match(release, /Build and verify the signed release/);
  assert.match(release, /Publish APK and update manifest together/);
  assert.match(release, /inputs\.validation_run_id/);
  assert.match(release, /--source-commit "\$SOURCE_COMMIT"/);
  assert.match(release, /AUTOMATION_BASE_SHA: \$\{\{ github\.event\.before \}\}/);
  for (const suite of ['Python', 'Node']) {
    assert.match(release, new RegExp(`${suite} automation tests \\(fallback\\)\\n\\s+if: [^\\n]*steps\\.automation\\.outputs\\.required != 'false'`));
  }
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
