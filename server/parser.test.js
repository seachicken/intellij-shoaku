import assert from 'node:assert';
import test from 'node:test';
import parser from './parser.js';

test('parse when root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
`), [
      {
        type: 'text',
        content: 'a',
        children: []
      }
    ]
  );
});

test('parse when nested', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
  - [shoaku] b
`), [
      {
        type: 'text',
        content: 'b',
        children: []
      }
    ]
  );
});

test('parse when only labeled root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
- b
`), [
      {
        type: 'text',
        content: 'a',
        children: []
      }
    ]
  );
});

test('not parse', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
`), [
    ]
  );
});

test('parse non-checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [ ] [shoaku] a
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
- [x] [shoaku] a
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
- [shoaku] a
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
