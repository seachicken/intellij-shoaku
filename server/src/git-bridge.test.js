import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import childProcess from 'node:child_process';
import { ensureExplorerBranch, processGitRequest, validateCommitRequest } from './git-bridge.js';
const execFile = promisify(childProcess.execFile);

test('rejects external paths, directory pathspecs, metadata and arbitrary Git operations', () => {
  for (const file of ['.', '../source', '/source', '.git/config', '.shoaku/git-response.json', 'sub/.git/config', ':(top)*']) {
    if (file === ':(top)*') continue; // Git uses literal pathspecs; this is only a literal file name.
    assert.throws(() => validateCommitRequest({ id: 'task-1', action: 'commit', files: [file], message: 'Implement' }));
  }
  assert.throws(() => validateCommitRequest({ id: 'task', action: 'reset', files: ['source'], message: 'reset' }));
});

test('commits only requested files on the session branch and acknowledges requests once', async () => {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-bridge-test-'));
  const git = async (...args) => (await execFile('git', ['-C', workspace, '-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', ...args])).stdout.trim();
  try {
    await git('init'); await git('config', 'commit.gpgsign', 'false'); await git('config', 'user.name', 'Test'); await git('config', 'user.email', 'test@example.invalid');
    await writeFile(join(workspace, 'source.txt'), 'before\n'); await git('add', 'source.txt'); await git('commit', '-m', 'Baseline');
    await git('checkout', '--detach'); await ensureExplorerBranch(workspace, 'shoaku/test');
    await mkdir(join(workspace, '.shoaku'));
    await writeFile(join(workspace, 'source.txt'), 'after\n');
    await writeFile(join(workspace, 'other.txt'), 'unrelated\n'); await git('add', 'other.txt');
    const requestPath = join(workspace, '.shoaku', 'git-request.json');
    await writeFile(requestPath, JSON.stringify({ id: 'task-1', action: 'commit', files: ['source.txt'], message: 'Implement behavior' }));
    const result = await processGitRequest(workspace, 'shoaku/test');
    assert.equal(result.status, 'complete');
    assert.equal(result.hash, await git('rev-parse', 'HEAD'));
    assert.equal(await git('show', '--format=', '--name-only', 'HEAD'), 'source.txt');
    assert.equal(await processGitRequest(workspace, 'shoaku/test'), null);
    assert.equal(JSON.parse(await readFile(join(workspace, '.shoaku', 'git-response.json'), 'utf8')).hash, result.hash);
    await writeFile(requestPath, JSON.stringify({ id: 'task-2', action: 'commit', files: ['other.txt'], message: 'Other behavior' }));
    const denied = await processGitRequest(workspace, 'shoaku/other');
    assert.equal(denied.status, 'failed');
    assert.match(denied.error, /session branch/);
    assert.equal(await git('rev-parse', 'HEAD'), result.hash);
  } finally { await rm(workspace, { recursive: true, force: true }); }
});

test('folds plan corrections into a session commit while preserving later work and the base branch', async () => {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-fixup-test-'));
  const git = async (...args) => (await execFile('git', ['-C', workspace, ...args])).stdout.trim();
  try {
    await git('init');
    await git('config', 'commit.gpgsign', 'false');
    await git('config', 'user.name', 'Test');
    await git('config', 'user.email', 'test@example.invalid');
    await writeFile(join(workspace, 'source.txt'), 'baseline\n');
    await git('add', 'source.txt'); await git('commit', '-m', 'Baseline');
    const base = await git('rev-parse', 'HEAD');
    await git('branch', 'original');
    await git('checkout', '--detach'); await ensureExplorerBranch(workspace, 'shoaku/test');
    await mkdir(join(workspace, '.shoaku'));
    await writeFile(join(workspace, '.shoaku', 'base-revision'), base);
    await writeFile(join(workspace, 'source.txt'), 'initial implementation\n');
    await git('add', 'source.txt'); await git('commit', '-m', 'Implement behavior');
    const target = await git('rev-parse', 'HEAD');
    await git('branch', 'checkpoint', target);
    await git('config', 'rebase.updateRefs', 'true');
    await writeFile(join(workspace, 'later.txt'), 'later work\n');
    await git('add', 'later.txt'); await git('commit', '-m', 'Add independent behavior');
    await writeFile(join(workspace, 'source.txt'), 'updated plan implementation\n');
    const request = { id: 'correction', action: 'fixup', target, files: ['source.txt'], message: 'Adapt to updated plan' };
    const submit = async value => {
      await writeFile(join(workspace, '.shoaku', 'git-request.json'), JSON.stringify(value));
      return processGitRequest(workspace, 'shoaku/test');
    };
    assert.equal((await submit({ ...request, id: 'outside', target: base })).status, 'failed');
    await writeFile(join(workspace, 'later.txt'), 'unrelated pending work\n');
    assert.match((await submit({ ...request, id: 'dirty' })).error, /unrelated changes/);
    await writeFile(join(workspace, 'later.txt'), 'later work\n');
    const result = await submit(request);
    assert.equal(result.status, 'complete', result.error);
    assert.equal(await git('log', '--reverse', '--format=%s', `${base}..HEAD`), 'Implement behavior\nAdd independent behavior');
    assert.equal(await git('show', 'HEAD~1:source.txt'), 'updated plan implementation');
    assert.equal(await git('show', 'HEAD:later.txt'), 'later work');
    assert.equal(await git('rev-parse', 'original'), base);
    assert.equal(await git('rev-parse', 'checkpoint'), target);
    assert.equal(await git('diff', 'HEAD'), '');
    assert.equal(await processGitRequest(workspace, 'shoaku/test'), null);
    assert.equal(result.hash, await git('rev-parse', 'HEAD'));
    const rewrittenTarget = await git('rev-parse', 'HEAD~1');
    await writeFile(join(workspace, 'source.txt'), 'later source change\n');
    await git('add', 'source.txt'); await git('commit', '-m', 'Later source behavior');
    const beforeConflict = await git('rev-parse', 'HEAD');
    await writeFile(join(workspace, 'source.txt'), 'correction conflicting with later work\n');
    const conflict = await submit({ ...request, id: 'conflict', target: rewrittenTarget });
    assert.equal(conflict.status, 'failed');
    assert.match(conflict.error, /fixup commit is preserved/);
    assert.equal(await git('rev-parse', 'HEAD~1'), beforeConflict);
    assert.equal(await git('show', 'HEAD:source.txt'), 'correction conflicting with later work');
    assert.equal(await git('symbolic-ref', '--short', 'HEAD'), 'shoaku/test');
    assert.equal(await git('diff', 'HEAD'), '');
  } finally { await rm(workspace, { recursive: true, force: true }); }
});
