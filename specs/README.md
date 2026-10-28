# Specifications

The app is developed spec-first for changes to its file formats, its exchange with the cycling
site and what the referee sees, with [Spec Kit](https://github.com/github/spec-kit), whose skills
both coding agents here (Claude Code and Codex) carry.

```
specs/
  <NNN>-<short-name>/         one feature
    spec.md                   what and why, for the referee and the readers of the files
    plan.md, tasks.md         how it will be built, once the spec is reviewed
```

The app as it works today is described by [`README.md`](../README.md) and [`docs/`](../docs);
a feature that changes described behaviour updates them in the same pull request.

The principles every plan is checked against are in
[`.specify/memory/constitution.md`](../.specify/memory/constitution.md).

Rules shared with the desktop chronometer -- the line formats, the start protocol fields and the
events a timing device reports -- are specified once, in ChronometerPython's
`specs/001-event-sources/`, and referred to from here.
