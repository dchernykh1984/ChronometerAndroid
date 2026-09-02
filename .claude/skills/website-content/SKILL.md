---
name: website-content
description: Contribute copy about this app - a description or release news - to the maintainer's cycling website. Use when asked to prepare, publish, or edit site content about the chronometer (news post, app description, feature blurb).
---

# Contributing app copy to the cycling website

The website is a **separate Django project** (repo name `cycling-site`), not part of this
Android repo. It has its **own `CLAUDE.md`, `knowledge/` directory, and apps** (`news`,
`home`, `knowledge`, `protocols`, `calendar_app`, ...). It is the single source of truth
for how site content is modelled, added, and edited - **read the site repo's own
`CLAUDE.md` and `knowledge/` first**, and ask the maintainer for its location if it is
not already open in the workspace. Do not hardcode local paths or duplicate the site's
guidance here.

## What "site content about the app" means here

The maintainer publishes two kinds of copy about the chronometer:

- a **description** (what the app is, who it's for, feature list, how to use it), and
- **release news** (a short announcement when a new version ships).

Workflow that has worked: draft the copy as plain, well-structured **text** (Russian, the
site's audience; the app is also localized ru/kk/en) with clearly separated blocks - a
short headline/lede for the news, a feature list and a step-by-step for the description -
so the maintainer can lift the parts they need. Base every claim on the app's **actual
functionality** (see this repo's `README.md` and `app/src/main/res/values/strings.xml`),
never invented features. Offer other languages only if asked.

Publishing/editing the live site (news entries, pages) happens **in the cycling-site
repo** through its Django conventions - follow that repo's instructions there.
