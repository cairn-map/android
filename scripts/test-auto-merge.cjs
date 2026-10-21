const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const workflow = fs.readFileSync(path.join(__dirname, '../.github/workflows/auto-merge.yml'), 'utf8');
const source = workflow.split('          script: |\n')[1].split('\n').map(line => line.slice(12)).join('\n');
const runWorkflow = new (Object.getPrototypeOf(async () => {}).constructor)('github', 'context', 'core', source);

async function scenario({ status = 'completed', conclusion = 'success', attempt = 1,
    head = 'checked', draft = false, state = 'open', base = 'main', branchHead = 'checked',
    fork = false, dispatchError = false } = {}) {
  const calls = [];
  const repository = { default_branch: 'main', full_name: 'owner/cairn' };
  const github = { paginate: async () => [{ number: 3 }], rest: {
    actions: {
      getWorkflowRun: async () => ({ data: { status, conclusion, run_attempt: attempt, head_sha: 'checked' } }),
      createWorkflowDispatch: async request => { calls.push(['dispatch', request]); if (dispatchError) throw Error('dispatch failed'); }
    },
    repos: { get: async () => ({ data: repository }), listPullRequestsAssociatedWithCommit: () => {} },
    pulls: {
      get: async () => ({ data: { number: 3, state, draft, base: { ref: base }, head: {
        sha: head, ref: 'feature', repo: { full_name: fork ? 'fork/cairn' : 'owner/cairn' } } } }),
      merge: async request => { calls.push(['merge', request]); return { data: { merged: true, sha: 'merged' } }; }
    },
    git: {
      getRef: async () => ({ data: { object: { sha: branchHead } } }),
      deleteRef: async request => calls.push(['delete', request])
    }
  }};
  const context = { repo: { owner: 'owner', repo: 'cairn' }, payload: { workflow_run: { id: 1, run_attempt: 1 } } };
  await runWorkflow(github, context, { info: () => {}, warning: () => {} });
  return calls;
}

test('merges only the checked SHA, dispatches its merge commit, then removes the feature branch', async () => {
  const calls = await scenario();
  assert.deepEqual(calls.map(call => call[0]), ['merge', 'dispatch', 'delete']);
  assert.equal(calls[0][1].sha, 'checked');
  assert.deepEqual(calls[1][1].inputs, { source_commit: 'merged' });
  assert.equal(calls[1][1].ref, 'main');
  assert.equal(calls[2][1].ref, 'heads/feature');
});

for (const options of [{ conclusion: 'failure' }, { status: 'in_progress' }, { attempt: 2 },
    { head: 'changed' }, { draft: true }, { state: 'closed' }, { base: 'other' }]) {
  test(`does not merge or release ${JSON.stringify(options)}`, async () => assert.deepEqual(await scenario(options), []));
}

test('preserves a feature branch that advanced after the merge', async () => {
  assert.deepEqual((await scenario({ branchHead: 'new' })).map(call => call[0]), ['merge', 'dispatch']);
});

test('does not delete a fork branch', async () => {
  assert.deepEqual((await scenario({ fork: true })).map(call => call[0]), ['merge', 'dispatch']);
});

test('reports a release dispatch failure instead of silently claiming success', async () => {
  await assert.rejects(scenario({ dispatchError: true }), /dispatch failed/);
});
