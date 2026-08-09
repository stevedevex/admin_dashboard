#!/usr/bin/env sh
# Publication guard.
#
# Fails if any staged file contains a term from the deny-list. The deny-list
# itself lives in .denyterms, which is git-ignored on purpose: writing the
# forbidden terms into a committed file would defeat the check it performs.
#
# Copy .denyterms.example to .denyterms and fill in the real terms locally.
#
# Usage:  npm run guard          (checks staged files; falls back to tracked files)

set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TERMS="$ROOT/.denyterms"

if [ ! -f "$TERMS" ]; then
  echo "guard: no .denyterms found — copy .denyterms.example and fill it in." >&2
  echo "guard: refusing to pass a check that has nothing to check." >&2
  exit 1
fi

# One term per line; blank lines and # comments ignored.
PATTERN="$(grep -vE '^\s*(#|$)' "$TERMS" | paste -sd '|' -)"

if [ -z "$PATTERN" ]; then
  echo "guard: .denyterms is empty — refusing to pass." >&2
  exit 1
fi

FILES="$(git diff --cached --name-only --diff-filter=ACM 2>/dev/null || true)"
[ -z "$FILES" ] && FILES="$(git ls-files 2>/dev/null || true)"
[ -z "$FILES" ] && { echo "guard: no files to check."; exit 0; }

HITS=0
for f in $FILES; do
  [ -f "$f" ] || continue
  case "$f" in
    .denyterms*|scripts/check-terms.sh) continue ;;
  esac
  if grep -niE "$PATTERN" "$f" >/dev/null 2>&1; then
    echo "guard: forbidden term in $f" >&2
    grep -niE "$PATTERN" "$f" | head -5 >&2
    HITS=$((HITS + 1))
  fi
done

if [ "$HITS" -gt 0 ]; then
  echo "" >&2
  echo "guard: $HITS file(s) contain terms that must not be published." >&2
  exit 1
fi

echo "guard: clean."
