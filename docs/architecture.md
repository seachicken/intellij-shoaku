# Architecture

## Concept

The Navigator does not write code directly. Instead, it helps the user build understanding and make progress at their own pace, much like an experienced pair-programming partner.

Meanwhile, the Explorer works in the background toward a complete implementation. By indirectly observing the Explorer's progress, the Navigator can offer better context, guidance, and support.

```mermaid
graph LR
  user((User))
  ai-navigator[Navigator]
  ai-explorer[Explorer]

  user <--> ai-navigator --> ai-explorer
```

## Detailed Structure

```mermaid
graph TD
  subgraph user_workspace["IDE workspace"]
    project-files[(Project files)]
  end
  subgraph sandbox_workspace["temporary workspace"]
    worktree-files[(Project files)]
  end
  subgraph codex
    navigator[navigator thread]
    explorer[explorer thread]
  end

  shoaku-server -- read/write --> todo[("{goals}.md")]
  IDE <-- <a href='https://microsoft.github.io/language-server-protocol/'>LSP</a> --> shoaku-server -- <a href='https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md'>ASP</a> --> codex
  shoaku-server -- "git worktree add" --> worktree-files
  navigator -- read --> project-files
  explorer -- read/write --> worktree-files
```
