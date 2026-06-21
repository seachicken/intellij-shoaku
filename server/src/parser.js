function parse(text) {
  const root = { children: [] };
  const stack = [{ indent: -1, node: root }];
  let lineNumber = -1;

  for (const line of text.split('\n')) {
    lineNumber++;
    const match = line.match(/^(\s*)(?:- |\* |)(\[[ |x]\]|) *(?:\[(shoaku(?:-[A-Za-z0-9]+)?)\])? *(.+?) *(?:\[(shoaku(?:-[A-Za-z0-9]+)?)\])?$/d);
    if (!match) {
      continue;
    }

    const indent = match[1].length;
    const checked = match[2];
    const leadingLabel = match[3];
    const content = match[4];
    const trailingLabel = match[5];
    const label = leadingLabel || trailingLabel;
    const labelRange = (leadingLabel ? match.indices[3] : match.indices[5]);

    while (stack[stack.length - 1].indent >= indent) {
      stack.pop();
    }

    if (!label && stack[stack.length - 1].indent < 0) {
      continue;
    }

    const parent = stack[stack.length - 1].node;
    const node = {
      type: 'text',
      content,
      line: lineNumber,
      children: [],
      ...(checked && { checked: checked === '[x]' }),
      ...(label && label !== 'shoaku' && { shoakuId: label }),
      ...(label && { labelPosition: { line: lineNumber, start: labelRange[0] - 1, end: labelRange[1] + 1 } })
    };
    parent.children.push(node);
    stack.push({ indent, node });
  }

  return root.children;
}

export default {
  parse,
};
