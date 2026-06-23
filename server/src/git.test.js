import assert from 'node:assert';
import { afterEach, beforeEach, describe, mock, test } from 'node:test';
import { cleanupStaleBranches } from './git.js';

test('deletes prunable worktrees and orphaned branches', (t, done) => {
  cleanupStaleBranches('dummy_path', {
    branchListFun: async () => ({
      stdout: [
        'main',
        'shoaku/zndqNI'
      ].join('\n')
    }),
    worktreeListFun: async () => ({
      stdout: [
        'worktree /home/user/project',
        'HEAD 0aaa321f307ce92eef93cbf65adc2a985d546f0f',
        'branch refs/heads/main',
        '',
        'worktree /tmp/shoaku-0Fbrji',
        'HEAD 59042a903e335fdfe6b08b5fe73dcd07836c0822',
        'branch refs/heads/shoaku-0Fbrji',
        '',
        'worktree /tmp/shoaku-0v1JJZ',
        'HEAD 418ed52fccd25c20d9e1153f4bbb06a097fd6577',
        'branch refs/heads/shoaku-0v1JJZ',
        'prunable gitdir file points to non-existent location',
      ].join('\n')
    }),
    branchDeleteFun: async (workspacePath, branches) => {
      assert.strictEqual(
        branches,
        'shoaku/zndqNI',
      );
      setImmediate(done);
    },
    worktreeDeleteFun: async (workspacePath, worktreePath) => {
      assert.strictEqual(
        worktreePath,
        '/tmp/shoaku-0v1JJZ',
      );
    }
  });
});

