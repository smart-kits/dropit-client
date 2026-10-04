#!/bin/bash
# Raycast Script Command — send a file to dropit
#
# @raycast.schemaVersion 1
# @raycast.title Send File to dropit
# @raycast.mode compact
# @raycast.icon 📎
# @raycast.packageName dropit
# @raycast.argument1 { "type": "text", "placeholder": "File path" }
# Raycast starts scripts with a short PATH: add where the installer and Homebrew put dropit and node
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
dropit send -f "${1/#\~/$HOME}"   # a path typed as ~/… isn't expanded inside quotes
