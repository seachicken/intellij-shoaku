import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { inspectReviewMap } from './review-map.js';
const exec = promisify(execFile);
const run = async (command, args) => (await exec(command, args, { timeout: 120000, maxBuffer: 16 * 1024 * 1024 })).stdout;

export function inferGoalMode(goal) {
  const text = JSON.stringify(goal);
  return /https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/pull\//.test(text) ? 'review' : 'implementation';
}

export function parsePrTarget(goal) {
  const text = JSON.stringify(goal);
  const matches = [...text.matchAll(/https:\/\/github\.com\/([\w.-]+)\/([\w.-]+)\/pull\/([1-9]\d*)(?=[/\s"\\?#]|$)/g)];
  const targets = [...new Set(matches.map(m => `${m[1]}/${m[2]}/${m[3]}`))];
  if (targets.length !== 1) throw new Error('Add exactly one GitHub PR URL to this goal or its notes.');
  const [owner, repo, number] = targets[0].split('/');
  return { owner, repo, number };
}

export async function preparePrReview(workspace, goal, command = run) {
  const { owner, repo, number } = parsePrTarget(goal);
  const pr = JSON.parse(await command('gh', ['api', `repos/${owner}/${repo}/pulls/${number}`]));
  for (const hash of [pr.base?.sha, pr.head?.sha]) {
    if (!/^[a-f0-9]{40}$/.test(hash || '')) throw new Error('PR revisions are unavailable.');
  }
  if (!pr.head.repo?.full_name || !/^[\w.-]+\/[\w.-]+$/.test(pr.head.repo.full_name)) throw new Error('PR head repository is unavailable.');
  await mkdir(join(workspace, '.shoaku', 'commits'), { recursive: true });
  await command('git', ['init', workspace]);
  const git = (...args) => command('git', ['-C', workspace, ...args]);
  await git('fetch', '--no-tags', `https://github.com/${owner}/${repo}.git`, pr.base.sha);
  await git('fetch', '--no-tags', `https://github.com/${pr.head.repo.full_name}.git`, pr.head.sha);
  const base = (await git('merge-base', pr.base.sha, pr.head.sha)).trim();
  if (!/^[a-f0-9]{40}$/.test(base)) throw new Error('Could not determine the PR comparison base.');
  const snapshot = { head: pr.head.sha, base, title: pr.title, url: pr.html_url, body: pr.body || '' };
  await writeFile(join(workspace, '.shoaku', 'pr-review.json'), JSON.stringify(snapshot, null, 2));
  return snapshot;
}

export async function savePrDesign(workspace, snapshot, response, command = run) {
  if (!response || typeof response.overviewMd !== 'string' || typeof response.designMd !== 'string' || typeof response.reviewMapJson !== 'string') throw new Error('Reviewer did not return a design document.');
  if ([response.overviewMd, response.designMd, response.reviewMapJson].some(text => !text.trim() || Buffer.byteLength(text) > 512 * 1024)) throw new Error('Review documents are empty or too large.');
  const root = join(workspace, '.shoaku');
  const staged = await mkdtemp(join(root, 'review-update-'));
  try {
    const stagedRoot = join(staged, '.shoaku', 'commits');
    await mkdir(stagedRoot, { recursive: true });
    await writeFile(join(stagedRoot, `${snapshot.head}.md`), response.designMd);
    await writeFile(join(stagedRoot, `${snapshot.head}.review.json`), response.reviewMapJson);
    const changed = await command('git', ['-C', workspace, 'diff', '--name-only', '--no-renames', '-z', snapshot.base, snapshot.head, '--']);
    const map = JSON.parse(response.reviewMapJson);
    const emptyPr = !changed && map.schemaVersion === 1 && map.commit === snapshot.head &&
      [map.problem, map.before, map.after].every(value => typeof value === 'string' && value.trim()) &&
      Array.isArray(map.steps) && map.steps.length === 0 && Array.isArray(map.excludedFiles) && map.excludedFiles.length === 0;
    if (!emptyPr) await inspectReviewMap(staged, { hash: snapshot.head }, async (_, ...args) => {
      if (args[0] === 'rev-list') return `${snapshot.head} ${snapshot.base}`;
      return command('git', ['-C', workspace, ...args]);
    });
    await writeFile(join(root, 'commits', `${snapshot.head}.md`), response.designMd);
    await writeFile(join(root, 'commits', `${snapshot.head}.review.json`), response.reviewMapJson);
    await writeFile(join(root, 'RESULTS.md'), response.overviewMd);
  } finally { await rm(staged, { recursive: true, force: true }); }
}
