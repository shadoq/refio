---
name: code-editing-user
type: tool
description: User prompt for code editing - provides file content and edit instruction
role: user
variables:
  - FILE_PATH
  - LANGUAGE
  - ORIGINAL_CONTENT
  - EDIT_DESCRIPTION
---

FILE: {{FILE_PATH}}
LANGUAGE: {{LANGUAGE}}

CURRENT CONTENT:
```{{LANGUAGE}}
{{ORIGINAL_CONTENT}}
```

EDIT INSTRUCTION:
{{EDIT_DESCRIPTION}}

OUTPUT THE COMPLETE MODIFIED FILE CONTENT:
