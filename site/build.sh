#!/usr/bin/env bash
# Builds the GitHub Pages site into build/site:
#   /       the landing page (site/index.html)
#   /docs/  the guides: docs/*.md and CHANGELOG.md rendered by MkDocs Material (site/mkdocs.yml)
# Needs the packages in site/requirements.txt. Used by .github/workflows/pages.yml.
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf build/site build/site-docs
mkdir -p build/site build/site-docs

# The guides' sources: our overview page and assets, the docs, and the changelog.
cp -R site/guides/. build/site-docs/
cp docs/*.md build/site-docs/
cp CHANGELOG.md build/site-docs/changelog.md
# On GitHub, docs/ links to the changelog one folder up; on the site it sits next to them.
sed -i.bak 's#](\.\./CHANGELOG\.md)#](changelog.md)#g' build/site-docs/*.md
rm build/site-docs/*.bak

# mkdocs is pinned below 2.0 in requirements.txt, so Material's MkDocs 2.0 warning doesn't apply.
NO_MKDOCS_2_WARNING=true mkdocs build --strict --config-file site/mkdocs.yml --site-dir "$PWD/build/site/docs"
cp site/index.html build/site/
