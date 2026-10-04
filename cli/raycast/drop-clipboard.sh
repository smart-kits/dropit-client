#!/bin/bash
# Raycast Script Command — send the clipboard to dropit
#
# @raycast.schemaVersion 1
# @raycast.title Send to dropit
# @raycast.mode compact
# @raycast.icon 📥
# @raycast.packageName dropit
# @raycast.description Send the clipboard to dropit
# Raycast starts scripts with a short PATH: add where the installer and Homebrew put dropit and node
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
pbpaste | dropit send
