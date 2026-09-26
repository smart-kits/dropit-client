#!/bin/bash
# Raycast Script Command — send the clipboard to dropit
#
# @raycast.schemaVersion 1
# @raycast.title Send to dropit
# @raycast.mode compact
# @raycast.icon 📥
# @raycast.packageName dropit
# @raycast.description Send the clipboard to dropit
pbpaste | dropit send
