function parse(text) {
  const root = { children: [] };
  const stack = [{ indent: -1, node: root }];
  let lineNumber = -1;
  let isCodeBlock = false;
  let codeBlockLineNumber = -1;
  let codeBlock = '';
  let codeBlockIndent = 0;

  for (const line of text.split('\n')) {
    lineNumber++;

    let lineNode = lineNumber;
    let indent = 0;
    let checked = false;
    let leadingLabel = '';
    let content = '';
    let trailingLabel = '';
    let label = leadingLabel || trailingLabel;
    let labelRange = [0, 0];

    if (line.trim().startsWith('```')) {
      if (!isCodeBlock) {
        codeBlockLineNumber = lineNumber;
        codeBlockIndent = line.match(/^(\s*)/)?.[1].length ?? 0;
      }
      isCodeBlock = !isCodeBlock;
    }
    if (isCodeBlock) {
      codeBlock += line.slice(codeBlockIndent) + '\n';
      continue;
    }

    if (codeBlock) {
      lineNode = codeBlockLineNumber;
      indent = codeBlockIndent;
      content = codeBlock + line.slice(codeBlockIndent);

      codeBlock = '';
      codeBlockIndent = 0;
      isCodeBlock = false;
    } else {
      const match = line.match(/^(\s*)(?:- |\* |)(\[[ |x]\]|) *(?:\[(shoaku(?:-[A-Za-z0-9]+)?)\])? *(.+?) *(?:\[(shoaku(?:-[A-Za-z0-9]+)?)\])?$/d);
      if (match) {
        indent = match[1].length;
        checked = match[2];
        leadingLabel = match[3];
        content = match[4];
        trailingLabel = match[5];
        label = leadingLabel || trailingLabel;
        labelRange = (leadingLabel ? match.indices[3] : match.indices[5]);
      }
    }

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
      line: lineNode,
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
