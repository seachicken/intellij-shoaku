import assert from 'node:assert';
import test from 'node:test';
import { renderSummary, renderSummaryDiff } from './diff-snippet.js';

test('insert summary when parent has no children', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
`.trimStart(), 'shoaku-aA1234', '- summary'), `
- a [shoaku-aA1234]
  - summary
`.trimStart());
});

test('insert summary after existing children', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
  - b
    - c
  - d
`.trimStart(), 'shoaku-aA1234', '- summary'), `
- a [shoaku-aA1234]
  - b
    - c
  - d
  - summary
`.trimStart());
});

test('insert multi-line summary', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
`.trimStart(), 'shoaku-aA1234', `
- summary A
- summary B
`.trim()), `
- a [shoaku-aA1234]
  - summary A
  - summary B
`.trimStart());
});

test('insert summary with unified lines', (t) => {
  assert.deepStrictEqual(renderSummaryDiff(`
- 0
- 1
- a [shoaku-aA1234]
- 3
- 4
`.trimStart(), 'shoaku-aA1234', '- summary', { unified: 1 }), `
- 1
- a [shoaku-aA1234]
+  - summary
- 3
`.trim());
});

