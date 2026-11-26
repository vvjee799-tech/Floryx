#!/bin/sh
set -u
branch="v$(cut -d. -f1,2 /etc/alpine-release)"
temporary="$(mktemp -d /tmp/flowchat-tools.XXXXXX)" || exit 1
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
for mirror in \
    https://dl-cdn.alpinelinux.org/alpine \
    https://mirrors.tuna.tsinghua.edu.cn/alpine \
    https://mirrors.ustc.edu.cn/alpine
do
    printf '\nInstalling development tools from %s\n' "$mirror"
    printf '%s/%s/main\n%s/%s/community\n' "$mirror" "$branch" "$mirror" "$branch" > "$temporary/repositories"
    # APK still verifies the Alpine repository/package signatures with the bundled distribution keys.
    # Use a temporary repository file so a failed attempt never replaces the user's own sources.
    if timeout -s KILL 120 apk --timeout 20 --repositories-file "$temporary/repositories" add --no-cache python3 py3-pip nodejs npm git curl bash; then
        python3 --version
        node --version
        npm --version
        git --version
        exit 0
    fi
    printf 'Repository attempt failed; trying the next mirror.\n'
done
printf 'Could not install development tools. Check the network and run flowchat-install-tools again.\n' >&2
exit 1
