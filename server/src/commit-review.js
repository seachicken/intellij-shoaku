import childProcess from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { inspectReviewMap } from './review-map.js';

const execFile = promisify(childProcess.execFile);
const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, ...args], { maxBuffer: 4 * 1024 * 1024, timeout: 30000 })).stdout;

export async function recordReviewBase(workspace, { existing = false } = {}) {
  const file = join(workspace, '.shoaku', 'base-revision');
  try { return (await readFile(file, 'utf8')).trim(); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  // Older sessions already have a dedicated branch. Its oldest reflog entry
  // records branch creation; do not use today's HEAD as the starting revision.
  const revision = existing
    ? (await git(workspace, 'reflog', 'show', '--format=%H', 'HEAD')).trim().split('\n').at(-1)
    : (await git(workspace, 'rev-parse', 'HEAD')).trim();
  if (!/^[a-f0-9]{40,64}$/.test(revision || '')) throw new Error('Cannot establish session starting revision');
  await mkdir(join(workspace, '.shoaku'), { recursive: true });
  await writeFile(file, revision + '\n', { flag: 'wx' });
  return revision;
}

export async function inspectCommitReview(workspace) {
  const base = (await readFile(join(workspace, '.shoaku', 'base-revision'), 'utf8')).trim();
  if (!/^[a-f0-9]{40,64}$/.test(base)) throw new Error('Invalid session starting revision');
  await git(workspace, 'merge-base', '--is-ancestor', base, 'HEAD');
  const log = await git(workspace, 'log', '--reverse', '--format=%H%x09%s', `${base}..HEAD`);
  const commits = log.trim().split('\n').filter(Boolean).map(line => {
    const [hash, ...subject] = line.split('\t');
    return { hash, subject: subject.join('\t') };
  });
  const changed = (await git(workspace, 'diff', '--name-only', '-z', 'HEAD')).split('\0').filter(Boolean);
  const added = (await git(workspace, 'ls-files', '--others', '--exclude-standard', '-z')).split('\0').filter(Boolean);
  const uncommitted = [...new Set([...changed, ...added])].filter(path => !path.startsWith('.shoaku/'));
  const missingDocs = [];
  const invalidMaps = [];
  for (const commit of commits) {
    try {
      const doc = await readFile(join(workspace, '.shoaku', 'commits', `${commit.hash}.md`), 'utf8');
      if (!doc.includes(commit.hash)) missingDocs.push(commit.hash);
    } catch (error) { if (error.code !== 'ENOENT') throw error; missingDocs.push(commit.hash); }
    try { await inspectReviewMap(workspace, commit, git); }
    catch (error) { invalidMaps.push({ hash: commit.hash, error: error.code === 'ENOENT' ? 'Design-to-code map is missing' : error.message }); }
  }
  const results = await readFile(join(workspace, '.shoaku', 'RESULTS.md'), 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error;
    return '';
  });
  const missingLinks = commits.filter(commit => !results.includes(`commits/${commit.hash}.md`)).map(commit => commit.hash);
  const ready = uncommitted.length === 0 && missingDocs.length === 0 && missingLinks.length === 0 && invalidMaps.length === 0 && results.trim().length > 0;
  const detail = uncommitted.length ? `${uncommitted.length} implementation files remain uncommitted.`
    : !results.trim() ? 'The design report is missing.'
    : missingDocs.length ? `${missingDocs.length} commit explanations are missing or do not identify the actual commit.`
    : missingLinks.length ? 'The design report does not link to every implementation commit.'
    : invalidMaps.length ? `${invalidMaps.length} commits need valid design-to-code links: ${invalidMaps[0].error}`
    : commits.length ? `${commits.length} implementation commits are ready for review.` : 'No implementation changes; the design report is available.';
  const guide = [
    '# Implementation commits', '',
    `Starting revision: \`${base}\``, '', detail, '',
    '| Commit | Purpose | Explanation |', '| --- | --- | --- |',
    ...commits.map(commit => `| \`${commit.hash.slice(0, 12)}\` | ${commit.subject.replaceAll('|', '\\|')} | ${missingDocs.includes(commit.hash) ? 'Pending' : `[Read](commits/${commit.hash}.md)`} |`), '',
    '[Design](RESULTS.md)', ''
  ].join('\n');
  const guidePath = join(workspace, '.shoaku', 'COMMITS.md');
  const previous = await readFile(guidePath, 'utf8').catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
  if (previous !== guide) await writeFile(guidePath, guide);
  return { ready, detail, commits, uncommitted, missingDocs, missingLinks, invalidMaps };
}
