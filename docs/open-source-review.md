# Open-source preparation review

Date: 2026-09-26.

## Scope and method

The review covered the main repository and the separate local project working directory. A local pattern scan read 325 UTF-8 text files before new release documents were added. It checked common private-key markers, token formats, quoted secret assignments, email addresses, personal home paths, and MAC addresses. It also checked filenames for environment files and signing-key containers.

Git listed 143 untracked, non-ignored publication candidates at the start. The repository had no commits. Therefore there was no committed history to inspect. The Git candidate list was checked separately from the wider working-directory scan.

Toolchains, dependency caches, build directories, Git internals, compiled app bundles, and symlinks were excluded. Fifty-seven binary, non-UTF-8, or oversized files were not inspected by the text scan. This includes archives and media. No claim is made that their contents or metadata are free of personal information. This was a local pattern review, not a complete security audit or a high-entropy secret scan.

## Findings and handling

| Finding | Result / action |
| --- | --- |
| Common token, private-key, password-assignment patterns | No matches in the scanned text. This is not proof that no credential exists. |
| Signing keys and environment filenames | No matching files in the scanned scope. |
| Personal home paths | 16 matching lines in Flutter generated files and separate working-directory documents. No such path was found in the original Git publication candidates. |
| Generated Flutter files | Already excluded by component ignore rules; root ignore rules now also cover the common generated paths. |
| Raw data, videos, session archives, and backups | Keep local and outside releases. Existing `data/` exclusion retained; added session/export/backup/media exclusions. |
| Signing configuration | Flutter's release configuration uses debug signing. No password was found. Do not advertise that build as a production-signed release. |
| Default icons and model binary | Not cleared by a text scan. Preserve third-party attribution and complete artifact provenance checks. |
| Local macOS README path | Replaced its user-specific interpreter path with a portable `uv --project` example. The local component remains outside the main repository. |

The separate local inventory and design documents still contain internal paths. They are not release documents and must not be copied wholesale into the repository. Only selected, reviewed source files should be imported.

## Changes made

- Replaced the root README with simplified technical English and added an equivalent Simplified Chinese README.
- Added the official Apache-2.0 license, a project NOTICE, and third-party scope notes.
- Added ignore rules for secrets, recordings, archives, and generated outputs.
- Corrected the Android README's old detector name and added a current-version note.

## Source release closure

The initial source-release checks are complete; see [the final checklist](source-release-check.md).
The model license issue was resolved by the approved replacement. Published icon
metadata and bundled archives were reviewed, and upstream Flutter licenses were
preserved. The final publication scope is documented in [RELEASE.md](../RELEASE.md).

Future native Mac/animation/enclosure imports and compiled app/firmware releases
remain separate work. They are not included in this initial source release.

The initial source commit is prepared after these checks. No remote publication or deletion of private data is part of this step. Application inference code was not changed. A subsequent approved model replacement was built and tested on a Pixel 9 Pro; see the linked validation report. Firmware was not changed or retested.
