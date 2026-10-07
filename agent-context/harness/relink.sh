#!/usr/bin/env bash
# Links the app's sources into the harness (src/main/kotlin/real), relative, so it always compiles the code of this
# checkout. Run it after adding or removing a file in app/src/main/java/com/example/forgegen (or gallery/): a missing
# link shows up as "Unresolved reference" when the harness compiles. Android UI files (Compose screens, activities,
# services the JVM stubs cannot load) stay out: the screenshot rig covers them.
set -euo pipefail
HARNESS="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HARNESS/../.." && pwd)"
APP="$REPO/app/src/main/java/com/example/forgegen"
REAL="$HARNESS/src/main/kotlin/real"
SKIP=" AppLicense.kt AppLock.kt ForgeApp.kt ForgeTheme.kt MainActivity.kt MainScreen.kt QueueTileService.kt WidgetViews.kt "
find "$REAL" -maxdepth 1 -type l -delete
# All in one folder (Kotlin does not mind): the app's package, its gallery/ files and the prompt tag helpers.
link() { ln -s "$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$1" "$REAL")" "$REAL/$(basename "$1")"; }
for f in "$APP"/*.kt; do
    case "$SKIP" in *" $(basename "$f") "*) continue ;; esac
    link "$f"
done
for f in "$APP"/gallery/*.kt; do link "$f"; done
link "$APP/ui/components/PromptTags.kt"
echo "Linked $(find "$REAL" -type l | wc -l) sources into $REAL"
