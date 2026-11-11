# Unreleased changes, one file per pull request

A pull request puts its changelog entry here, as `changes/<branch>.md`: its
branch's name, with each `/` as `-` (`android/batch-56` →
`changes/android-batch-56.md`). It never edits [CHANGELOG.md](../CHANGELOG.md),
so two pull requests never conflict on the same line.

- **The entry** is one bullet, written as CHANGELOG.md's are: what changed for
  the person, which app or service, and the page in [docs/](../docs/README.md).
- **Nothing user-visible** (a pure refactor, a routine doc fix): add the file
  empty.
- **The check** (References, `scripts/changelog.py check`) fails a pull request
  that edits CHANGELOG.md, adds no fragment or more than one, names it for
  another branch, or changes another pull request's fragment. Dependabot's are
  exempt.
- **At a release,** on a branch named `release/<version>`,
  `python3 scripts/changelog.py fold --release "<version>"` moves every
  fragment into CHANGELOG.md, newest first, and removes them (`fold` alone puts
  them under Unreleased). The check lets a `release/` branch edit CHANGELOG.md
  and remove fragments, and nothing else under `changes/`.
