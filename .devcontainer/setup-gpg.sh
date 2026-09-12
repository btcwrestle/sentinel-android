#!/usr/bin/env bash
if [ -n "${GPG_PRIVATE_KEY:-}" ]; then
  printf '%s' "$GPG_PRIVATE_KEY" | base64 -d | gpg --batch --import
  KEYID=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec/{print $5; exit}')
  git config --global gpg.program gpg
  git config --global user.signingkey "$KEYID"
  git config --global commit.gpgsign true
  git config --global user.email "$(gh api user --jq '"\(.id)+\(.login)@users.noreply.github.com"')"
  cat >> ~/.gnupg/gpg-agent.conf <<'EOF'
default-cache-ttl 3600
max-cache-ttl 7200
EOF
  gpg-connect-agent reloadagent /bye
  grep -q "GPG_TTY" ~/.bashrc 2>/dev/null || echo "export GPG_TTY=$(tty)" >> ~/.bashrc
  echo "GPG: signing enabled with $KEYID"
fi
