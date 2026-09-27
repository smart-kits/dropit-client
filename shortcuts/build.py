#!/usr/bin/env python3
"""Build the dropit shortcut for iPhone / iPad.

    python3 shortcuts/build.py [--api https://dropit.smart-kits.xyz] [--out shortcuts/dropit.shortcut]

Writes an unsigned shortcut, then signs it with macOS `shortcuts sign --mode anyone`
so it can be installed with one tap. Signing needs macOS 12+ signed in to iCloud.

One shortcut does both jobs:

* Input starting with `dropit-token:` is a pairing handoff from the web inbox
  (`shortcuts://run-shortcut?name=dropit&input=text&text=dropit-token:…`).
  The token is saved to iCloud Drive/Shortcuts/dropit-token.txt and nothing is sent.
* Anything else is sent. With no input (Home Screen, Back Tap) it sends the clipboard.

Success is a short vibration. Failure is a notification carrying the server's error code —
the response is checked for `seq`, which success and same-day duplicates both carry.
"""

import argparse
import plistlib
import subprocess
import sys
import uuid

TOKEN_FILE = 'dropit-token.txt'
PAIR_PREFIX = 'dropit-token:'
OBJ = '￼'                          # placeholder character Shortcuts uses for an inline variable


def new_id():
    return str(uuid.uuid4()).upper()


# ── Variable references ──────────────────────────────────────────────────

def ref_input():
    return {'Type': 'ExtensionInput'}


def ref_output(uid, name):
    return {'Type': 'ActionOutput', 'OutputUUID': uid, 'OutputName': name}


def ref_var(name):
    return {'Type': 'Variable', 'VariableName': name}


def attachment(ref):
    return {'Value': ref, 'WFSerializationType': 'WFTextTokenAttachment'}


def text(*parts):
    """A text field made of literal strings and variable references, in order."""
    out, ranges = '', {}
    for part in parts:
        if isinstance(part, str):
            out += part
        else:
            ranges[f'{{{len(out)}, 1}}'] = part
            out += OBJ
    return {'Value': {'string': out, 'attachmentsByRange': ranges}, 'WFSerializationType': 'WFTextTokenString'}


def dictionary(pairs):
    items = [{'WFItemType': 0, 'WFKey': text(k), 'WFValue': text(*v) if isinstance(v, tuple) else text(v)}
             for k, v in pairs]
    return {'Value': {'WFDictionaryFieldValueItems': items}, 'WFSerializationType': 'WFDictionaryFieldValue'}


def condition_input(ref):
    return {'Type': 'Variable', 'Variable': attachment(ref)}


# ── Actions ──────────────────────────────────────────────────────────────

def action(ident, **params):
    return {'WFWorkflowActionIdentifier': f'is.workflow.actions.{ident}', 'WFWorkflowActionParameters': params}


BEGINS_WITH, HAS_VALUE, HAS_NO_VALUE = 8, 100, 101


def if_block(ref, condition, then, otherwise=None, string=None):
    group = new_id()
    start = {'GroupingIdentifier': group, 'WFControlFlowMode': 0,
             'WFCondition': condition, 'WFInput': condition_input(ref)}
    if string is not None:
        start['WFConditionalActionString'] = string
    steps = [action('conditional', **start), *then]
    if otherwise is not None:
        steps += [action('conditional', GroupingIdentifier=group, WFControlFlowMode=1), *otherwise]
    steps.append(action('conditional', GroupingIdentifier=group, WFControlFlowMode=2))
    return steps


def notify(*body):
    return action('notification', WFNotificationActionTitle='dropit', WFNotificationActionBody=text(*body),
                  WFNotificationActionSound=True)


