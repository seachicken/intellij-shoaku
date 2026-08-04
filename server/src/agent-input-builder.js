export const inputType = {
  GOAL: 1,
  LSP: 2
}

export default class AgentInputBuilder {
  constructor(rootPath, debounceMs, maxEvents = 20) {
    this.rootPath = rootPath;
    this.debounceMs = debounceMs;
    this.maxEvents = maxEvents;
    this.listeners = [];
    this.events = [];
    this.timer;
  }

  onAgentInput(listener) {
    this.listeners.push(listener);
  }

  ingest(event) {
    if (this.events.length > 0 && JSON.stringify(event) === JSON.stringify(this.events.at(-1))) {
      return;
    }

    if (this.events.length >= this.maxEvents) {
      this.events.shift();
    }
    this.events.push(event);

    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      let lastOperation = null;
      for (const event of this.events) {
        switch (event.type) {
          case inputType.LSP:
            if (event.content.method === 'textDocument/didChange') {
              // ex. file:///project/root/Main.java -> Main.java
              const relativePath = event.content.params.textDocument.uri.slice(this.rootPath.length + 8);
              const line = event.content.params.contentChanges[0].range.start.line;
              lastOperation = `- Changed ${relativePath} around line ${line}`;
            }
            break;
          case inputType.GOAL:
            lastOperation = event.content;
            break;
        }
      }

      switch (event.type) {
        case inputType.LSP:
          if (lastOperation) {
            for (const listener of this.listeners) {
              listener([
                'Driver operations:',
                lastOperation,
              ].join('\n'));
            }
            lastOperation = null;
          }
          break;
        case inputType.GOAL:
          if (lastOperation) {
            for (const listener of this.listeners) {
              listener(lastOperation);
            }
            lastOperation = null;
          }
          break;
      }
    }, this.debounceMs);
  }
}

