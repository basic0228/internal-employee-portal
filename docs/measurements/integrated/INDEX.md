# 통합 원본 인덱스

기준: 2026-10-12 KST. 원본 파일 58개에서 고유 종료 실행 39개, 실제 시도 65개를 중복 제거했다. 이는 기록 재고이며 전체를 단일 성능 표본으로 합산하지 않는다.

| 구분 | 실행 n | 실제 시도 n | 완료 HTTP n | POST n |
|---|---:|---:|---:|---:|
| INITIAL_11_CASES | 15 | 34 | 31 | 6 |
| FOLLOWUP_130_HTTP | 13 | 18 | 16 | 2 |
| OTHER_EXPLORATORY | 11 | 13 | 8 | 0 |

## 상세 GET 원본 기반 통계

LIST와 별도다. 같은 run/HTTP 상태별로 분리했다. 지연은 ms, Type7이며 소표본 상위 백분위수는 장기 tail 보장이 아니다.

| run ID | 구분 | HTTP/전송 결과 | n | 평균 | p50 | p95 | p99 | 최대 |
|---|---|---|---:|---:|---:|---:|---:|---:|
| fa48a462-cfff-4b10-9259-5b1eb2d89365 | INITIAL_11_CASES | 200 | 2 | 325.184 | 325.184 | 332.955 | 333.645 | 333.818 |
| fa48a462-cfff-4b10-9259-5b1eb2d89365 | INITIAL_11_CASES | REQUEST_TIMEOUT | 1 | 30003.721 | 30003.721 | 30003.721 | 30003.721 | 30003.721 |
| 4ff4f593-c904-4916-a2da-937217b90c98 | FOLLOWUP_130_HTTP | REQUEST_TIMEOUT | 1 | 30001.118 | 30001.118 | 30001.118 | 30001.118 | 30001.118 |
| a42e5d55-0aa3-4a75-9f70-418a803de39d | FOLLOWUP_130_HTTP | 500 | 1 | 229.141 | 229.141 | 229.141 | 229.141 | 229.141 |

비HTTP 행은 중단까지 경과시간이며 완료 응답 지연이 아니다. 정상 업무 시간·최종 발견과 혼합하지 않는다.

## 실행 추적

