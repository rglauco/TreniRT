# R8 rules for TreniRT's release build.
#
# Gson's own rules (generic signatures, TypeToken subclasses) ship inside the Gson jar, and
# OkHttp, Compose and coroutines ship theirs too — only app-specific rules belong here.

# Shrink and optimize, but don't rename: crash stack traces from users stay readable
# without having to archive a mapping.txt for every release.
-dontobfuscate

# Gson models (parsed from ViaggiaTreno/Italo, or saved as JSON in SharedPreferences) are
# NOT listed here: they're annotated with @androidx.annotation.Keep in the source, so the
# rule follows the class through renames. Any new class that Gson reads or writes needs
# @Keep too — without it R8 strips its fields and parsing silently returns empty objects.
