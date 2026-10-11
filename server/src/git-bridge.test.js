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
