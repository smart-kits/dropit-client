#!/bin/bash
# Raycast Script Command — send the clipboard to dropit
#
# @raycast.schemaVersion 1
# @raycast.title 投进 dropit
# @raycast.mode compact
# @raycast.icon 📥
# @raycast.packageName dropit
# @raycast.description 把剪贴板内容投进 dropit
pbpaste | dropit send
