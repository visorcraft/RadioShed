#!/usr/bin/env bash
# Publish a stable RadioShed AppImage to the AUR package `radioshed`.
#
# The package downloads the GitHub release asset. This script refuses to
# push the AUR repo until that asset is anonymously downloadable and its
# SHA-256 matches the release. A private repository returns 404 here.
#
# Prerequisites: gh, curl, sha256sum, makepkg, git, ssh.
# Required env:
#   VERSION          e.g. 1.6.1 (no leading v)
#
# Optional env:
#   REPO_ROOT        repository root (default: parent of this script's dir)
#   SKIP_AUR_PUSH    if 1, update the AUR checkout but do not push
#   AUR_DIR          existing AUR checkout; otherwise clone into a temp dir
#   DRY_RUN          if 1, stop after the public asset is verified
set -euo pipefail

VERSION="${VERSION:?VERSION is required (e.g. 1.6.1)}"
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "VERSION must match N.N.N (got: ${VERSION})" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${REPO_ROOT:-${SCRIPT_DIR}/..}" && pwd)"
ASSET="RadioShed-${VERSION}-x86_64.AppImage"
TAG="v${VERSION}"
PUBLIC_URL="https://github.com/visorcraft/RadioShed/releases/download/${TAG}/${ASSET}"

release_dir="$(mktemp -d /tmp/radioshed-aur-release-XXXXXXXX)"
AUR_DIR_OWNED=""
cleanup() {
    rm -rf "${release_dir}"
    if [[ -n "${AUR_DIR_OWNED}" && -d "${AUR_DIR_OWNED}" ]]; then
        rm -rf "${AUR_DIR_OWNED}"
    fi
}
trap cleanup EXIT

echo "Selecting stable GitHub release ${TAG}..."
release_json="$(gh release view "${TAG}" --repo visorcraft/RadioShed --json tagName,isDraft,isPrerelease)"
tag_name="$(printf '%s' "${release_json}" | sed -n 's/.*"tagName"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
is_draft="$(printf '%s' "${release_json}" | sed -n 's/.*"isDraft"[[:space:]]*:[[:space:]]*\([a-z]*\).*/\1/p')"
is_pre="$(printf '%s' "${release_json}" | sed -n 's/.*"isPrerelease"[[:space:]]*:[[:space:]]*\([a-z]*\).*/\1/p')"
if [[ "${tag_name}" != "${TAG}" || "${is_draft}" != "false" || "${is_pre}" != "false" ]]; then
    echo "Release ${TAG} must exist, not be a draft, and not be a prerelease." >&2
    printf '%s\n' "${release_json}" >&2
    exit 1
fi

echo "Downloading ${ASSET}..."
gh release download "${TAG}" --repo visorcraft/RadioShed --pattern "${ASSET}" --dir "${release_dir}"
if [[ ! -f "${release_dir}/${ASSET}" ]]; then
    echo "GitHub release ${TAG} is missing ${ASSET}." >&2
    exit 1
fi
sha256="$(sha256sum "${release_dir}/${ASSET}" | awk '{print $1}')"
printf 'Local SHA-256: %s  %s\n' "${sha256}" "${ASSET}"

echo "Checking anonymous download ${PUBLIC_URL}..."
public_status="$(curl -sS -o /dev/null -w '%{http_code}' -L -I "${PUBLIC_URL}" || true)"
if [[ "${public_status}" != "200" ]]; then
    echo "Anonymous download returned HTTP ${public_status}." >&2
    echo "The repository must be public, and ${ASSET} must be on release ${TAG}, before the AUR package can ship." >&2
    exit 1
fi
curl -fsSL "${PUBLIC_URL}" -o "${release_dir}/public-${ASSET}"
public_sha="$(sha256sum "${release_dir}/public-${ASSET}" | awk '{print $1}')"
if [[ "${public_sha}" != "${sha256}" ]]; then
    echo "Public AppImage SHA-256 does not match the GitHub release." >&2
    echo "local=${sha256} public=${public_sha}" >&2
    exit 1
