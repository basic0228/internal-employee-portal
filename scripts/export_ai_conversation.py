"""Export visible messages from this task's local record; exclude raw tool logs."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re

THREAD_ID = '01a124de-b755-76c1-a212-a325ffee9c34'
SECRET_LABEL = r'(?:X[-_]Candidate[-_]Key|candidate[-_]?key|PORTAL_ADMIN_PASSWORD|PGPASSWORD|POSTGRES_PASSWORD|password|비밀번호)'
LABELED_VALUE = re.compile(SECRET_LABEL + r'''['"]?\s*(?:[:=]\s*['"]?|(?:는|은)\s+)([A-Za-z0-9._~+!/@#$%^&*=-]{6,})''', re.I)
FILL_VALUE = re.compile(r'''(?:password|비밀번호)[^\n]{0,250}?\.fill\(\s*(['"])([^'"\n]{6,})\1''', re.I)
PLACEHOLDERS = {'false', 'true', 'string', 'password', 'password_hash', 'undefined', 'candidatekey', 'candidate-key', 'candidate_key', 'enabled', 'localhost'}


def visible_records(source):
    """Use UI-completed items, including the UI's question messages, in file order."""
    records, metadata, counts = [], None, Counter()
    credential_sources = []
    source_events = [json.loads(line) for line in source.splitlines() if line.strip()]
    for line_number, event in enumerate(source_events, 1):
        payload = event.get('payload', {})
        if event.get('type') == 'session_meta':
            metadata = payload
        if event.get('type') == 'response_item' and payload.get('type') in ('function_call', 'custom_tool_call'):
            # These strings are read for credential discovery only, never included in exports.
            value = payload.get('input') or payload.get('arguments') or ''
            if isinstance(value, str):
                credential_sources.append(value)
                try:
                    parsed = json.loads(value)
                    credential_sources.extend(walk_strings(parsed))
                except (ValueError, TypeError):
                    pass
        if event.get('type') != 'event_msg' or payload.get('type') != 'item_completed':
            continue
        item = payload.get('item', {})
        kind = item.get('type')
        if kind not in ('UserMessage', 'AgentMessage'):
            continue
        if kind == 'AgentMessage' and item.get('phase') not in ('commentary', 'final_answer'):
            raise ValueError('Unknown visible assistant phase; review before export.')
        blocks, attachments = [], []
        for block in item.get('content', []):
            if block.get('type') in ('text', 'Text'):
                blocks.append(block.get('text', ''))
            elif block.get('type') == 'local_image':
                attachments.append({'type': 'local_image', 'originalPath': block['path']})
            else:
                raise ValueError('Unknown visible content type; review before export.')
        text = '\n'.join(blocks)
        counts[kind] += 1
        records.append({'sequence': len(records) + 1, 'sourceLine': line_number,
                        'messageId': item['id'], 'turnId': payload.get('turn_id'),
                        'timestampUtc': event.get('timestamp'),
                        'role': 'USER' if kind == 'UserMessage' else 'ASSISTANT',
                        'phase': item.get('phase'), 'text': text, 'attachments': attachments})
    if not metadata or metadata.get('id') != THREAD_ID:
        raise ValueError('This exporter only accepts the authorized current task record.')
    if len({r['messageId'] for r in records}) != len(records):
        raise ValueError('Duplicate visible item IDs; reconcile rather than drop messages.')
    if not records or not all(r['text'] or r['attachments'] for r in records):
        raise ValueError('Empty visible message; review before export.')
    return records, metadata, counts, credential_sources


def walk_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from walk_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk_strings(child)


def discover_credentials(texts):
    secrets = set()
    for text in texts:
        values = [m.group(1) for m in LABELED_VALUE.finditer(text)]
        values += [m.group(2) for m in FILL_VALUE.finditer(text)]
        for value in values:
            if value.lower() not in PLACEHOLDERS and not value.startswith(('$', '*', '[')):
                secrets.add(value)
    return secrets


def redact_records(records, secrets):
    edits = []
    pattern = re.compile('|'.join(re.escape(s) for s in sorted(secrets, key=len, reverse=True))) if secrets else None
    for record in records:
        original = record['text']
        changed, n = pattern.subn('[REDACTED:CREDENTIAL]', original) if pattern else (original, 0)
        changed, urls = re.subn(r'((?:postgres(?:ql)?|https?)://[^\s/:@]+:)[^\s@]+(@)', r'\1[REDACTED:CREDENTIAL]\2', changed, flags=re.I)
        changed, bearer = re.subn(r'(?i)(Bearer\s+)[A-Za-z0-9._~+/=-]{8,}', r'\1[REDACTED:CREDENTIAL]', changed)
        changed, pem = re.subn(r'-----BEGIN [^-\n]*PRIVATE KEY-----.*?-----END [^-\n]*PRIVATE KEY-----', '[REDACTED:PRIVATE_KEY]', changed, flags=re.S)
        record['text'] = changed
        if changed != original:
            edits.append({'messageId': record['messageId'], 'sequence': record['sequence'],
                          'credentialOccurrences': n + urls + bearer, 'privateKeyBlocks': pem})
    export_text = json.dumps(records, ensure_ascii=False)
    if any(s in export_text for s in secrets):
        raise ValueError('Credential remains in export; publication blocked.')
    return edits


