"""Offline checks for transcript completeness and credential masking."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from export_ai_conversation import THREAD_ID, discover_credentials, export, redact_records, visible_records


def event(item, stamp='2026-10-12T00:00:00Z'):
    return {'type': 'event_msg', 'timestamp': stamp,
            'payload': {'type': 'item_completed', 'turn_id': 'fixture-turn', 'item': item}}


class ConversationExportTest(unittest.TestCase):
    def source(self):
        items = [
            {'type': 'session_meta', 'payload': {'id': THREAD_ID}},
            event({'type': 'UserMessage', 'id': 'user-one', 'content': [{'type': 'text', 'text': 'X-Candidate-Key는 FixtureCandidate9 이야'}]}),
            event({'type': 'AgentMessage', 'id': 'call_question', 'phase': 'final_answer',
                   'content': [{'type': 'Text', 'text': '실행 조건을 선택해 주세요.'}]}),
            event({'type': 'UserMessage', 'id': 'user-two', 'content': [{'type': 'text', 'text': '<send_user_message_question_reply>승인</send_user_message_question_reply>\r\n원래 줄바꿈'},
                    {'type': 'local_image', 'path': 'C:/fixture/evidence.png'}]}),
            event({'type': 'AgentMessage', 'id': 'answer', 'phase': 'final_answer',
                   'content': [{'type': 'Text', 'text': 'FixtureCandidate9 재사용, EMP-001 pending. 전체 내용 ' + '가' * 25000}]}),
            event({'type': 'Reasoning', 'id': 'private', 'text': 'PRIVATE_REASONING_MUST_NOT_EXPORT'}),
            event({'type': 'CommandExecution', 'id': 'command', 'output': 'RAW_TOOL_OUTPUT_MUST_NOT_EXPORT'}),
            {'type': 'response_item', 'payload': {'type': 'message', 'role': 'developer', 'content': [{'type': 'input_text', 'text': 'SYSTEM_RULE_MUST_NOT_EXPORT'}]}},
        ]
        return ('\n'.join(json.dumps(i, ensure_ascii=False) for i in items) + '\n').encode('utf-8')

    def test_all_visible_items_are_preserved_without_truncation_or_hidden_items(self):
        records, _, counts, _ = visible_records(self.source())
        self.assertEqual([r['messageId'] for r in records], ['user-one', 'call_question', 'user-two', 'answer'])
        self.assertEqual(counts['UserMessage'], 2)
        self.assertIn('가' * 25000, records[-1]['text'])
        with tempfile.TemporaryDirectory() as work:
            source = Path(work) / 'fixture.jsonl'
            source.write_bytes(self.source())
            output = Path(work) / 'out'
            result = export(source, output)
            text = (output / 'AI_CONVERSATION.txt').read_bytes().decode('utf-8')
            self.assertEqual(result['visibleMessageCount'], 4)
            self.assertEqual(result['questionMessageCount'], 1)
            self.assertEqual(result['imageReferenceCount'], 1)
            self.assertNotIn('FixtureCandidate9', text)
            self.assertIn('가' * 25000, text)
            self.assertIn('</send_user_message_question_reply>\r\n원래 줄바꿈', text)
            for marker in ['PRIVATE_REASONING_MUST_NOT_EXPORT', 'RAW_TOOL_OUTPUT_MUST_NOT_EXPORT', 'SYSTEM_RULE_MUST_NOT_EXPORT']:
                self.assertNotIn(marker, text)
            self.assertEqual(source.read_bytes(), self.source())

    def test_credentials_are_masked_on_reuse_and_tool_derived_passwords(self):
        texts = ['X_CANDIDATE_KEY=FixtureCandidate9', "name:'비밀번호'}).fill('FixturePassword8')"]
        secrets = discover_credentials(texts)
        self.assertEqual(secrets, {'FixtureCandidate9', 'FixturePassword8'})
        records = [{'sequence': 1, 'messageId': 'fixture', 'text': 'FixtureCandidate9 FixturePassword8 postgres://user:DbFixture8@host/db Bearer TokenFixture8'}]
        edits = redact_records(records, secrets)
        self.assertEqual(len(edits), 1)
        for value in ['FixtureCandidate9', 'FixturePassword8', 'DbFixture8', 'TokenFixture8']:
            self.assertNotIn(value, records[0]['text'])

    def test_scope_duplicate_and_unknown_content_fail_closed(self):
        data = [json.loads(line) for line in self.source().splitlines()]
        wrong = copy.deepcopy(data)
        wrong[0]['payload']['id'] = 'another-task'
        with self.assertRaises(ValueError):
            visible_records('\n'.join(json.dumps(i) for i in wrong).encode())
        for extra in [data[1], event({'type': 'UserMessage', 'id': 'unknown', 'content': [{'type': 'unknown_attachment'}]})]:
            with self.assertRaises(ValueError):
                visible_records('\n'.join(json.dumps(i) for i in data + [extra]).encode())


if __name__ == '__main__':
    unittest.main()
