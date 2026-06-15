export const inputType = {
  GOAL: 1,
  LSP: 2
}

export default class AgentInputBuilder {
  constructor(rootPath, debounceMs) {
    this.rootPath = rootPath;
    this.debounceMs = debounceMs;
    this.listeners = [];
    this.events = [];
    this.timer;
  }

  onAgentInput(listener) {
    this.listeners.push(listener);
  }

  ingest(event) {
    this.events.push(event);

    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      let lastOperation;
      for (const event of this.events) {
        if (event.type === inputType.LSP) {
          if (event.content.method === 'textDocument/didChange') {
            // ex. file:///project/root/Main.java -> Main.java
            const relativePath = event.content.params.textDocument.uri.slice(this.rootPath.length + 8);
            const line = event.content.params.contentChanges[0].range.start.line;
            lastOperation = `- Changed ${relativePath} around line ${line}`;
          }
        }
      }

      if (lastOperation) {
        for (const listener of this.listeners) {
          listener(`
Driver operations:
${lastOperation}
          `.trim());
        }
      }
    }, this.debounceMs);
  }
}