def export(source_path, output):
    raw = source_path.read_bytes()  # Fixed snapshot: later messages are outside this export's cutoff.
    records, metadata, counts, credential_sources = visible_records(raw)
    secrets = discover_credentials([r['text'] for r in records] + credential_sources)
    edits = redact_records(records, secrets)
    output.mkdir(parents=True, exist_ok=True)
    text_lines = ['# AI 대화 전체 텍스트 기록 — 현재 대화의 생성 시점까지', '',
                  f'Thread: {THREAD_ID}', f'마지막 포함 메시지: {records[-1]["timestampUtc"]}',
                  f'메시지 수: {len(records)} (USER {counts["UserMessage"]}, ASSISTANT {counts["AgentMessage"]})',
                  '원본 UI 메시지를 시간순으로 기록했다. 사용자·AI 답변·중간 안내·질문·선택 응답을 포함한다.',
                  '보안 편집: 자격증명은 [REDACTED:CREDENTIAL], 비밀키 본문은 [REDACTED:PRIVATE_KEY]로 마스킹했다.',
                  '첨부 이미지·문서 바이너리, 명령/도구 원시 출력, 숨겨진 추론, 시스템/개발자 지침은 포함하지 않는다.',
                  '첨부 경로와 원래 메시지의 자동 브라우저 문맥은 원문에 있으면 보존한다. 별도 대화·이후 메시지는 포함하지 않는다.',
                  'EXPORT_MANIFEST.json에 출처 해시·누락 여부·마스킹 위치·내보내기 파일 해시를 기록했다.', '']
    for record in records:
        text_lines += ['=' * 72, f'[{record["sequence"]:04d}] {record["role"]} | {record["timestampUtc"]}',
                       f'Message ID: {record["messageId"]} | Turn ID: {record["turnId"]}', '', record['text']]
        text_lines += [f'[첨부 이미지 경로: {a["originalPath"]}]' for a in record['attachments']]
        text_lines.append('')
    files = {'AI_CONVERSATION.txt': '\n'.join(text_lines).encode('utf-8'),
             'AI_CONVERSATION.jsonl': (''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in records)).encode('utf-8')}
    for name, content in files.items():
        (output / name).write_bytes(content)
    manifest = {'schemaVersion': 1, 'threadId': THREAD_ID,
                'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
                'sourceFile': source_path.name, 'sourceSnapshotBytes': len(raw),
                'sourceSnapshotSha256': hashlib.sha256(raw).hexdigest(),
                'firstMessageAtUtc': records[0]['timestampUtc'], 'lastMessageAtUtc': records[-1]['timestampUtc'],
                'visibleMessageCount': len(records), 'userMessageCount': counts['UserMessage'],
                'assistantMessageCount': counts['AgentMessage'],
                'questionMessageCount': sum(r['role'] == 'ASSISTANT' and r['messageId'].startswith('call_') for r in records),
                'imageReferenceCount': sum(len(r['attachments']) for r in records),
                'sourceVisibleMessagesOmitted': 0, 'sourceVisibleTextsTruncated': 0,
                'redactionEdits': edits, 'files': {name: {'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()} for name, value in files.items()},
                'scope': 'Current task visible messages through the fixed source snapshot. No earlier/separate tasks or later messages.',
                'excluded': ['image/document binary attachments', 'raw tools/terminal outputs', 'private reasoning', 'system/developer instructions'],
                'validation': {'uniqueMessageIds': True, 'visibleMessageCoverage': True, 'knownCredentialResiduals': 0}}
    (output / 'EXPORT_MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Exact local JSONL record for the current thread; read only.')
    parser.add_argument('--output', type=Path, default=Path('docs/ai-conversation'))
    args = parser.parse_args()
    result = export(args.source, args.output)
    print(json.dumps({'messages': result['visibleMessageCount'], 'user': result['userMessageCount'],
                      'assistant': result['assistantMessageCount'], 'redactedMessages': len(result['redactionEdits']),
                      'omittedVisibleMessages': result['sourceVisibleMessagesOmitted'], 'truncatedVisibleTexts': result['sourceVisibleTextsTruncated']}))