fi
echo "Public hash OK."

if [[ "${DRY_RUN:-0}" == "1" ]]; then
    echo "DRY_RUN=1; stopping before AUR mutation."
    exit 0
fi

if [[ -n "${AUR_DIR:-}" ]]; then
    aur_dir="${AUR_DIR}"
    if [[ ! -d "${aur_dir}/.git" ]]; then
        echo "AUR_DIR must be a git checkout: ${aur_dir}" >&2
        exit 1
    fi
else
    aur_dir="$(mktemp -d /tmp/radioshed-aur-XXXXXXXX)"
    AUR_DIR_OWNED="${aur_dir}"
    echo "Cloning AUR package repository..."
    git clone ssh://aur@aur.archlinux.org/radioshed.git "${aur_dir}"
fi

cp "${REPO_ROOT}/packaging/aur/PKGBUILD" "${aur_dir}/PKGBUILD"
cp "${REPO_ROOT}/LICENSE" "${aur_dir}/LICENSE"
cd "${aur_dir}"

if grep -q 'pkgname=radioshed-bin' PKGBUILD; then
    echo "Refusing to publish package name radioshed-bin." >&2
    exit 1
fi
if ! grep -q '^pkgname=radioshed$' PKGBUILD; then
    echo "PKGBUILD must set pkgname=radioshed." >&2
    exit 1
fi

sed -i -E \
    -e "s/^pkgver=.*/pkgver=${VERSION}/" \
    -e "s/^pkgrel=.*/pkgrel=1/" \
    -e "s/^sha256sums_x86_64=\\('[0-9a-f]{64}'\\)/sha256sums_x86_64=('${sha256}')/" \
    PKGBUILD

if ! grep -qE "^pkgver=${VERSION}$" PKGBUILD; then
    echo "Failed to set pkgver=${VERSION} in PKGBUILD." >&2
    exit 1
fi
if ! grep -q "${sha256}" PKGBUILD; then
    echo "Failed to embed AppImage SHA-256 in PKGBUILD." >&2
    exit 1
fi
if ! grep -q 'github.com/visorcraft/RadioShed/releases/download/' PKGBUILD; then
    echo "PKGBUILD sources must use the GitHub release asset." >&2
    exit 1
fi

license_sha="$(sha256sum LICENSE | awk '{print $1}')"
if ! grep -q "${license_sha}" PKGBUILD; then
    echo "PKGBUILD LICENSE checksum does not match repo LICENSE." >&2
    exit 1
fi

makepkg --printsrcinfo > .SRCINFO
echo "Running makepkg --verifysource..."
makepkg --verifysource
echo "Building package..."
makepkg --force --noconfirm
pkg_archive="radioshed-${VERSION}-1-x86_64.pkg.tar.zst"
if [[ ! -f "${pkg_archive}" ]]; then
    echo "Expected package archive ${pkg_archive} was not produced." >&2
    exit 1
fi

git config user.name visorcraft
git config user.email thomas@visorcraft.com
git add PKGBUILD .SRCINFO LICENSE
if git diff --cached --quiet; then
    echo "PKGBUILD/.SRCINFO already match ${VERSION}; nothing to commit."
else
    git commit -m "Publish RadioShed ${VERSION}"
fi

cp PKGBUILD .SRCINFO "${REPO_ROOT}/packaging/aur/"

if [[ "${SKIP_AUR_PUSH:-0}" == "1" ]]; then
    echo "SKIP_AUR_PUSH=1; not pushing AUR master."
    exit 0
fi

echo "Pushing AUR master..."
git branch -M master
git push -u origin master
echo "AUR publish complete for RadioShed ${VERSION}."
echo "Package page: https://aur.archlinux.org/packages/radioshed"