| run ID | 구분 | 프로토콜/유형 | 실제 n | 상태 | 원본 |
|---|---|---|---:|---|---|
| e83392ee-b8f9-4f29-bf31-4e4032154ff1 | OTHER_EXPLORATORY | GET_BASELINE  | 1 | COMPLETED/- | [JSON](../raw/lab-e83392ee-b8f9-4f29-bf31-4e4032154ff1.json) |
| f70323f3-2b6b-4d72-9f7a-a9bf31d23557 | OTHER_EXPLORATORY | GET_BASELINE  | 1 | STOPPED/CANCELLED | [JSON](../raw/lab-f70323f3-2b6b-4d72-9f7a-a9bf31d23557.json) |
| 2bc4b99c-502d-4f1b-9fbb-9a767c193833 | OTHER_EXPLORATORY | GET_BASELINE  | 1 | COMPLETED/- | [JSON](../raw/lab-2bc4b99c-502d-4f1b-9fbb-9a767c193833.json) |
| 7766daec-5f98-450b-89b8-7cd18f4683d2 | OTHER_EXPLORATORY | GET_BASELINE  | 1 | COMPLETED/- | [JSON](../raw/lab-7766daec-5f98-450b-89b8-7cd18f4683d2.json) |
| 50c4afc0-a755-49f6-8ea1-1c6821f54ce9 | OTHER_EXPLORATORY | GET_BASELINE  | 2 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/lab-50c4afc0-a755-49f6-8ea1-1c6821f54ce9.json) |
| 0664760e-a7c2-49f4-8110-b49a75175cac | OTHER_EXPLORATORY | GET_BASELINE  | 1 | COMPLETED/- | [JSON](../raw/lab-0664760e-a7c2-49f4-8110-b49a75175cac.json) |
| 9dd4a2d6-e702-4ef1-83aa-88bbb5eda46d | INITIAL_11_CASES | GET_FIXED  | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-9dd4a2d6-e702-4ef1-83aa-88bbb5eda46d.json) |
| 0f599928-512a-4b08-a113-8f3913ada02f | INITIAL_11_CASES | GET_FIXED  | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-0f599928-512a-4b08-a113-8f3913ada02f.json) |
| bceb8eb7-7715-4efe-9c5d-00fd68d2e35a | INITIAL_11_CASES | GET_FIXED  | 3 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-bceb8eb7-7715-4efe-9c5d-00fd68d2e35a.json) |
| 15b904a5-002f-4348-89d1-44f18868da03 | INITIAL_11_CASES | GET_FIXED  | 1 | COMPLETED/- | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-15b904a5-002f-4348-89d1-44f18868da03.json) |
| 2e79bff2-80e2-4e82-8e04-31e96f8d9ffb | INITIAL_11_CASES | GET_FIXED  | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-2e79bff2-80e2-4e82-8e04-31e96f8d9ffb.json) |
| 2a883cd0-c44a-4288-b496-8d4e71cf680f | INITIAL_11_CASES | GET_FIXED  | 2 | COMPLETED/- | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-2a883cd0-c44a-4288-b496-8d4e71cf680f.json) |
| 7c70ac5f-cabb-4ef7-93c0-0e06417208be | INITIAL_11_CASES | GET_FIXED  | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-7c70ac5f-cabb-4ef7-93c0-0e06417208be.json) |
| 2e8b541c-3b23-4a80-865a-206dfcc601a9 | INITIAL_11_CASES | GET_FIXED  | 3 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-2e8b541c-3b23-4a80-865a-206dfcc601a9.json) |
| 73897f1f-b17d-429b-8baa-cc679b273fe2 | INITIAL_11_CASES | CREATE_POLL  | 1 | COMPLETED/- | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-73897f1f-b17d-429b-8baa-cc679b273fe2.json) |
| fa48a462-cfff-4b10-9259-5b1eb2d89365 | INITIAL_11_CASES | CREATE_POLL  | 4 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-fa48a462-cfff-4b10-9259-5b1eb2d89365.json) |
| 6389a638-6cb5-42c5-9cd0-4170c34ed323 | INITIAL_11_CASES | REPEAT_POST  | 4 | COMPLETED/- | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-6389a638-6cb5-42c5-9cd0-4170c34ed323.json) |
| da9e2620-c41a-4997-a4eb-9403b35dd6df | INITIAL_11_CASES | REPEAT_POST  | 4 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-da9e2620-c41a-4997-a4eb-9403b35dd6df.json) |
| 2f8c0183-c5eb-4632-b161-b57c6eb95042 | INITIAL_11_CASES | GET_FIXED  | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-2f8c0183-c5eb-4632-b161-b57c6eb95042.json) |
| d2381c7b-c97b-492c-b233-1838492c46b7 | INITIAL_11_CASES | GET_FIXED  | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-d2381c7b-c97b-492c-b233-1838492c46b7.json) |
| 489e33c1-cc4b-4921-a081-350bb185fbd5 | INITIAL_11_CASES | GET_FIXED  | 3 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json), [JSON](../raw/lab-489e33c1-cc4b-4921-a081-350bb185fbd5.json) |
| b3d7fb89-4ca3-4e80-a639-d0b42015a8bb | OTHER_EXPLORATORY | B01 DEFAULT | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-7141a14a-f921-4532-9dd2-7fa75a294a0b.json), [JSON](../raw/lab-b3d7fb89-4ca3-4e80-a639-d0b42015a8bb.json) |
| 5a194a1c-824c-41c7-a581-cb9da902ee5f | OTHER_EXPLORATORY | R01 SHORT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-eb8c172a-7897-4e0e-bef1-46f65025f88f.json), [JSON](../raw/lab-5a194a1c-824c-41c7-a581-cb9da902ee5f.json) |
| 4bfb484c-0b9d-4515-91d7-5f11b9c9cda1 | OTHER_EXPLORATORY | R02 DEFAULT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-b6ceb3a7-2551-4f0a-b662-07829ce20b46.json), [JSON](../raw/lab-4bfb484c-0b9d-4515-91d7-5f11b9c9cda1.json) |
| 3f350177-709e-4394-aa6a-4d129c56dedd | OTHER_EXPLORATORY | B01 DEFAULT | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-7df3edd8-4fe6-4b44-a652-ced885fcec86.json), [JSON](../raw/lab-3f350177-709e-4394-aa6a-4d129c56dedd.json) |
| f0acb2f8-e05e-40f6-814e-3e3f8ebfdb73 | OTHER_EXPLORATORY | R02 DEFAULT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-748a7e9a-61e6-4476-86ec-375c1764013c.json), [JSON](../raw/lab-f0acb2f8-e05e-40f6-814e-3e3f8ebfdb73.json) |
| af283831-9960-4b48-a7ca-612720d4e20f | FOLLOWUP_130_HTTP | B01 DEFAULT | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-d19050c7-b8e4-4397-8afe-9b40b5cd0c9d.json), [JSON](../raw/lab-af283831-9960-4b48-a7ca-612720d4e20f.json) |
| 9a1de23e-044b-49f9-aba4-6dc2e60323b9 | FOLLOWUP_130_HTTP | R02 DEFAULT | 1 | COMPLETED/- | [JSON](../raw/campaign-f6740d43-b428-4489-a533-40889e985d99.json), [JSON](../raw/lab-9a1de23e-044b-49f9-aba4-6dc2e60323b9.json) |
| 36a7e27c-3067-4620-a8db-e0690228ff33 | FOLLOWUP_130_HTTP | R01 SHORT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-3b6621c9-bc12-4cb3-9720-d2fd5ee1da21.json), [JSON](../raw/lab-36a7e27c-3067-4620-a8db-e0690228ff33.json) |
| fa596d81-c243-483c-bf12-8df0a40f2ea1 | FOLLOWUP_130_HTTP | R04 DEFAULT | 1 | COMPLETED/- | [JSON](../raw/campaign-d5497303-0797-4d53-9d49-f2ae1bb7ab82.json), [JSON](../raw/lab-fa596d81-c243-483c-bf12-8df0a40f2ea1.json) |
| 00fcd3c4-ce33-448b-a1c3-c5b8617139a3 | FOLLOWUP_130_HTTP | P01 DEFAULT | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-27af3e04-26c5-4de1-9025-946ed53d57bc.json), [JSON](../raw/lab-00fcd3c4-ce33-448b-a1c3-c5b8617139a3.json) |
| 4ff4f593-c904-4916-a2da-937217b90c98 | FOLLOWUP_130_HTTP | P02 DEFAULT | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-ae6ce636-adec-430b-bdac-53deb0057b5d.json), [JSON](../raw/lab-4ff4f593-c904-4916-a2da-937217b90c98.json) |
| a42e5d55-0aa3-4a75-9f70-418a803de39d | FOLLOWUP_130_HTTP | P04 DEFAULT | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-a769d7b9-551d-4574-8bf2-e8262d996b52.json), [JSON](../raw/lab-a42e5d55-0aa3-4a75-9f70-418a803de39d.json) |
| e67172ec-09be-4ea8-8f90-6d410aebdce5 | FOLLOWUP_130_HTTP | B01 DEFAULT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-cbc964ca-9f05-480c-bec2-d68649e53927.json), [JSON](../raw/lab-e67172ec-09be-4ea8-8f90-6d410aebdce5.json) |
| 8c014f8a-8e13-4d71-98d3-9ce96cbe978f | FOLLOWUP_130_HTTP | C03 DEFAULT | 1 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-43ed4d81-dd19-472c-8d9e-3a914e535783.json), [JSON](../raw/lab-8c014f8a-8e13-4d71-98d3-9ce96cbe978f.json) |
| c4e7adbc-a862-4e9a-911f-fd4821d87ec7 | FOLLOWUP_130_HTTP | R02 DEFAULT | 2 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-a104e056-cf53-4fe0-8bef-42ac38a1e6e6.json), [JSON](../raw/lab-c4e7adbc-a862-4e9a-911f-fd4821d87ec7.json) |
| 0f2532a0-73c0-43ea-b9a1-d7a49afe4562 | FOLLOWUP_130_HTTP | R01 SHORT | 1 | COMPLETED/- | [JSON](../raw/campaign-c4b9beda-d514-4894-8bdb-9fc700e3d2c3.json), [JSON](../raw/lab-0f2532a0-73c0-43ea-b9a1-d7a49afe4562.json) |
| d58d979a-5b67-4643-aad1-41f87602f647 | FOLLOWUP_130_HTTP | P01 DEFAULT | 1 | STOPPED/OBSERVED_ERROR | [JSON](../raw/campaign-d0b4e21a-7d5e-4bb5-ac12-f5b5e96494da.json), [JSON](../raw/lab-d58d979a-5b67-4643-aad1-41f87602f647.json) |
| ccc9290e-06c6-4f9c-9c76-e409eb068e4d | FOLLOWUP_130_HTTP | C03 DEFAULT | 3 | STOPPED/RETRY_AFTER_OR_OVERLOAD | [JSON](../raw/campaign-07d10634-d071-4c95-911c-56000359968d.json), [JSON](../raw/lab-ccc9290e-06c6-4f9c-9c76-e409eb068e4d.json) |

정확한 설정·리비전·시각·원본 SHA256은 evidence.json에 포함한다. 화면 관측만 남은 실행은 정밀 통계에 포함하지 않았다. 오류 의존 중단·서로 다른 대상과 시각 때문에 고정 독립 표본으로 해석하지 않는다.
