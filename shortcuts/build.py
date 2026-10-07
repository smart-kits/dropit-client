#!/usr/bin/env python3
"""Build the dropit shortcut for iPhone / iPad.

    python3 shortcuts/build.py [--api https://dropit.smart-kits.xyz] [--out shortcuts/dropit.shortcut]

Writes an unsigned shortcut, then signs it with macOS `shortcuts sign --mode anyone`
so it can be installed with one tap. Signing needs macOS 12+ signed in to iCloud.

One shortcut does all of it:

* Input starting with `dropit-token:` is a pairing handoff from the web inbox
  (`shortcuts://run-shortcut?name=dropit&input=text&text=dropit-token:…`).
  The token is saved to iCloud Drive/Shortcuts/dropit-token.txt and nothing is sent.
  If the shortcut was already paired, it asks before replacing the token — a web page can open
  that link too, and silently swapping accounts would send everything after it to someone else.
* Photos, videos, files, PDFs: each is uploaded in one request (`POST /v1/ingest/file`). Several
  shared at once become one batch, shown together on the other devices.
* Anything else — text, links, notes — is sent as text.
* Run with no input at all (Home Screen, Back Tap): it sends the clipboard. "No input" means
  nothing was shared, counted as items: a PDF turned into text is empty, and must not fall back to
  the clipboard.

Success is a short vibration. Failure is a notification carrying the reason.

What the share sheet hands over was measured on a device (iOS 26, four rounds, 30+ shares):
photos and videos arrive as "Photo Media", Files-app items as "File" or "PDF", a .json file as
"Dictionary" (dropped unless that type is accepted — the shortcut then ran with nothing).
Every upload carried Content-Length; names come without extensions (IMG_1587), a PDF's is empty
and a JSON file's "name" is its whole content, so a name is sent only for photos and ordinary files.
Every check uses actions proven on a device: plain Replace Text, and an If on a Text action's output.
"""

import argparse
import plistlib
import subprocess
import sys
import uuid

VERSION = '1.0.0'                  # the signed dropit.shortcut is released as shortcut-<VERSION> on GitHub; bump when it's rebuilt
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



def as_text(uid, ref):
    """A Text action holding `ref`. Every If tests one of these, never the input or a variable:
    on iOS 26 an If whose input is the Shortcut Input or a named variable fails with
    "choose a value for each parameter" (ConditionalAction code 1), while an If on a Text
    action's output runs. Verified on a device, iOS 26.6.1."""
    return action('gettext', UUID=uid, WFTextActionText=text(ref))


# Types uploaded as files; everything else is sent as text. Unsure goes to text: at worst a link or a
# name arrives as text, where a web page sent "as a file" would be downloaded and uploaded.
# Tested by deleting these words from the type(s) and seeing whether anything is left, with plain
# Replace Text, which works on a device. (A regex version sent every photo as text on iOS 26, 09-30.)
FILE_TYPES = ['Photo Media', 'Image', 'File', 'PDF', 'Dictionary', 'Media', 'Contact', '\n', ' ']
# Only these carry a usable name (IMG_1587, sample-formulas). A PDF's is empty and a JSON file's
# "name" is its whole content, so those go without one and the server names them.
NAMED_TYPES = ['Photo Media', 'File', 'Image', '\n', ' ']

EMPTY = '<dropit-empty>'


def if_empty(ref, then, otherwise=None):
    """If `ref` is empty text. "Does not have any value" can't tell: on a device (iOS 27, 09-30) an empty
    Text action still has a value, and every photo was sent as text. So add a marker and test
    "begins with" it (the check pairing has always used), which is true only when nothing came before."""
    probe = new_id()
    return [action('gettext', UUID=probe, WFTextActionText=text(ref, EMPTY)),
            *if_block(ref_output(probe, 'Text'), BEGINS_WITH, string=EMPTY, then=then, otherwise=otherwise)]


