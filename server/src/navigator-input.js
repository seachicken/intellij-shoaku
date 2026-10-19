// App-server echoes turn/start input as userMessage, including server prompts.
// Mark those prompts so their echoes never trigger another plan refresh.
const internalPrefix = '[Shoaku:internal]\n';
export const internalNavigatorInput = text => internalPrefix + text;

export function userConversationText(item) {
  if (item.type !== 'userMessage') return null;
  const text = item.content?.filter(part => part.type === 'text').map(part => part.text).join('\n');
  return text && !text.startsWith('[Shoaku:') ? text : null;
}
