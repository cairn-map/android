const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { runtime } = require('./validation-proof.cjs');

const sha = value => typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
const artifactName = attempt => `validated-source-${attempt}`;
const fallback = reason => ({ reused: false, reason });

function sameRuntime(proof, current) {
  const fields = ['platform', 'arch', 'image', 'imageVersion', 'java'];
  return proof && fields.every(field => typeof proof[field] === 'string' && proof[field] === current[field]);
}

function configurationChanged(base, source) {
  return execFileSync('git', ['diff', '--name-only', base, source, '--', '.github', 'scripts'],
    { encoding: 'utf8' }).trim() !== '';
}

async function readProof(github, repository, artifact) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'cairn-proof-'));
  try {
    const { data } = await github.rest.actions.downloadArtifact({ ...repository,
      artifact_id: artifact.id, archive_format: 'zip' });
    const archive = path.join(directory, 'proof.zip');
    const bytes = Buffer.from(data);
    if (bytes.length > 65536) throw new Error('Validation evidence is too large');
    fs.writeFileSync(archive, bytes, { mode: 0o600 });
    // Read only this bounded JSON entry. Never extract or execute artifact contents.
    return JSON.parse(execFileSync('unzip', ['-p', archive, 'validation.json'],
      { encoding: 'utf8', maxBuffer: 16384, timeout: 5000 }));
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

async function reuseValidation({ github, owner, repo, source, runId,
    environment = runtime(), changed = configurationChanged, loadProof = readProof }) {
  if (!sha(source) || (runId && !/^[1-9][0-9]*$/.test(String(runId))))
    return fallback('Invalid source SHA or PR validation run ID');
  const repository = { owner, repo };
  let stage = 'merged commit';
  try {
    const { data: merged } = await github.rest.git.getCommit({ ...repository, commit_sha: source });
    // Only preserve PR validation across a genuine two-parent merge commit.
    // Single-parent squash/rebase/direct pushes use full validation.
    if (merged.parents.length !== 2 || !sha(merged.parents[0].sha)
        || !sha(merged.parents[1].sha))
      return fallback('The release source is not a two-parent merge');
    const head = merged.parents[1].sha;
    stage = 'configuration comparison';
    if (changed(merged.parents[0].sha, source))
      return fallback('CI or validation scripts changed in this merge');
    stage = 'checks workflow';
    const { data: workflow } = await github.rest.actions.getWorkflow({ ...repository, workflow_id: 'checks.yml' });
    let resolvedId = String(runId || '');
    if (!resolvedId) {
      // Native auto-merge generates a main push without workflow_dispatch inputs.
      // Query the second parent (PR head), then validate the exact merge tree.
      stage = 'PR run discovery';
      const { data } = await github.rest.actions.listWorkflowRuns({
        ...repository, workflow_id: workflow.id, event: 'pull_request',
        head_sha: head, per_page: 100
      });
      const candidates = (data.workflow_runs || []).filter(candidate =>
        candidate.workflow_id === workflow.id && candidate.event === 'pull_request'
        && candidate.head_sha === head && candidate.status === 'completed'
        && candidate.conclusion === 'success' && candidate.repository?.full_name === `${owner}/${repo}`
        && Number.isSafeInteger(candidate.id) && candidate.id > 0);
      if (candidates.length !== 1)
        return fallback('No unique successful PR validation run matches the merged head');
      resolvedId = String(candidates[0].id);
    }
    stage = 'PR run';
    const { data: run } = await github.rest.actions.getWorkflowRun({ ...repository, run_id: Number(resolvedId) });
    if (run.id !== Number(resolvedId) || run.workflow_id !== workflow.id || run.event !== 'pull_request'
        || run.status !== 'completed' || run.conclusion !== 'success'
        || run.repository?.full_name !== `${owner}/${repo}` || run.head_sha !== head
        || !Number.isInteger(run.run_attempt) || run.run_attempt < 1)
      return fallback('The latest PR check attempt does not validate this merged head');
    stage = 'artifact listing';
    const artifacts = await github.paginate(github.rest.actions.listWorkflowRunArtifacts,
      { ...repository, run_id: run.id });
    const matches = artifacts.filter(item => item.name === artifactName(run.run_attempt) && !item.expired);
    if (matches.length !== 1 || !Number.isInteger(matches[0].size_in_bytes)
        || matches[0].size_in_bytes < 1 || matches[0].size_in_bytes > 65536)
      return fallback('Current validation evidence is missing, expired or too large');
    stage = 'proof download and parsing';
    const proof = await loadProof(github, repository, matches[0]);
    if (proof.schema !== 1 || proof.repository !== `${owner}/${repo}` || proof.runId !== run.id
        || proof.attempt !== run.run_attempt || proof.head !== run.head_sha
        || !sha(proof.commit) || !sha(proof.tree) || proof.tree !== merged.tree.sha
        || !sameRuntime(proof.runtime, environment))
      return fallback('Tested source or toolchain differs from the release');
    stage = 'checked merge commit';
    const { data: tested } = await github.rest.git.getCommit({ ...repository, commit_sha: proof.commit });
    if (tested.tree.sha !== proof.tree || tested.parents.length !== 2
        || tested.parents[0].sha !== merged.parents[0].sha || tested.parents[1].sha !== run.head_sha)
      return fallback('The evidence does not describe the checked PR merge');
    // A rerun may have started while the evidence was being downloaded.
    stage = 'latest PR run attempt';
    const { data: latest } = await github.rest.actions.getWorkflowRun({ ...repository, run_id: run.id });
    if (latest.run_attempt !== run.run_attempt || latest.status !== 'completed' || latest.conclusion !== 'success')
      return fallback('The PR check was rerun while verifying its evidence');
    return { reused: true, reason: `Reusing successful PR validation run ${run.id} for identical source and toolchain` };
  } catch {
    return fallback(`Validation evidence could not be verified (${stage})`);
  }
}

module.exports = { reuseValidation, readProof, sameRuntime };
