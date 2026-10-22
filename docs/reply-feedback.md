# Reply feedback — FlowChat 1.5.4

Implemented on 2026-10-22. The change adapts Codex's progress/answer separation and concise, evidence-based communication to FlowChat's existing providers and phone/command tools.

## Source references

Reviewed OpenAI's public Codex repository at revision `b337fe06b7d8d6e5b42295e04c5e92c2b9c46567`. Implementation is original Kotlin; no Codex runtime is embedded.

- [Progress updates and final-response guidance](https://github.com/openai/codex/blob/b337fe06b7d8d6e5b42295e04c5e92c2b9c46567/codex-rs/core/gpt_5_1_prompt.md): short updates on meaningful progress, followed by a self-contained result.
- [Agent message phase metadata](https://github.com/openai/codex/blob/b337fe06b7d8d6e5b42295e04c5e92c2b9c46567/codex-rs/protocol/src/items.rs): commentary and final answers have distinct meanings; providers may omit native phase metadata.
- [Plan event handling](https://github.com/openai/codex/blob/b337fe06b7d8d6e5b42295e04c5e92c2b9c46567/codex-rs/core/src/tools/handlers/plan.rs) was reviewed as context. This release uses the existing task flow and adds no separate planning UI or planning tool.

## Behavior

- A provider-neutral response policy now applies even when a conversation has no custom system prompt. User language, tone, format and custom instructions remain available. Simple chat should receive a direct answer; action tasks should get a concise preface, meaningful updates, and a result grounded in tool evidence.
- Removed the bundled requirement to generate 2–5 visible thinking paragraphs. Thinking settings and provider-specific reasoning metadata remain intact. Thinking text is collapsed by default and can be expanded manually.
- Assistant segments preceding tool execution are stored as commentary. Completed task activity is folded into one expandable row. Commentary is excluded from subsequent conversation history so old intentions are not replayed as answers.
- A tool limit, repeated call loop, three consecutive failed actions, or a cancelled permission ends execution and allows one tool-free finalization request. Further tool calls during finalization are rejected. Empty conclusions after tools or reasoning-only output get at most one conclusion attempt, then an explicit local incomplete-result message.
- Truncated Chat Completions streams and output-limit termination are failures, not successful completion. Partial text is retained with an interruption notice. Partial tool calls are not executed. No automatic retry follows a partially received stream.
- Removed synthetic typing pauses and tiny display chunks. Actual provider deltas are shown directly. Running commands expose elapsed seconds from the real command state.
- The floating window uses the current user turn, so it does not show the previous answer or a finished tool as the current operation. Existing pause, cancellation and confirmation boundaries remain.
- Common Chinese error feedback distinguishes rejected credentials/access, rate or quota limits, service failures, timeouts and incomplete output. Plain text is preferred to match the current message renderer.

## Persistence and compatibility

Database migration 8 → 9 adds `messages.replyPhase`, defaulting existing rows to `Answer`; no data reset is used. The migration/reopen test checks retained provider credential aliases, messages, attachments and conversation settings, and persists the new phase. Reply phase is local metadata, so no unsupported phase parameter is added to Chat Completions, Anthropic or Responses requests. Opaque provider reasoning/signatures remain available during tool continuation.

## Validation

- 299 unit tests: 298 passed, one existing Windows symlink-privilege case skipped; zero failures.
- Debug, instrumentation and release builds passed, including release lint/R8.
- MuMu instance 0, explicit ADB target `127.0.0.1:16384`: updated the existing app in place to 1.5.4 (18).
- A loopback model drove the real chat UI and a harmless Linux command. It verified live preface/elapsed status, stored phases, collapsed reasoning and activity, expandable history, the command's actual output/exit code, and interruption feedback without another request. Exactly three local HTTP requests covered the successful task and interrupted follow-up.
- The first diagnostic screenshots were obscured by the launch overlay because Compose's test clock had not advanced. The fixture now advances past the launch animation; the rerun passed and its actual rendered screens were inspected.
- A bounded live test reused the existing DeepSeek configuration (`deepseek-v4-flash`) with synthetic ledger data only. Each of two validation passes used two requests and produced a preface, one read-only simulated file call, and the correct 17 + 25 = 42 result. No personal file content or credentials were exported. This is a targeted smoke test, not a guarantee of identical behavior across models.
- Diagnostic conversations/providers are removed and temporary command, Power and memory settings restored. No physical phone was used.

Ignored validation artifacts are under `build/codex-feedback/`: build logs, device logs, `captures-final/`, and `live-results.txt`. Installable packages are `build/releases/FlowChat-Dev-1.5.4.apk` and `build/releases/FlowChat-1.5.4-retain-config.apk`.

The retain-config release continues to use `com.flowchat.app.debug` and the existing certificate (SHA-256 `423042ed2303b81afcb8d6581541e0dab977297a9893b8906071fd2867681772`) to support an in-place update of the user's current installation. Do not uninstall or clear app data first.
