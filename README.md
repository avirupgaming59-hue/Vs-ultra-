# VS Ultra Private

**Private canonical workspace for the VS Ultra project.**

This repository is intended to hold the complete private application source, project-owned build/runtime configuration, AI implementation, internal services, and security-sensitive development materials.

## Repository security

- Visibility must remain **Private**.
- The repository owner is the only human who should be granted access unless the owner explicitly adds someone.
- AI agents must be granted access separately, with least-privilege repository-scoped permissions. Being connected to the public repository does not grant access here.
- Never place private source, credentials, signing material, user data, or private build artifacts in a public repository.
- Do not mirror this repository's Git history, branches, tags, or complete source tree into any public repository.

## Relationship to the public repository

The intended public repository is a separate, independent repository containing only owner-approved documentation, standalone UI demonstrations, and safe examples.

When that public repository exists, it can be referenced here as a Git submodule or other read-only dependency. A submodule stores a pointer to a specific public commit; it does **not** copy all public files into this repository's normal working tree. Cloning this repository with submodules enabled retrieves the referenced public content separately.

A submodule reference does not make a public repository private. Anyone can still browse or clone the public repository directly. It also does not grant any AI agent access to this private repository.

## Current setup status

- Private repository identified: `avirupgaming59-hue/Vs-ultra-private-`
- Default branch: `main`
- Private visibility: confirmed before initialization.
- Public companion repository: not found in the connected GitHub account at setup time.
- Public submodule: not configured because the public companion repository does not yet exist.
- Source files: not transferred in this initial setup commit.

## Owner-controlled next steps

1. Confirm the intended public repository exists and its exact repository name.
2. Review its complete file list and history before linking it.
3. Add the public repository as a submodule only if the owner wants a visible, pinned reference.
4. Transfer the private application source into this repository through a reviewed, complete file/tree migration if this repository is to become the canonical private source.
5. Verify repository visibility and AI permissions after migration.
