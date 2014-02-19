#!/usr/bin/env bash

set -e
set -x

git fetch --tags

lein test
echo "Tests passed!"

lein release
echo "Release plugin successful, pushing changes to git"

git push origin --tags HEAD:$TRAPPERKEEPER_BRANCH

echo "git push successful."
