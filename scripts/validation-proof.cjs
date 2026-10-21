const { execFileSync, spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const fs = require('node:fs');

function runtime() {
  const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
  if (java.error || java.status !== 0) throw new Error('Cannot identify the Java runtime');
  return {
    platform: process.platform,
    arch: process.arch,
    image: process.env.ImageOS || '',
    imageVersion: process.env.ImageVersion || '',
    java: createHash('sha256').update(java.stderr.trim()).digest('hex')
  };
}

function createProof({ env = process.env, toolchain = runtime(),
    git = ref => execFileSync('git', ['rev-parse', ref], { encoding: 'utf8' }).trim() } = {}) {
  const commit = git('HEAD'), tree = git('HEAD^{tree}');
  const sha = value => typeof value === 'string' && /^[0-9a-f]{40}$/.test(value);
  const runId = Number(env.GITHUB_RUN_ID), attempt = Number(env.GITHUB_RUN_ATTEMPT);
  if (!sha(commit) || !sha(tree) || !sha(env.VALIDATED_HEAD_SHA) || env.GITHUB_SHA !== commit
      || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(env.GITHUB_REPOSITORY || '')
      || !Number.isSafeInteger(runId) || runId < 1 || !Number.isSafeInteger(attempt) || attempt < 1)
    throw new Error('Cannot bind validation evidence to this PR checkout and workflow attempt');
  return {
    schema: 1,
    repository: env.GITHUB_REPOSITORY,
    runId,
    attempt,
    head: env.VALIDATED_HEAD_SHA,
    commit,
    tree,
    runtime: toolchain
  };
}

if (require.main === module) {
  fs.writeFileSync(process.argv[2], JSON.stringify(createProof()) + '\n');
}
module.exports = { runtime, createProof };
