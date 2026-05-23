function parse(text) {
  const root = { children: [] };
  const stack = [{ indent: -1, node: root }];

  for (const line of text.split('\n')) {
    const match = line.match(/^(\s*)(?:- |\* |)(\[[ |x]\]|) *(\[shoaku\]|) *(.+)$/);
    if (!match) {
      continue;
    }

    const indent = match[1].length;
    const checked = match[2];
    const label = match[3];
    const content = match[4];

    if (!label && stack[stack.length - 1].indent < 0) {
      continue;
    }

    while (stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }

    const parent = stack[stack.length - 1].node;
    const node = {
      type: 'text',
      content,
      children: [],
      ...(checked && { checked: checked === '[x]' })
    };
    parent.children.push(node);
    stack.push({ indent, node });
  }

  return root.children;
}

export default {
  parse,
};
