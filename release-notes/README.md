# Release notes

Optional notes for a tagged build. Pushing the tag is enough; `versionName`
and `versionCode` in Gradle are not edited by hand.

When a `v*` tag is published, the GitHub Release body starts with
`release-notes/<tag>.md` from that commit when the file exists. The name
includes the leading `v`, for example `release-notes/v5.1.3-dojobay.5.md`.
GitHub's generated notes (pull requests and issues since the previous tag)
follow that file.

A tag with a pre-release suffix — a hyphen after the version numbers,
including `-dojobay.` — is published as a pre-release. A tag with no suffix
is a full release.

The signed APK and `SHA256SUMS.txt` are attached to that release.