def strip_words(result, ref, words):
    """Delete each word from `ref` in turn; `result` is a Text action holding what is left. An If tests
    that: on iOS 26 an If only works on a Text action's output."""
    steps, current = [], ref
    for word in words:
        uid = new_id()
        steps.append(action('text.replace', UUID=uid, WFInput=text(current), WFReplaceTextFind=word, WFReplaceTextReplace=''))
        current = ref_output(uid, 'Updated Text')
    return [*steps, as_text(result, current)]


# What the shortcut says, in English unless the phone is set to Chinese. Shortcuts can't read the
# system language, and its requests say Accept-Language: en-US even on a Chinese phone (09-30, seen in
# the server log) — but a date formatted as the weekday comes out in the phone's language: "星期二".
# If that fails too, the English is what shows, which is the default anyway.
STRINGS = {
    'en': {
        'paired': 'Paired. Share anything to dropit to send it.',
        'replace': 'This shortcut is already paired. Replace it with this pairing?',
        'not_paired': 'Not paired yet. Open dropit.smart-kits.xyz on this device.',
        'sent': 'sent', 'sent:Text': 'Text sent', 'sent:URL': 'Link sent', 'sent:Safari web page': 'Link sent',
        'repeat': 'Already sent a moment ago, so not sent again.',
        'failed': "Couldn't send", 'some_failed': "Some didn't go:",
        'offline': "Couldn't reach dropit, or the file is over 100 MB.",
        'label:Photo Media': 'photo/video', 'label:Image': 'image', 'label:PDF': 'PDF', 'label:File': 'file',
        'label:Dictionary': 'file', 'label:Contact': 'contact', 'label:': 'item',
        'error:PAYLOAD_TOO_LARGE': 'Too large for your plan.', 'error:QUOTA_EXCEEDED': 'Storage is full.',
        'error:RATE_LIMITED': 'Sending too fast. Try again in a moment.', 'error:INVALID_TOKEN': 'Pair again.',
        'error:DEVICE_REVOKED': 'This device was removed. Pair again.', 'error:INVALID_REQUEST': 'Nothing usable to send.',
        'error:GLOBAL_CIRCUIT_OPEN': 'dropit is busy. Try again later.',
    },
    'zh': {
        'paired': '配对完成。在分享面板里选 dropit 就能发送。',
        'replace': '已经配过对了，换成这次的配对吗？',
        'not_paired': '还没配对：在这台设备上打开 dropit.smart-kits.xyz。',
        'sent': '已发送', 'sent:Text': '已发送文字', 'sent:URL': '已发送链接', 'sent:Safari web page': '已发送链接',
        'repeat': '刚才已经发过这条，没有重复发送。',
        'failed': '发送失败', 'some_failed': '有的没发出去：',
        'offline': '连不上 dropit，或文件超过 100 MB。',
        'label:Photo Media': '张照片/视频', 'label:Image': '张图片', 'label:PDF': '个 PDF', 'label:File': '个文件',
        'label:Dictionary': '个文件', 'label:Contact': '个联系人', 'label:': '项',
        'error:PAYLOAD_TOO_LARGE': '超过了单条大小上限。', 'error:QUOTA_EXCEEDED': '空间满了。',
        'error:RATE_LIMITED': '发得太快了，稍后再试。', 'error:INVALID_TOKEN': '需要重新配对。',
        'error:DEVICE_REVOKED': '这台设备已被移除，需要重新配对。', 'error:INVALID_REQUEST': '没有可发送的内容。',
        'error:GLOBAL_CIRCUIT_OPEN': 'dropit 现在比较忙，稍后再试。',
    },
}
assert STRINGS['en'].keys() == STRINGS['zh'].keys()


def say(result, *key):
    """`result`: a Text action holding the string for `key` in the phone's language ('' when there's none)."""
    uid = new_id()
    return [action('getvalueforkey', UUID=uid, WFInput=attachment(ref_var('L')), WFGetDictionaryValueType='Value',
                   WFDictionaryKey=text(*key)),
            as_text(result, ref_output(uid, 'Dictionary Value'))]


