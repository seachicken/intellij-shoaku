import test from 'node:test';
import assert from 'node:assert/strict';
import { createManagedWorktree, isGitWorkspace, parseWorktreeBootstrap, worktreeBootstrapPrompt, runWorktreeCommand } from './managed-worktree.js';
const events = (answer) => [
  { type: 'thread.started', thread_id: 'native-thread' },
  { type: 'item.completed', item: { type: 'agent_message', text: answer } }
].map(JSON.stringify).join('\n');
test('uses the native CLI worktree lifecycle without overriding Git permissions', async () => {
  const threadId = await createManagedWorktree('/repo with spaces', async (program, args) => {
    assert.equal(program, 'codex');
    assert.deepEqual(args, ['--enable', 'worktrees', '--ask-for-approval', 'never', '--sandbox', 'workspace-write', 'exec', '--worktree', '--json', '--cd', '/repo with spaces', worktreeBootstrapPrompt]);
    return { stdout: events('SHOAKU_WORKTREE_READY') };
  });
  assert.equal(threadId, 'native-thread');
});
test('does not continue when native worktree initialization reports a permission error', () => {
  assert.throws(() => parseWorktreeBootstrap(events('SHOAKU_WORKTREE_BLOCKED: index.lock: Operation not permitted')), /native-thread.*index.lock/);
  assert.throws(() => parseWorktreeBootstrap(events('Inspecting files…')), /initialization failed/);
  assert.throws(() => parseWorktreeBootstrap('not JSON'), /initialization failed/);
});

test('closes piped input so the native CLI cannot wait for an appended prompt', async () => {
  const { stdout } = await runWorktreeCommand(process.execPath, ['-e', "process.stdin.resume(); process.stdin.on('end', () => process.stdout.write('EOF'));"], { timeout: 2000 });
  assert.equal(stdout, 'EOF');
});

test('non-Git parent containing repositories selects direct execution', async t => {
  const { mkdtemp, mkdir, rm } = await import('node:fs/promises');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const parent = await mkdtemp(join(tmpdir(), 'shoaku-workspace-'));
  t.after(() => rm(parent, { recursive: true, force: true }));
  for (const name of ['api', 'web']) {
    const repo = join(parent, name);
    await mkdir(repo);
    await runWorktreeCommand('git', ['init', repo], {});
    assert.equal(await isGitWorkspace(repo), true);
    const nested = join(repo, 'src');
    await mkdir(nested);
    assert.equal(await isGitWorkspace(nested), true);
  }
  assert.equal(await isGitWorkspace(parent), false);
  const broken = join(parent, 'broken');
  await mkdir(join(broken, '.git'), { recursive: true });
  await assert.rejects(isGitWorkspace(broken), /not a git repository/);
});

test('Git failures other than a non-repository error never fall back to direct edits', async () => {
  for (const error of [
    Object.assign(new Error('permission denied'), { code: 128, stderr: 'fatal: detected dubious ownership' }),
    Object.assign(new Error('missing Git'), { code: 'ENOENT' })
  ]) {
    await assert.rejects(isGitWorkspace('/workspace', async () => { throw error; }), e => e === error);
  }
});
