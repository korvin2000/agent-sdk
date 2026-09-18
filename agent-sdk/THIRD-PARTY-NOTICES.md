# Third-party notices

This SDK is a from-scratch Java implementation, but a number of **model-facing strings** (tool
descriptions, guidelines, refusal and reprompt texts, truncation notices, the validation-failure
block) and the **system-prompt templates** are reproduced verbatim or with mechanical tool-name
substitutions from the MIT-licensed projects below. Prompt text tuned against real models is a
load-bearing asset, and the MIT licence permits its reuse **with the notice**. This file ships in
`META-INF/` of every jar (`agent-core`, `agent-tools`, `agent-testkit`); the build fails if it is
missing (`licenseNotices` task).

No material from Apache-2.0 or GPL-licensed sources is included. `mini-coding-agent` (Apache-2.0)
was used only as the described *design* of path containment; nothing was transcribed. `zerostack` (GPL-3.0-only) was never read.

| Source | Licence | What is reused | Where |
|---|---|---|---|
| pi-mono (Mario Zechner) | MIT | Coding-tool design and prompt influences; `Tool {name} not found`; `Tool execution was blocked`; the `Validation failed for tool "..."` block; `(no output)`; `PendingMessageQueue` semantics | `agent-core`: `sdk.agent.json.ArgumentException`, `sdk.agent.tool.ToolMessages`, `sdk.agent.MessageQueue`; `agent-tools`: `sdk.agent.tools` |
| nanocoder (Nano Collective) | MIT | Coding-tool refusal/output design influences; `TRUNCATED_TURN_INSTRUCTION`, `FINAL_TURN_INSTRUCTION`, `CONTINUE_NUDGE` | `agent-core`: `sdk.agent.hook.TurnGuard`; `agent-tools`: `sdk.agent.tools` |
| kon (0xku) | MIT | Invalid-JSON tool-call string; `(no output)`; workspace instructions and prompt-section design influences | `agent-core`: `sdk.agent.tool.ToolMessages`; `agent-tools`: `sdk.agent.tools.CodingToolsExtension` |
| mini-swe-agent (Kilian A. Lieret and Carlos E. Jimenez) | MIT | `action was not executed`; `STOPPED_WITHOUT_TOOL_CALL_INSTRUCTION` (the tool-call-less branch of `format_error_template`)| `agent-core`: `sdk.agent.tool.ToolMessages`, `sdk.agent.hook.TurnGuard` |
| tiny-coding-agent (Dung Huynh Duc) | MIT | `LOOP_DETECTED`; original loop-detection design influence (now consecutive identical batches only) | `agent-core`: `sdk.agent.hook.TurnGuard` |

## MIT License — pi-mono

Copyright (c) 2025 Mario Zechner

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## MIT License — nanocoder

Copyright (c) 2026 Nano Collective

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## MIT License — kon

Copyright (c) 2026 0xku

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## MIT License — mini-swe-agent

Copyright (c) 2025 Kilian A. Lieret and Carlos E. Jimenez

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## The MIT License (MIT) — tiny-coding-agent

Copyright (c) 2026 Dung Huynh Duc

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
