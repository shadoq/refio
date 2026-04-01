---
name: multi-line-editing-user
type: tool
description: User prompt for multi-line editing - provides numbered file content and edit instruction
role: user
variables:
  - FILE_PATH
  - LANGUAGE
  - EDIT_DESCRIPTION
  - NUMBERED_CONTENT
---

FILE: {{FILE_PATH}}
LANGUAGE: {{LANGUAGE}}
EDIT DESCRIPTION: {{EDIT_DESCRIPTION}}

CURRENT CONTENT (with line numbers):
{{NUMBERED_CONTENT}}

Instructions:
1. Identify the minimal line ranges that need to be changed to fulfill: "{{EDIT_DESCRIPTION}}"
2. Return ONLY the JSON with changes array
3. Do NOT include explanations, markdown, or any text outside the JSON
