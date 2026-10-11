import test from 'node:test';
import assert from 'node:assert/strict';
import { createManagedWorktree, parseWorktreeBootstrap, worktreeBootstrapPrompt, runWorktreeCommand } from './managed-worktree.js';
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
