import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import childProcess from 'node:child_process';
import { recordReviewBase, inspectCommitReview, inspectExistingCommitReview } from './commit-review.js';

const execFile = promisify(childProcess.execFile);
test('reviews actual commits and rejects uncommitted work, missing explanations and stale report links', async () => {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-review-test-'));
  const git = async (...args) => (await execFile('git', ['-C', workspace, '-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', ...args])).stdout.trim();
  try {
    await git('init');
    await writeFile(join(workspace, 'source.txt'), 'before\n');
    await git('add', 'source.txt'); await git('commit', '-m', 'Baseline');
    const base = await recordReviewBase(workspace);
    await writeFile(join(workspace, '.shoaku', 'RESULTS.md'), '# Outcome\n');
    assert.equal((await inspectCommitReview(workspace)).ready, true); // genuine no-change result
    await writeFile(join(workspace, 'source.txt'), 'after\n');
    assert.equal((await inspectCommitReview(workspace)).ready, false);
    await git('add', 'source.txt'); await git('commit', '-m', 'Implement behavior');
    const hash = await git('rev-parse', 'HEAD');
    const missing = await inspectCommitReview(workspace);
    assert.deepEqual(missing.commits, [{ hash, subject: 'Implement behavior' }]);
    assert.deepEqual(missing.missingDocs, [hash]);
    await mkdir(join(workspace, '.shoaku', 'commits'));
    await writeFile(join(workspace, '.shoaku', 'commits', `${hash}.md`), `# Implementation\n${hash}\n## Change behavior\n`);
    assert.equal((await inspectCommitReview(workspace)).ready, false);
    await writeFile(join(workspace, '.shoaku', 'RESULTS.md'), `# Outcome\n[Read](commits/${hash}.md)\n`);
    assert.equal((await inspectCommitReview(workspace)).ready, false); // prose alone has no correspondence
    await writeFile(join(workspace, '.shoaku', 'commits', `${hash}.review.json`), JSON.stringify({
      schemaVersion: 1, commit: hash, problem: 'Old behavior', before: 'before', after: 'after',
      steps: [{ id: 'behavior', title: 'Change behavior', why: 'Support the goal', change: 'Update source', verification: 'Not verified',
        anchors: [{ path: 'source.txt', side: 'after', startLine: 1, endLine: 1, label: 'Behavior' }], links: [] }], excludedFiles: []
    }));
    assert.equal((await inspectCommitReview(workspace)).ready, true);
    assert.equal(await recordReviewBase(workspace, { existing: true }), base);
    await git('commit', '--amend', '-m', 'Organized implementation');
    const rewritten = await inspectCommitReview(workspace);
    assert.equal(rewritten.ready, false);
    assert.notEqual(rewritten.commits[0].hash, hash);
    assert.deepEqual(rewritten.missingDocs, [rewritten.commits[0].hash]);
  } finally { await rm(workspace, { recursive: true, force: true }); }
});

test('reports a removed session workspace without recreating it', async () => {
  const root = await mkdtemp(join(tmpdir(), 'shoaku-missing-workspace-'));
  const missing = join(root, 'removed-session');
  try {
    await assert.rejects(inspectExistingCommitReview(missing), /Session workspace no longer exists/);
    await assert.rejects(readFile(join(missing, '.shoaku/base-revision')), { code: 'ENOENT' });
  } finally { await rm(root, { recursive: true, force: true }); }
});
