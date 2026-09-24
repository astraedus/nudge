#!/usr/bin/env python3
"""Scrub a UI hierarchy dump so it can be committed to this PUBLIC repository.

A raw hierarchy dump of a social app is a transcript of one real person's feed: usernames,
captions, story labels, ad copy, who liked what. None of that is structure, none of it is anything
Nudge reasons about, and all of it would be published forever the moment the file is committed.
This is the same invariant `A11yCapturePrivacyTest` holds over the accessibility captures, applied
to the other kind of device fixture this repo keeps.

THE SCRUBBING RULE
------------------
Structure is kept byte-for-byte: `class`, `resource-id`, `bounds`, `clickable`, `selected`,
`index`, `package` and every other attribute pass through untouched.

Content is destroyed: EVERY `text` and `content-desc` value is replaced with the empty string,
except for an explicit allowlist of the strings the tab-vanish / following-steer feature actually
reasons about. The allowlist is a closed set of Instagram's own UI chrome labels -- not user data --
and it is the ONLY route by which any string survives. Allowing by exception rather than denying by
pattern is deliberate: a denylist has to anticipate what a caption looks like, and it will be wrong
the first time someone's username is "Following".

Usage:
    scripts/scrub-surface-fixture.py <src.xml> [<src.xml> ...] --out <dir>
"""

import argparse
import pathlib
import re
import sys

# The ONLY strings allowed to survive scrubbing. Each is Instagram's own chrome, and each is read by
# production code: the five bottom-nav tab content-descriptions, the Home-feed logo description, and
# the two title-dropdown menu labels. Anything not on this list is user content by default.
ALLOWLIST = frozenset(
    {
        "Following",
        "Favorites",
        "Instagram Home Feed",
        "Home",
        "Reels",
        "Message",
        "Search and explore",
        "Profile",
    }
)

# Matches one `text="..."` or `content-desc="..."` attribute. Both are unique attribute spellings in
# a hierarchy dump (`long-clickable` is the only near-miss and it carries no text), so a plain
# attribute match is exact here.
ATTR = re.compile(r'(?P<attr>\stext|\scontent-desc)="(?P<value>[^"]*)"')


def scrub(xml: str) -> str:
    def replace(match: re.Match) -> str:
        value = match.group("value")
        kept = value if value in ALLOWLIST else ""
        return f'{match.group("attr")}="{kept}"'

    return ATTR.sub(replace, xml)


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sources", nargs="+", type=pathlib.Path)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)

    args.out.mkdir(parents=True, exist_ok=True)
    for source in args.sources:
        scrubbed = scrub(source.read_text(encoding="utf-8"))
        destination = args.out / source.name
        destination.write_text(scrubbed, encoding="utf-8")
        survivors = sorted({m.group("value") for m in ATTR.finditer(scrubbed) if m.group("value")})
        print(f"{source} -> {destination}  kept: {survivors}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
