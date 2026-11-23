# Island test builds (assembleDebug made release-like).
# Keep class names readable in crash traces, and do not fail the CI build on
# missing-class warnings from debug-only dependencies (ui-tooling, test manifest).
-dontobfuscate
-ignorewarnings
