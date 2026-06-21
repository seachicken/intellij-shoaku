export function renderSummary(content, shoakuId, summary) {
  return renderSummaryDiff(content, shoakuId, summary, { operator: '', unified: null });
}

export function renderSummaryDiff(content, shoakuId, summary, { operator = '+', unified = 3 } = {}) {
  let lineNumber = -1;
  let parentIndent = -1;
  let indentChar = ' ';
  let insertLine = -1;
  let insertIndent = Number.MAX_VALUE;

  const countIndent = (line) => line.match(/^\s*/)[0].length;

  const lines = content.split('\n');
  for (const line of lines) {
    lineNumber++;

    if (parentIndent >= 0) {
      const indent = countIndent(line);
      if (indent > parentIndent && indent < insertIndent) {
        indentChar = line[0] ?? ' ';
        insertIndent = indent;
      }
      if (indent <= parentIndent) {
        break;
      }
      insertLine = lineNumber;
      continue;
    }

    if (line.includes(`[${shoakuId}]`)) {
      insertLine = lineNumber;
      parentIndent = countIndent(line);
    }
  }

  if (parentIndent < 0) {
    return '';
  }

  if (insertIndent === Number.MAX_VALUE) {
    insertIndent = parentIndent + 2;
  }

  const summaryLines = JSON.parse(summary).summary.map(line => `${operator}${indentChar.repeat(insertIndent)}- ${line} by AI`);
  lines.splice(insertLine + 1, 0, ...summaryLines);

  if (unified == null) {
    return lines.join('\n');
  } else {
    const start = Math.max(0, insertLine - unified);
    const end = Math.min(lines.length, insertLine + unified + 1 + summaryLines.length);
    const targetLines = lines.slice(start, end);
    return targetLines.join('\n');
  }
}

