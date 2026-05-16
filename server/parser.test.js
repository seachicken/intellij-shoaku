import assert from 'node:assert';
import test from 'node:test';
import parser from './parser.js';

test('parse', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
`), [
      {
        type: 'text',
        content: 'a',
        children: []
      }
    ]
  );
});

test('parse non-checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [ ] a
`), [
      {
        type: 'text',
        content: 'a',
        checked: false,
        children: []
      }
    ]
  );
});

test('parse checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [x] a
`), [
      {
        type: 'text',
        content: 'a',
        checked: true,
        children: []
      }
    ]
  );
});

test('parse nested nodes', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
  - a_a
`), [
      {
        type: 'text',
        content: 'a',
        children: [
          {
            type: 'text',
            content: 'a_a',
            children: []
          }
        ]
      }
    ]
  );
});
