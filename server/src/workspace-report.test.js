import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import childProcess from 'node:child_process';
import { recordWorkspaceRepositories, inspectWorkspaceReport } from './workspace-report.js';

const execFile = promisify(childProcess.execFile);
const git = async (directory, ...args) => (await execFile('git', ['-C', directory,
  '-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', ...args])).stdout.trim();
async function fixture(t) {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-reports-'));
  t.after(() => rm(workspace, { recursive: true, force: true }));
  return workspace;
}
async function repository(workspace, name) {
  const directory = join(workspace, name);
  await mkdir(directory);
  await git(directory, 'init');
  return directory;
}
async function commit(directory, message) {
  await git(directory, 'add', 'source.txt');
  await git(directory, 'commit', '-m', message);
  return git(directory, 'rev-parse', 'HEAD');
}

test('one report includes only post-baseline commits by repository, with uncommitted work separate', async t => {
  const workspace = await fixture(t);
  const api = await repository(workspace, 'api');
  const web = await repository(workspace, 'web app');
  await writeFile(join(api, 'source.txt'), 'before');
  const old = await commit(api, 'Old implementation');
  // Unborn repositories and linked .git files are both valid child repositories.
  const linked = join(workspace, 'linked');
  await git(api, 'worktree', 'add', '--detach', linked);
  await symlink(api, join(workspace, 'alias'));
  const baseline = await recordWorkspaceRepositories(workspace);
  assert.deepEqual(baseline.map(repo => repo.path), ['api', 'linked', 'web app']);
  await writeFile(join(api, 'source.txt'), 'after');
  const apiHash = await commit(api, 'Support API\n\nKeep compatibility.');
  await writeFile(join(web, 'source.txt'), 'new UI');
  const webHash = await commit(web, 'Render UI');
  await writeFile(join(web, 'source.txt'), 'unfinished');
  await writeFile(join(workspace, '.shoaku/RESULTS.md'), '# Outcome\n\nUser-authored design.');
  assert.equal((await inspectWorkspaceReport(workspace)).ready, true);
  const path = join(workspace, '.shoaku/RESULTS.md');
  const report = await readFile(path, 'utf8');
  assert.ok(report.startsWith('# Outcome\n\nUser-authored design.'));
  for (const value of ['api', 'web app', 'linked', apiHash, webHash, 'Support API', 'Keep compatibility.', 'source.txt', 'Uncommitted changes', 'No commits since start.']) assert.ok(report.includes(value), value);
  assert.ok(!report.includes(old));
  assert.ok(!report.includes('Old implementation'));
  assert.ok(!report.includes('unfinished'));
  assert.equal(await git(web, 'rev-parse', 'HEAD'), webHash); // inspection never commits
  await inspectWorkspaceReport(workspace);
  assert.equal(await readFile(path, 'utf8'), report); // repeated refreshes do not duplicate sections
  assert.deepEqual(JSON.parse(await readFile(join(workspace, '.shoaku/repositories.json'), 'utf8')).repositories, baseline);
});

test('removed repositories remain visible without hiding other repositories or accepting a missing design', async t => {
  const workspace = await fixture(t);
  const directory = await repository(workspace, 'removed');
  await repository(workspace, 'remaining');
  await recordWorkspaceRepositories(workspace);
  assert.equal((await inspectWorkspaceReport(workspace)).ready, false);
  await rm(directory, { recursive: true, force: true });
  const path = join(workspace, '.shoaku/RESULTS.md');
  await writeFile(path, '# Design');
  await inspectWorkspaceReport(workspace);
  const report = await readFile(path, 'utf8');
  assert.ok(report.includes('Could not inspect this repository'));
  assert.ok(report.includes('remaining'));
  assert.ok(report.includes('No commits since start.'));
  await writeFile(path, report.slice(report.indexOf('<!-- shoaku:repository-commits -->')));
  assert.equal((await inspectWorkspaceReport(workspace)).ready, false);
});
