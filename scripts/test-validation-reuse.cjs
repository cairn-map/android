const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { reuseValidation, readProof } = require('./reuse-validation.cjs');
const { createProof } = require('./validation-proof.cjs');

const BASE = 'a'.repeat(40), HEAD = 'b'.repeat(40), MERGE = 'c'.repeat(40), TESTED = 'd'.repeat(40), TREE = 'e'.repeat(40);
const environment = { platform: 'linux', arch: 'x64', image: 'ubuntu24', imageVersion: '20261018', java: 'java17' };

async function scenario(change = () => {}) {
  const run = { id: 123, workflow_id: 9, event: 'pull_request', status: 'completed', conclusion: 'success',
    run_attempt: 2, head_sha: HEAD, repository: { full_name: 'owner/cairn' } };
  const proof = { schema: 1, repository: 'owner/cairn', runId: 123, attempt: 2, head: HEAD,
    commit: TESTED, tree: TREE, runtime: { ...environment } };
  const merged = { parents: [{ sha: BASE }, { sha: HEAD }], tree: { sha: TREE } };
  const tested = { parents: [{ sha: BASE }, { sha: HEAD }], tree: { sha: TREE } };
  const artifact = { id: 456, name: 'validated-source-2', expired: false, size_in_bytes: 500 };
  const options = { run, proof, merged, tested, artifacts: [artifact], runs: [run], changed: false,
    runId: '123', loads: 0, queries: 0, discoveries: 0, latest: null, failure: false };
  change(options);
  const github = { paginate: async () => options.artifacts, rest: {
    actions: {
      getWorkflow: async () => ({ data: { id: 9 } }),
      listWorkflowRunsForWorkflow: async () => {
        options.discoveries++;
        if (options.discoveryFailure) throw new Error('Cannot list workflow runs');
        return { data: { workflow_runs: options.runs } };
      },
      getWorkflowRun: async () => ({ data: ++options.queries > 1 && options.latest ? options.latest : run }),
      listWorkflowRunArtifacts: () => {}
    },
    git: { getCommit: async ({ commit_sha }) => {
      if (options.failure) throw new Error('API unavailable');
      return { data: commit_sha === MERGE ? merged : tested };
    }}
  }};
  const result = await reuseValidation({ github, owner: 'owner', repo: 'cairn', source: MERGE,
    runId: options.runId, environment, changed: () => options.changed,
    loadProof: async () => { options.loads++; return options.proof; } });
  return { ...result, options };
}

test('reuses a successful current PR attempt for the identical merge tree and toolchain', async () => {
  const result = await scenario();
  assert.equal(result.reused, true);
  assert.equal(result.options.loads, 1);
  assert.equal(result.options.queries, 2);
  assert.equal(result.options.discoveries, 0);
});

test('native GitHub merge discovers and verifies the completed PR check', async () => {
  const result = await scenario(x => { x.runId = ''; });
  assert.equal(result.reused, true);
  assert.equal(result.options.discoveries, 1);
  assert.equal(result.options.loads, 1);
});

const cases = {
  'no discoverable PR run': x => { x.runId = ''; x.runs = []; },
  'ambiguous PR runs': x => { x.runId = ''; x.runs.push({ ...x.run, id: 124 }); },
  'discovered PR check has failed': x => { x.runId = ''; x.runs[0] = { ...x.run, conclusion: 'failure' }; },
  'discovered PR check has wrong head': x => { x.runId = ''; x.runs[0] = { ...x.run, head_sha: BASE }; },
  'discovery API unavailable': x => { x.runId = ''; x.discoveryFailure = true; },
  'invalid run ID': x => { x.runId = '123; echo bad'; },
  'direct push or squash merge': x => { x.merged.parents.pop(); },
  'CI configuration changed': x => { x.changed = true; },
  'main changed before merging': x => { x.merged.tree.sha = 'f'.repeat(40); },
  'failed check': x => { x.run.conclusion = 'failure'; },
  'incomplete check': x => { x.run.status = 'in_progress'; },
  'another workflow': x => { x.run.workflow_id = 10; },
  'push check instead of PR check': x => { x.run.event = 'push'; },
  'another repository': x => { x.run.repository.full_name = 'other/cairn'; },
  'a different PR head': x => { x.run.head_sha = BASE; },
  'older attempt': x => { x.proof.attempt = 1; },
  'wrong run': x => { x.proof.runId = 122; },
  'forged tree': x => { x.tested.tree.sha = BASE; },
  'checkout of head without its base': x => { x.tested.parents.pop(); },
  'missing artifact': x => { x.artifacts = []; },
  'expired artifact': x => { x.artifacts[0].expired = true; },
  'ambiguous artifacts': x => { x.artifacts.push({ ...x.artifacts[0] }); },
  'oversized artifact': x => { x.artifacts[0].size_in_bytes = 65537; },
  'malformed JSON shape': x => { x.proof = null; },
  'different Java runtime': x => { x.proof.runtime.java = 'java21'; },
  'different runner image': x => { x.proof.runtime.imageVersion = 'new-image'; },
  'API unavailable': x => { x.failure = true; },
  'check rerun started while downloading': x => { x.latest = { ...x.run, status: 'in_progress', run_attempt: 3 }; }
};
for (const [name, change] of Object.entries(cases)) {
  test(`runs full validation when ${name}`, async () => assert.equal((await scenario(change)).reused, false));
}

test('configuration changes do not download any artifact', async () => {
  const result = await scenario(x => { x.changed = true; });
  assert.equal(result.options.loads, 0);
});

test('reads only bounded JSON from the downloaded archive', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'cairn-proof-test-'));
  try {
    fs.writeFileSync(path.join(directory, 'validation.json'), JSON.stringify({ schema: 1 }));
    execFileSync('zip', ['-q', 'proof.zip', 'validation.json'], { cwd: directory });
    const data = fs.readFileSync(path.join(directory, 'proof.zip'));
    const github = { rest: { actions: { downloadArtifact: async () => ({ data }) } } };
    assert.deepEqual(await readProof(github, { owner: 'owner', repo: 'cairn' }, { id: 456 }), { schema: 1 });
    fs.writeFileSync(path.join(directory, 'validation.json'), 'x'.repeat(20000));
    execFileSync('zip', ['-q', 'proof.zip', 'validation.json'], { cwd: directory });
    github.rest.actions.downloadArtifact = async () => ({ data: fs.readFileSync(path.join(directory, 'proof.zip')) });
    await assert.rejects(readProof(github, { owner: 'owner', repo: 'cairn' }, { id: 456 }));
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});


test('binds the evidence to the actual checked-out merge and workflow attempt', () => {
  const env = { GITHUB_REPOSITORY: 'owner/cairn', GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2',
    VALIDATED_HEAD_SHA: HEAD, GITHUB_SHA: TESTED };
  const options = { env, toolchain: environment, git: ref => ref === 'HEAD' ? TESTED : TREE };
  const proof = createProof(options);
  assert.equal(proof.commit, TESTED);
  assert.equal(proof.tree, TREE);
  assert.equal(proof.runId, 123);
  assert.equal(proof.attempt, 2);
  assert.deepEqual(proof.runtime, environment);
  assert.throws(() => createProof({ ...options, env: { ...env, GITHUB_SHA: HEAD } }));
  assert.throws(() => createProof({ ...options, env: { ...env, GITHUB_RUN_ATTEMPT: '' } }));
});
