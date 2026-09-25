#!/bin/bash
# Raycast Script Command — send a file to dropit
#
# @raycast.schemaVersion 1
# @raycast.title 投文件进 dropit
# @raycast.mode compact
# @raycast.icon 📎
# @raycast.packageName dropit
# @raycast.argument1 { "type": "text", "placeholder": "文件路径" }
dropit send -f "$1"