def notice(*body, sound=True):
    return action('notification', WFNotificationActionTitle='dropit', WFNotificationActionBody=text(*body),
                  WFNotificationActionSound=sound)


def pick_language():
    """Set L to this phone's strings and `lang` to en or zh."""
    now, weekday, weekday_text, en, zh, en_name, zh_name = (new_id() for _ in range(7))
    return [
        action('date', UUID=now, WFDateActionMode='Current Date'),
        action('format.date', UUID=weekday, WFDate=text(ref_output(now, 'Date')), WFDateFormatStyle='Custom', WFDateFormat='EEEE'),
        as_text(weekday_text, ref_output(weekday, 'Formatted Date')),
        *if_block(ref_output(weekday_text, 'Text'), BEGINS_WITH, string='星期', then=[
            action('dictionary', UUID=zh, WFItems=dictionary(list(STRINGS['zh'].items()))),
            action('setvariable', WFVariableName='L', WFInput=attachment(ref_output(zh, 'Dictionary'))),
            action('gettext', UUID=zh_name, WFTextActionText=text('zh')),
            action('setvariable', WFVariableName='lang', WFInput=attachment(ref_output(zh_name, 'Text'))),
        ], otherwise=[
            action('dictionary', UUID=en, WFItems=dictionary(list(STRINGS['en'].items()))),
            action('setvariable', WFVariableName='L', WFInput=attachment(ref_output(en, 'Dictionary'))),
            action('gettext', UUID=en_name, WFTextActionText=text('en')),
            action('setvariable', WFVariableName='lang', WFInput=attachment(ref_output(en_name, 'Text'))),
        ]),
    ]


def failure(err_text):
    """Say why a send failed: the error's own words, or that dropit couldn't be reached."""
    msg, failed, offline = new_id(), new_id(), new_id()
    return [
        *say(msg, 'error:', ref_output(err_text, 'Text')),
        *say(failed, 'failed'),
        *if_empty(ref_output(msg, 'Text'), then=[
            *say(offline, 'offline'),
            notice(ref_output(failed, 'Text'), ': ', ref_output(offline, 'Text')),
        ], otherwise=[
            notice(ref_output(failed, 'Text'), ': ', ref_output(msg, 'Text')),
        ]),
    ]


def send_text(api, token_text, raw_ref, type_text=None):
    """Send `raw_ref` as text and say how it went: sent, already sent a moment ago, or why not."""
    post, seq, seq_text, err, err_text, label, fallback, repeat = (new_id() for _ in range(8))
    return [
        action('downloadurl', UUID=post, WFURL=f'{api}/v1/ingest', WFHTTPMethod='POST', WFHTTPBodyType='JSON',
               ShowHeaders=True,
               WFHTTPHeaders=dictionary([('Authorization', ('Bearer ', ref_output(token_text, 'Text')))]),
               WFJSONValues=dictionary([('kind', 'text'), ('raw', (raw_ref,)), ('source', 'ios-shortcut')])),
        action('getvalueforkey', UUID=seq, WFInput=attachment(ref_output(post, 'Contents of URL')),
               WFGetDictionaryValueType='Value', WFDictionaryKey='seq'),
        as_text(seq_text, ref_output(seq, 'Dictionary Value')),
        action('getvalueforkey', UUID=err, WFInput=attachment(ref_output(post, 'Contents of URL')),
               WFGetDictionaryValueType='Value', WFDictionaryKey='error'),
        as_text(err_text, ref_output(err, 'Dictionary Value')),
        # Success carries `seq`; so does a repeat within the minute (409, with error DEDUPED).
        # Tested as "empty or not" — "has any value" is true for an empty Text too, so it can't tell.
        *if_empty(ref_output(seq_text, 'Text'), then=failure(err_text), otherwise=[
            action('vibrate'),
            *if_empty(ref_output(err_text, 'Text'), then=[
                *say(label, 'sent:', ref_output(type_text, 'Text') if type_text else 'Text'),
                *if_empty(ref_output(label, 'Text'), then=[
                    *say(fallback, 'sent:Text'),
                    notice(ref_output(fallback, 'Text'), sound=False),
                ], otherwise=[
                    notice(ref_output(label, 'Text'), sound=False),
                ]),
            ], otherwise=[
                *say(repeat, 'repeat'),
                notice(ref_output(repeat, 'Text'), sound=False),
            ]),
        ]),
    ]


