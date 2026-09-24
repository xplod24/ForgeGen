---
name: Changelog Pre-Build Requirement
description: Ensures a bilingual changelog is generated and saved before launching ANY app build (even debug or verification builds).
---
# Pre-Build Changelog

**Before** launching ANY Gradle build (whether for debugging, compiling, verifying, or releasing an update), you MUST treat it as a release and:

1. **Draft a Small Changelog**: Summarize the changes introduced since the last build.
2. **Translate**: Ensure the changelog is written in both **English** and **Polish**.
3. **Feed into Actual Changelog**: Append or prepend this new changelog entry into the project's actual `CHANGELOG.md` file (create it in the project root if it does not exist).
4. **Show as a Separate Artifact**: ALWAYS present the generated changelog to the user as a newly created Artifact file (a markdown artifact using the UI). IT ALWAYS HAS TO SHOW UP AS A SEPARATE ARTIFACT before proceeding.
5. Do not proceed with the build until the changelog file is successfully updated and presented as an artifact.
