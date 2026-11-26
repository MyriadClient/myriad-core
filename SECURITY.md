# Security

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub's
[security advisories](https://github.com/MyriadClient/myriad-core/security/advisories/new) for this repository. We'll
answer within a few days and credit you in the fix unless you'd rather we didn't.

## How updates are protected

Myriad's code is public, so none of this relies on anything being hidden: it rests on signatures and on checks that
fail closed.

- **Myriad's own updates are signed offline.** CI builds and attests each release, but holds no signing key. The
  maintainer rebuilds the tag, checks the result is byte for byte the released jar, and only then signs it with one of
  Myriad's two root keys, kept offline (`tools/sign-release`). Core takes an update only with a valid root signature,
  so a compromised CI, GitHub account or release page can't push code to players.
- **Addons that list keys take only updates signed with them.** The signature covers the addon's id, the version and
  the jar's exact size and SHA-256 (`myriad-addon-release:1`), so it can't be replayed for another addon, an older
  version or a changed jar. Keys change only through a release signed with an old key.
- **Every download is checked before it's staged**: size and SHA-256 against what the release listed, the signature,
  and the jar read through its central directory as the loader reads it. Jars that could read differently to another
  tool (duplicate entries, mismatched headers, odd names, ZIP64, encryption), or that bring in another Myriad addon,
  are refused.
- **Downloads** are https only, capped at the size they were listed with, followed through redirects one hop at a time,
  and cut off after five minutes. A private source's token goes only to that source's own origin.
- **Installing from a link** shows the addon's source and key fingerprint, or a warning when it's unsigned, and writes
  nothing until the player agrees. A first install trusts the key it comes with; later updates need that key.

## What this doesn't cover

- Code already running on the player's machine: it can change the mods folder directly.
- An addon whose developer means harm. A signature proves who released a jar, not that it's safe.
- An addon that lists no keys: it's only as safe as where it's released, which its card says.
