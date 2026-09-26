#!/bin/bash
# Raycast Script Command — send a file to dropit
#
# @raycast.schemaVersion 1
# @raycast.title Send File to dropit
# @raycast.mode compact
# @raycast.icon 📎
# @raycast.packageName dropit
# @raycast.argument1 { "type": "text", "placeholder": "File path" }
dropit send -f "$1"
