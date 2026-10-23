import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

// Structural traceability is checked against immutable Git blobs. Semantic
// rationale and verification claims remain author-provided review material.
export async function inspectReviewMap(workspace, commit, git) {
  const json = await readFile(join(workspace, '.shoaku', 'commits', `${commit.hash}.review.json`), 'utf8');
  if (Buffer.byteLength(json) > 512 * 1024) throw new Error('Design map is too large');
  const map = JSON.parse(json);
  const text = value => typeof value === 'string' && value.trim() && value.length <= 4000;
  if (map.schemaVersion !== 1 || map.commit !== commit.hash || ![map.problem, map.before, map.after].every(text)) throw new Error('Review map must identify this commit and explain problem/before/after');
  if (!Array.isArray(map.steps) || !map.steps.length || map.steps.length > 24) throw new Error('Review map needs 1–24 design steps');
  const parent = (await git(workspace, 'rev-list', '--parents', '-n', '1', commit.hash)).trim().split(' ')[1];
  const changed = (parent
    ? await git(workspace, 'diff', '--name-only', '--no-renames', '-z', parent, commit.hash, '--')
    : await git(workspace, 'diff-tree', '--root', '--no-commit-id', '--name-only', '--no-renames', '-r', '-z', commit.hash, '--')).split('\0').filter(Boolean);
  const ids = new Set();
  const covered = new Set();
  const blobs = new Map();
  for (const step of map.steps) {
    if (!/^[a-zA-Z0-9_-]{1,80}$/.test(step.id || '') || ids.has(step.id) || ![step.title, step.why, step.change, step.verification].every(text)) throw new Error('Each design step needs a unique ID, title, rationale, change, and verification');
    if (step.intent !== undefined && !text(step.intent)) throw new Error('Step intent must be concise nonempty text');
    ids.add(step.id);
    if (!Array.isArray(step.anchors) || !step.anchors.length || step.anchors.length > 20) throw new Error(`Step ${step.id} has no concrete text or code locations`);
    for (const anchor of step.anchors) {
      if (!changed.includes(anchor.path) || !['before', 'after'].includes(anchor.side) || !Number.isInteger(anchor.startLine) || !Number.isInteger(anchor.endLine) || anchor.startLine < 1 || anchor.endLine < anchor.startLine || !text(anchor.label)) throw new Error(`Step ${step.id} has an invalid text or code location`);
      const revision = anchor.side === 'before' ? parent : commit.hash;
      if (!revision) throw new Error('The root commit has no before-side code');
      const object = `${revision}:${anchor.path}`;
      if (!blobs.has(object)) {
        const size = Number((await git(workspace, 'cat-file', '-s', object)).trim());
        if (size > 2 * 1024 * 1024) throw new Error('Large files must be explicitly excluded from line mapping');
        blobs.set(object, await git(workspace, 'show', object));
      }
      const blob = blobs.get(object);
      const lines = blob ? blob.split('\n').length - (blob.endsWith('\n') ? 1 : 0) : 0;
      if (blob.includes('\0') || anchor.endLine > lines) throw new Error(`Text or code location ${anchor.path}:${anchor.startLine}-${anchor.endLine} does not exist in this commit`);
      covered.add(anchor.path);
    }
    if (!Array.isArray(step.links) || step.links.some(link => !text(link.label) || typeof link.to !== 'string')) throw new Error('Design relationships need target IDs and labels');
  }
  for (const step of map.steps) if (step.links.some(link => !ids.has(link.to))) throw new Error('Design flow refers to an unknown step');
  const markdown = await readFile(join(workspace, '.shoaku', 'commits', `${commit.hash}.md`), 'utf8');
  if (map.steps.some(step => !markdown.includes(step.title))) throw new Error('Design step titles must also appear in the Markdown explanation');
  if (!Array.isArray(map.excludedFiles) || map.excludedFiles.some(item => !changed.includes(item.path) || !text(item.reason) || covered.has(item.path))) throw new Error('Excluded files need actual changed paths and reasons');
  const accounted = new Set([...covered, ...map.excludedFiles.map(item => item.path)]);
  const unmapped = changed.filter(path => !accounted.has(path));
  if (unmapped.length) throw new Error(`Changed files lack design correspondence: ${unmapped.join(', ')}`);
  return map;
}
