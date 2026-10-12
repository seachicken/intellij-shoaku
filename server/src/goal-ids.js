import parser from './parser.js';

export async function assignGoalIds(text, reserve) {
  const lines = text.split('\n');
  let changed = false;
  for (const goal of parser.parse(text)) {
    if (goal.shoakuId || !goal.labelPosition) continue;
    const { start, end } = goal.labelPosition;
    const id = await reserve();
    lines[goal.line] = lines[goal.line].slice(0, start) + `[${id}]` + lines[goal.line].slice(end);
    changed = true;
  }
  return { text: changed ? lines.join('\n') : text, changed };
}
