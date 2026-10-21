"""MkDocs hook: search-friendly titles and descriptions for the guides.

They live here rather than as front matter, so the Markdown files in docs/ stay plain on GitHub.
Keys are the file names in build/site-docs (see build.sh).
"""

PAGES = {
    "index.md": (
        "Guides for Meshtastic teams on OsmAnd",
        "Guides for MeshAnd, the Android app that shows your Meshtastic team live on the OsmAnd "
        "map: radio setup, release notes and building the app.",
    ),
    "radio-setup.md": (
        "Meshtastic radio setup for live team tracking",
        "Channel, role and position settings that make Meshtastic teammates move smoothly on the "
        "map: a private channel, faster position updates and the Client role.",
    ),
    "changelog.md": (
        "Release notes",
        "What changed in each version of MeshAnd, the Android app that shows your Meshtastic team "
        "on the OsmAnd map.",
    ),
    "development.md": (
        "Building MeshAnd from source",
        "Build MeshAnd with Android Studio, see how the code is organised, read the logs, publish "
        "a release and debug Bluetooth.",
    ),
}


def on_page_markdown(markdown, page, **kwargs):
    title, description = PAGES.get(page.file.src_uri, (None, None))
    if title:
        page.meta.setdefault("title", title)
    if description:
        page.meta.setdefault("description", description)
    return markdown