def build(api):
    token_from_input, get_file, clipboard, post, seq, err = (new_id() for _ in range(6))
    actions = [
        # 1 · Pairing handoff from the web inbox: save the token, send nothing.
        *if_block(ref_input(), BEGINS_WITH, string=PAIR_PREFIX, then=[
            action('text.replace', UUID=token_from_input, WFInput=text(ref_input()),
                   WFReplaceTextFind=PAIR_PREFIX, WFReplaceTextReplace=''),
            action('documentpicker.save', WFInput=attachment(ref_output(token_from_input, 'Updated Text')),
                   WFAskWhereToSave=False, WFFileDestinationPath=TOKEN_FILE, WFSaveFileOverwrite=True),
            notify('Paired — share anything to dropit to send it. · 配对完成，在分享面板选 dropit 就能投。'),
            action('exit'),
        ]),
        # 2 · Load the token.
        action('documentpicker.open', UUID=get_file, WFGetFilePath=TOKEN_FILE,
               WFFileErrorIfNotFound=False, WFShowFilePicker=False),
        *if_block(ref_output(get_file, 'File'), HAS_NO_VALUE, then=[
            notify('Not paired yet: open dropit.smart-kits.xyz on this device. · 还没配对：在这台设备上打开 dropit.smart-kits.xyz。'),
            action('exit'),
        ]),
        # 3 · What to send: the shared item, or the clipboard when there is none.
        action('setvariable', WFVariableName='raw', WFInput=attachment(ref_input())),
        *if_block(ref_input(), HAS_NO_VALUE, then=[
            action('getclipboard', UUID=clipboard),
            action('setvariable', WFVariableName='raw', WFInput=attachment(ref_output(clipboard, 'Clipboard'))),
        ]),
        # 4 · Send. No choices, no confirmation.
        action('downloadurl', UUID=post, WFURL=f'{api}/v1/ingest', WFHTTPMethod='POST', WFHTTPBodyType='JSON',
               ShowHeaders=True,
               WFHTTPHeaders=dictionary([('Authorization', ('Bearer ', ref_output(get_file, 'File')))]),
               WFJSONValues=dictionary([('kind', 'text'), ('raw', (ref_var('raw'),)), ('source', 'ios-shortcut')])),
        # 5 · Success carries `seq` (a same-day duplicate does too). Anything else is a loud failure.
        action('getvalueforkey', UUID=seq, WFInput=attachment(ref_output(post, 'Contents of URL')),
               WFGetDictionaryValueType='Value', WFDictionaryKey='seq'),
        *if_block(ref_output(seq, 'Dictionary Value'), HAS_VALUE, then=[
            action('vibrate'),
        ], otherwise=[
            action('getvalueforkey', UUID=err, WFInput=attachment(ref_output(post, 'Contents of URL')),
                   WFGetDictionaryValueType='Value', WFDictionaryKey='error'),
            notify('Send failed · 投递失败: ', ref_output(err, 'Dictionary Value')),
        ]),
    ]
    return {
        'WFWorkflowActions': actions,
        'WFWorkflowClientVersion': '2605.0.5',
        'WFWorkflowMinimumClientVersion': 900,
        'WFWorkflowMinimumClientVersionString': '900',
        'WFWorkflowIcon': {'WFWorkflowIconStartColor': 4282601983, 'WFWorkflowIconGlyphNumber': 59446},
        'WFWorkflowImportQuestions': [],
        'WFWorkflowTypes': ['ActionExtension'],
        'WFWorkflowInputContentItemClasses': ['WFURLContentItem', 'WFStringContentItem', 'WFRichTextContentItem'],
        'WFWorkflowOutputContentItemClasses': [],
        'WFWorkflowHasShortcutInputVariables': True,
        'WFQuickActionSurfaces': [],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--api', default='https://dropit.smart-kits.xyz')
    ap.add_argument('--out', default='shortcuts/dropit.shortcut')
    args = ap.parse_args()
    unsigned = args.out.replace('.shortcut', '.unsigned.shortcut')
    with open(unsigned, 'wb') as f:
        plistlib.dump(build(args.api.rstrip('/')), f, fmt=plistlib.FMT_BINARY)
    done = subprocess.run(['shortcuts', 'sign', '--mode', 'anyone', '--input', unsigned, '--output', args.out])
    if done.returncode:
        sys.exit(f'signing failed; the unsigned file is at {unsigned}')
    print(args.out)


if __name__ == '__main__':
    main()
