# 청약 알림

아파트 청약(청약홈)과 공모주 청약(38커뮤니케이션)의 **신규 건만** 골라 텔레그램으로 보낸다.

외부 라이브러리 없음. JDK 11 이상이면 빌드 없이 소스 파일을 그대로 실행한다.

## 파일

| 파일 | 역할 |
|---|---|
| `Cheongyak.java` | 전부. 수집 · 필터 · 중복판정 · 전송 · 페이지 생성 |
| `run.bat` | 실행 래퍼. 작업 스케줄러에 이걸 등록한다 |
| `seen.txt` | 이미 알린 건의 키. 첫 실행 때 자동 생성 |
| `docs/index.html` | 웹 페이지. `--html` 로 생성 |
| `.github/workflows/cheongyak.yml` | GitHub Actions 스케줄 실행 |

## 설정

환경변수 4개. `setx` 로 한 번만 등록하면 된다 (등록 후 새 터미널을 열어야 반영된다).

```
setx TG_TOKEN "봇토큰"
setx TG_CHAT_ID "채팅ID"
setx APPLYHOME_KEY "data.go.kr 개인 API 인증키"
setx APT_REGIONS "서울,경기"
```

- `APPLYHOME_KEY` — 없으면 아파트를 건너뛰고 공모주만 알린다.
- `APT_REGIONS` — 비워두면 전국. 값은 청약홈의 공급지역명(`서울`, `경기`, `부산` …)과 부분 일치로 비교한다.

텔레그램 토큰은 [@BotFather](https://t.me/BotFather) 에서 `/newbot` 으로 받는다.
채팅 ID 는 그 봇에게 아무 메시지나 보낸 뒤
`https://api.telegram.org/bot<토큰>/getUpdates` 의 `chat.id` 를 보면 된다.

## 실행

```
run.bat                          평소 실행
run.bat --dry                    텔레그램 안 보내고 콘솔에만 출력
run.bat --selftest               파서 자체 점검 (네트워크 불필요)
run.bat --html docs\index.html   웹 페이지도 같이 생성
```

**첫 실행은 알림을 보내지 않는다.** 현재 목록(40건 안팎)을 기준선으로 `seen.txt` 에
등록만 한다. 두 번째 실행부터 그 이후 새로 뜬 건만 나간다. 처음부터 다시 받고 싶으면
`seen.txt` 를 지우면 된다.

작업 스케줄러에는 `run.bat` 을 등록한다. 하루 2회 정도면 충분하다.
**아래 GitHub Actions 로 돌리면 작업 스케줄러는 필요 없다** (PC 를 안 켜도 동작한다).

## 웹으로 보기 (GitHub Actions + Pages)

수집을 GitHub 이 대신 돌리고, 만들어진 정적 HTML 을 Pages 가 서빙한다.
브라우저가 API 를 직접 부르지 않으므로 **CORS 도 없고 인증키도 노출되지 않는다.**

```
Actions (cron) ── Cheongyak.java ── 텔레그램 전송
                                 └─ docs/index.html 생성 → commit → Pages 서빙
```

### 설정 순서

1. **public 저장소를 새로 만들고** 이 폴더 전체를 push 한다.
   (무료 Pages 는 public 저장소여야 한다. 전용 저장소를 권한다 — Actions 가 하루 2회
   결과를 커밋하므로 다른 프로젝트와 섞으면 이력이 지저분해진다.)
2. **Settings → Secrets and variables → Actions**
   - `Secrets` 탭: `TG_TOKEN`, `TG_CHAT_ID`, `APPLYHOME_KEY`
   - `Variables` 탭: `APT_REGIONS` (예 `서울,경기`. 전국이면 안 만들어도 된다)
3. **Settings → Pages** → Source: `Deploy from a branch` → Branch: `main` / 폴더 `/docs`
4. **Actions 탭 → 청약 알림 → Run workflow** 로 한 번 수동 실행해 확인한다.
5. 주소는 `https://<사용자명>.github.io/<저장소명>/` 이다.

실행 시각은 `cheongyak.yml` 의 `cron` 이다. GitHub 은 UTC 를 쓰므로
`'0 0,9 * * *'` 는 KST 09:00 / 18:00 이다. 예약 실행은 러너가 붐비면 수십 분 밀릴 수 있다.

### 실패하면 어떻게 되나

두 소스가 **동시에** 실패해 수집이 0건이면, 멀쩡한 페이지를 빈 페이지로 덮지 않고
**실패로 종료한다.** 그러면 Actions 가 실패 알림 메일을 보낸다.
한쪽만 실패하면 나머지는 정상적으로 갱신된다.

`seen.txt` 도 같이 커밋된다. 이게 "이미 알린 건" 기록이라, 저장소에서 지우면
다음 실행이 첫 실행으로 취급되어 기준선만 다시 잡는다(알림은 안 나간다).

## 알아둘 것

- **`run.bat` 은 ASCII 로만 유지한다.** `chcp` 가 콘솔 코드페이지를 파일 중간에서 바꾸는데,
  cmd.exe 는 배치 파일을 바이트 오프셋으로 다시 읽기 때문에 위쪽에 한글이 있으면
  파싱이 어긋나 주석을 명령으로 실행해 버린다. 한글 설명은 이 README 에 둔다.
- **`-Dfile.encoding=UTF-8` 을 빼면 안 된다.** JDK 17 은 한국어 Windows 에서 기본 charset 이
  MS949 라, 단일 파일 런처가 UTF-8 로 저장된 소스를 MS949 로 읽어 한글 리터럴을 전부
  깨뜨린다. 그러면 공모주 표를 못 찾아 **에러 없이 조용히 0건**이 된다. JDK 18 부터는
  기본이 UTF-8 이라 없어도 되지만 있어도 무해하다.
- **`HttpURLConnection` 을 쓴다.** 요즘 API 인 `java.net.http.HttpClient` 는 내부에서 NIO
  Selector 용 loopback 파이프를 여는데, 사내 보안 정책으로 그게 막힌 PC 에서는
  `Unable to establish loopback connection` 으로 아예 뜨지 않는다. 실제로 이 PC 가 그렇다.
- **JSON 을 정규식으로 읽는다.** JDK 에 파서가 없고, 라이브러리를 넣으면 "파일 하나로 실행"
  이라는 장점이 사라진다. 청약홈 응답이 중첩 없는 평면 구조라 가능하다(실제 응답 200건 확인).
  구조가 바뀌면 0건으로 나타나므로 로그에 드러난다.
- **공모주는 HTML 파싱이라 사이트 개편에 깨진다.** 그때는 "공모주: 수집 실패" 로그만 뜨고
  아파트 알림은 계속 간다. `parseIpo` 의 표 검색 문자열과 칸 번호만 맞춰주면 된다.
- 아파트는 접수 마감일이 지난 공고를 제외한다. 공고일 최신순 200건만 조회한다.

## 자체 점검

`run.bat --selftest` 는 네트워크 없이 파서만 검사한다. 광고/헤더 행 제외, 지역 필터,
마감 필터, null 처리, JSON 이스케이프 양방향을 확인한다. 파서를 손댔으면 이걸 먼저 돌린다.
