---
description: "Rules regarding the order of artifact updates during tasks."
---

# Task Artifact Update Order

When executing tasks that involve the creation or updating of both the `changelog` artifact and the `walkthrough` artifact:

1. You **MUST** update the `task.md` artifact twice.
2. The **FIRST** update to `task.md` must occur **BEFORE** you create or update the `changelog` artifact.
3. The **SECOND** update to `task.md` must occur **AFTER** you create or update the `walkthrough` artifact.

Always follow this order whenever both changelog and walkthrough artifacts are required for the task.
