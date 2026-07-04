import { promisify } from 'node:util';
import child_process from 'node:child_process';

const exec = promisify(child_process.exec);

export async function cleanupStaleBranches(workspacePath, {
  branchListFun = async (workspacePath) => await exec(`git -C ${workspacePath} branch --format="%(refname)"`),
  worktreeListFun = async (workspacePath) => await exec(`git -C ${workspacePath} worktree list --porcelain`),
  branchDeleteFun = async (workspacePath, branches) => await exec(`git -C ${workspacePath} branch -D ${branches}`),
  worktreeDeleteFun = async (workspacePath, worktreePath) => await exec(`git -C ${workspacePath} worktree remove --force ${worktreePath}`),
} = {}) {
  const allShoakuBranches = [];
  await branchListFun(workspacePath).then(({ stdout }) => {
    for (const line of stdout.split('\n')) {
      if (line.startsWith('shoaku/')) {
        allShoakuBranches.push(line);
      }
    }
  });

  const livedWorktrees = [];
  await worktreeListFun(workspacePath).then(({ stdout }) => {
    let currentWorktree = {};
    for (const line of stdout.split('\n')) {
      const worktreePrefix = 'worktree ';
      if (line.startsWith(worktreePrefix)) {
        currentWorktree.path = line.slice(worktreePrefix.length);
      }
      const branchPrefix = 'branch ';
      if (line.startsWith(branchPrefix)) {
        currentWorktree.branch = line.slice(branchPrefix.length);
      }
      if (line.startsWith('prunable ')) {
        worktreeDeleteFun(workspacePath, currentWorktree.path);
      } else {
        livedWorktrees.push(currentWorktree);
      }
    }
  });

  const staleBranches = allShoakuBranches
    .filter(branch => !livedWorktrees.some(worktree => worktree.branch === branch));
  if (staleBranches.length > 0) {
    await branchDeleteFun(workspacePath, staleBranches.join(' '));
  }
}