def send_files(api, token_text, count_text):
    """Upload each shared item in one request; one batch when there are several. Then say how many went."""
    now, stamp, stamp_enc, batch = (new_id() for _ in range(4))
    loop, item_type, item_type_text, named, blank, name, name_text, name_enc = (new_id() for _ in range(8))
    post, seq, seq_text, err, err_text, msg, line, failed, lang_text = (new_id() for _ in range(9))
    label_key, label, some_failed, sent, offline, mark, fresh, fresh_count, fresh_text, repeat = (new_id() for _ in range(10))
    item, index = ref_var('Repeat Item'), ref_var('Repeat Index')
    return [
        action('date', UUID=now, WFDateActionMode='Current Date'),
        # The name for items that have none (a PDF): the phone's own clock, as the notes will show it
        action('format.date', UUID=stamp, WFDate=text(ref_output(now, 'Date')), WFDateFormatStyle='Custom',
               WFDateFormat='MM-dd HH.mm'),
        action('urlencode', UUID=stamp_enc, WFInput=text(ref_output(stamp, 'Formatted Date')), WFEncodeMode='Encode'),
        action('format.date', UUID=batch, WFDate=text(ref_output(now, 'Date')), WFDateFormatStyle='Custom',
               WFDateFormat='yyMMddHHmmssSSS'),
        action('repeat.each', GroupingIdentifier=loop, WFControlFlowMode=0, WFInput=attachment(ref_input())),
        # A name only for photos and ordinary files
        action('getitemtype', UUID=item_type, WFInput=attachment(item)),
        as_text(item_type_text, ref_output(item_type, 'Type')),
        action('setvariable', WFVariableName='type', WFInput=attachment(ref_output(item_type_text, 'Text'))),
        *strip_words(named, ref_output(item_type_text, 'Text'), NAMED_TYPES),
        action('gettext', UUID=blank, WFTextActionText=text('')),
        action('setvariable', WFVariableName='name', WFInput=attachment(ref_output(blank, 'Text'))),
        *if_empty(ref_output(named, 'Text'), then=[
            as_text(name, item),
            action('setvariable', WFVariableName='name', WFInput=attachment(ref_output(name, 'Text'))),
        ]),
        as_text(name_text, ref_var('name')),
        action('urlencode', UUID=name_enc, WFInput=text(ref_output(name_text, 'Text')), WFEncodeMode='Encode'),
        action('downloadurl', UUID=post, WFHTTPMethod='POST', WFHTTPBodyType='File', WFRequestVariable=attachment(item),
               WFURL=text(f'{api}/v1/ingest/file?source=ios-shortcut&name=', ref_output(name_enc, 'URL Encoded Text'),
                          '&at=', ref_output(stamp_enc, 'URL Encoded Text'), '&group=b', ref_output(batch, 'Formatted Date'),
                          '&i=', index, '&n=', ref_output(count_text, 'Text')),
               ShowHeaders=True,
               WFHTTPHeaders=dictionary([('Authorization', ('Bearer ', ref_output(token_text, 'Text')))])),
        action('getvalueforkey', UUID=seq, WFInput=attachment(ref_output(post, 'Contents of URL')),
               WFGetDictionaryValueType='Value', WFDictionaryKey='seq'),
        as_text(seq_text, ref_output(seq, 'Dictionary Value')),
        action('getvalueforkey', UUID=err, WFInput=attachment(ref_output(post, 'Contents of URL')),
               WFGetDictionaryValueType='Value', WFDictionaryKey='error'),
        as_text(err_text, ref_output(err, 'Dictionary Value')),
        # A repeat within the minute also carries `seq` (409 DEDUPED): count only the new ones, one mark each
        *if_empty(ref_output(seq_text, 'Text'), otherwise=[
            *if_empty(ref_output(err_text, 'Text'), then=[
                action('gettext', UUID=mark, WFTextActionText=text(ref_var('fresh'), 'x')),
                action('setvariable', WFVariableName='fresh', WFInput=attachment(ref_output(mark, 'Text'))),
            ]),
        ], then=[
            *say(msg, 'error:', ref_output(err_text, 'Text')),
            *if_empty(ref_output(msg, 'Text'), then=[
                *say(offline, 'offline'),
                action('setvariable', WFVariableName='why', WFInput=attachment(ref_output(offline, 'Text'))),
            ], otherwise=[
                action('setvariable', WFVariableName='why', WFInput=attachment(ref_output(msg, 'Text'))),
            ]),
            action('gettext', UUID=line, WFTextActionText=text(ref_var('failed'), '#', index, ' ', ref_var('why'), '\n')),
            action('setvariable', WFVariableName='failed', WFInput=attachment(ref_output(line, 'Text'))),
        ]),
        action('repeat.each', GroupingIdentifier=loop, WFControlFlowMode=2),
        as_text(failed, ref_var('failed')),
        *if_empty(ref_output(failed, 'Text'), then=[
          as_text(fresh, ref_var('fresh')),
          *if_empty(ref_output(fresh, 'Text'), then=[
            # every one was sent a moment ago already
            *say(repeat, 'repeat'),
            notice(ref_output(repeat, 'Text'), sound=False),
          ], otherwise=[
            action('vibrate'),
            action('count', UUID=fresh_count, WFCountType='Characters', Input=attachment(ref_output(fresh, 'Text'))),
            as_text(fresh_text, ref_output(fresh_count, 'Count')),
            # "3 photo/video sent" · "已发送 3 张照片/视频" — the new ones, named after the last item's type
            *say(label_key, 'label:', ref_var('type')),
            *if_empty(ref_output(label_key, 'Text'), then=[
                *say(label, 'label:'),
                action('setvariable', WFVariableName='label', WFInput=attachment(ref_output(label, 'Text'))),
            ], otherwise=[
                action('setvariable', WFVariableName='label', WFInput=attachment(ref_output(label_key, 'Text'))),
            ]),
            *say(sent, 'sent'),
            as_text(lang_text, ref_var('lang')),
            *if_block(ref_output(lang_text, 'Text'), BEGINS_WITH, string='zh', then=[
                notice(ref_output(sent, 'Text'), ' ', ref_output(fresh_text, 'Text'), ' ', ref_var('label'), sound=False),
            ], otherwise=[
                notice(ref_output(fresh_text, 'Text'), ' ', ref_var('label'), ' ', ref_output(sent, 'Text'), sound=False),
            ]),
          ]),
        ], otherwise=[
            *say(some_failed, 'some_failed'),
            notice(ref_output(some_failed, 'Text'), '\n', ref_output(failed, 'Text')),
        ]),
    ]


