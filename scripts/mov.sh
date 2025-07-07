#!/usr/bin/env sh

set -e

toDir=$1
from=$2

if [ ! -f "data/$from.mapping" ]; then
  echo -e "\e[0;91mFile not found"
  exit
fi

read -p "Do you want to move $from to $toDir ? (y/n) " answer
if [ "$answer" = "n" ]; then
  exit
fi

name=${from##*/}

mkdir --parents data/$toDir;
mv -i "data/$from.mapping" "data/$toDir"
grep -rl $from data | xargs sed -i "s,$from,$toDir/$name,g"

echo "Done!"