def build(api):
    token_from_input, get_file, clipboard, old_file, old_text, replace, paired, not_paired = (new_id() for _ in range(8))
    input_text, token_text, count, count_text, none_text, type_, type_text, rest = (new_id() for _ in range(8))
    actions = [
        *pick_language(),
        # 1 · Pairing handoff from the web inbox: save the token, send nothing.
        as_text(input_text, ref_input()),
        *if_block(ref_output(input_text, 'Text'), BEGINS_WITH, string=PAIR_PREFIX, then=[
            action('text.replace', UUID=token_from_input, WFInput=text(ref_output(input_text, 'Text')),
                   WFReplaceTextFind=PAIR_PREFIX, WFReplaceTextReplace=''),
            # Already paired: ask first. Cancel stops the shortcut and keeps the old token.
            action('documentpicker.open', UUID=old_file, WFGetFilePath=TOKEN_FILE,
                   WFFileErrorIfNotFound=False, WFShowFilePicker=False),
            as_text(old_text, ref_output(old_file, 'File')),
            *if_empty(ref_output(old_text, 'Text'), then=[], otherwise=[
                *say(replace, 'replace'),
                action('alert', WFAlertActionTitle='dropit', WFAlertActionMessage=text(ref_output(replace, 'Text')),
                       WFAlertActionCancelButtonShown=True),
            ]),
            action('documentpicker.save', WFInput=attachment(ref_output(token_from_input, 'Updated Text')),
                   WFAskWhereToSave=False, WFFileDestinationPath=TOKEN_FILE, WFSaveFileOverwrite=True),
            *say(paired, 'paired'),
            notice(ref_output(paired, 'Text')),
            action('exit'),
        ]),
        # 2 · Load the token.
        action('documentpicker.open', UUID=get_file, WFGetFilePath=TOKEN_FILE,
               WFFileErrorIfNotFound=False, WFShowFilePicker=False),
        as_text(token_text, ref_output(get_file, 'File')),
        *if_empty(ref_output(token_text, 'Text'), then=[
            *say(not_paired, 'not_paired'),
            notice(ref_output(not_paired, 'Text')),
            action('exit'),
        ]),
        # 3 · Nothing shared (Home Screen, Back Tap): send the clipboard. Counted in items, not as text.
        action('count', UUID=count, WFCountType='Items', Input=attachment(ref_input())),
        as_text(count_text, ref_output(count, 'Count')),
        *strip_words(none_text, ref_output(count_text, 'Text'), ['0']),      # 0 leaves nothing; 10 leaves 1
        *if_empty(ref_output(none_text, 'Text'), then=[
            action('getclipboard', UUID=clipboard),
            *send_text(api, token_text, ref_output(clipboard, 'Clipboard')),
            action('exit'),
        ]),
        # 4 · Photos, videos, files: upload each. Everything else: send as text. No choices, no confirmation.
        action('getitemtype', UUID=type_, WFInput=attachment(ref_input())),
        as_text(type_text, ref_output(type_, 'Type')),
        *strip_words(rest, ref_output(type_text, 'Text'), FILE_TYPES),
        *if_empty(ref_output(rest, 'Text'),
                  then=send_files(api, token_text, count_text),
                  otherwise=send_text(api, token_text, ref_output(input_text, 'Text'), type_text)),
    ]
    return {
        'WFWorkflowActions': actions,
        'WFWorkflowClientVersion': '2605.0.5',
        'WFWorkflowMinimumClientVersion': 900,
        'WFWorkflowMinimumClientVersionString': '900',
        'WFWorkflowIcon': {'WFWorkflowIconStartColor': 4282601983, 'WFWorkflowIconGlyphNumber': 59446},
        'WFWorkflowImportQuestions': [],
        'WFWorkflowTypes': ['ActionExtension'],
        # Every type a share can turn into. A type left out is dropped before the shortcut runs — a .json
        # file (a "Dictionary") arrived as nothing at all, which the clipboard fallback would have misread.
        'WFWorkflowInputContentItemClasses': [
            'WFURLContentItem', 'WFStringContentItem', 'WFRichTextContentItem', 'WFImageContentItem',
            'WFAVAssetContentItem', 'WFPhotoMediaContentItem', 'WFGenericFileContentItem', 'WFPDFContentItem',
            'WFDictionaryContentItem', 'WFContactContentItem', 'WFDateContentItem', 'WFLocationContentItem',
            'WFEmailAddressContentItem', 'WFPhoneNumberContentItem', 'WFNumberContentItem',
            'WFSafariWebPageContentItem', 'WFArticleContentItem'],
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
