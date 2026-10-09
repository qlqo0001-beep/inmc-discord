# inmc-discord (inmc-디스코드)

**DiscordSRV + InteractiveChat(+InteractiveChatDiscordSrvAddon) 를 대신하는 플러그인.** 셋을 빼고 이것 하나를 넣는다.

사용법은 `GUIDE.md`, 바꾼 것은 `CHANGELOG.md`, 이 저장소의 규칙은 `CLAUDE.md`.

## 왜 새로 만들었나

2026-10-07 테섭에서 접속 순간 메인 스레드가 10초 넘게 멈췄다. jstack 여러 장으로 확인한 원인:

- InteractiveChat 의 `ModernChatCompletionTask` 가 **비동기로 10틱마다** 접속자 전원 × 자리표시 수만큼 `hasPermission` 을 불렀다.
- 그 호출이 LuckPerms 의 플레이어별 잠금(`QueryOptionsCache`, 50ms)을 계속 잡았다(잠금 안에서 Lands·DiscordSRV·Multiverse-Inventories 의 컨텍스트 계산이 돈다).
- 같은 잠금을 기다리던 메인 스레드(타이틀포지 이름표 → PAPI → CMI 접두사, 커스텀아이템 `EquipmentStore.capacity`, FAWE)가 멈췄다.

사용자 결정은 설정을 끄는 것이 아니라 **원인을 없애는 것** — 디스코드 연동과 채팅 꾸미기를 INMC 플러그인으로 새로 만들되,
**주기적으로 접속자를 훑으며 권한·PAPI 를 묻는 일을 처음부터 하지 않는다.**

## 스레드 규칙

| 일 | 어디서 | 얼마나 |
|---|---|---|
| 채팅 꾸미기(키워드·자리표시·멘션·이름 호버)·디스코드 형식 PAPI | 채팅 스레드 | **메시지 하나에 한 번** — 그 기능을 쓴 글일 때만 권한을 묻는다 |
| [item]·[inv]·[ender] 사본 + 툴팁 줄 | 그 플레이어의 메인 스레드(채팅 스레드가 최대 1초 기다림) | 키워드가 있을 때만 |
| 탭 완성 목록 | 메인 | **접속할 때 한 번 + 리로드** |
| 디스코드 송수신 | JDA 스레드 | — |
| 디스코드 → 게임 방송·명령 실행·알림 | 메인으로 넘김 | 메시지마다 |
| 콘솔 로그 | log4j 어펜더(대기열에 넣기만) → 봇 일꾼이 묶어 보냄 | `console.refresh-seconds` |
| 채널 주제 | 봇 일꾼 | 10분 이상 |
| 서버 멈춤 감시 | 데몬 스레드 + 1초 심장 | — |
| 서버 현황 패널 | 봇 일꾼(메시지 고치기) · 메인(접속자 이름·수·TPS·오늘 수를 뜸 — **권한·PAPI 없음**) | `panel.refresh-seconds`(15초 이상), 앞 갱신이 안 끝났으면 건너뜀 |

LuckPerms 컨텍스트 계산기는 등록하지 않는다(DiscordSRV 의 `discordsrv:*` 는 LP 데이터에서 쓰지 않았다).

## 기능 — 옮긴 것

### DiscordSRV

| 기능 | 어디 |
|---|---|
| 채팅 양방향(`chat.*`) — 형식·역할 색·역할 별명·답장·256자 자르기·멘션 바꾸기·이모지 이름·웹훅 막기·색 역할·콘솔에도 찍기·거르기 | `relay/GameToDiscord.kt` · `relay/DiscordToGame.kt` |
| 웹훅 전달(`chat.webhook`, 기본 꺼짐) — 메시지마다 플레이어 이름·얼굴(mc-heads.net), 실패하면 봇으로. `url` 이면 이미 있는 웹훅 | `relay/Webhooks.kt` |
| 접속·처음 접속·퇴장·사망 알림(`player-events`) — 색 띠 + 얼굴 + 한 줄 임베드, 숨은 플레이어 제외 | `relay/PlayerEvents.kt` |
| 점검 `/디스코드 관리 검증 [보내기]` — 연결·권한·슬래시·역할·그림·웹훅, 보내기는 시험 메시지를 다시 읽어 확인 | `bot/Verifier.kt` |
| 콘솔 채널 — 로그(레벨·거르기·코드블록·접두사) · 채널에 친 글 = 콘솔 명령(막은 명령) · 사용 기록 | `console/ConsoleRelay.kt` · `console/ConsoleCommands.kt` |
| `!c 명령어` — 역할·허용 목록·우회 역할·오류 알림 | `console/ConsoleCommands.kt` |
| `접속목록` — 글 답, 정한 초 뒤 지움 | `relay/DiscordToGame.kt` |
| 봇 상태 · 시작/종료 알림 · 채널 주제(10분) | `bot/Bot.kt` · `status/Topics.kt` |
| 서버 멈춤 알림(30초 → 3번) + 회복 한 줄 | `status/Watchdog.kt` |
| 계정 연결 — 게임 `/디스코드 연결` → 봇 DM 또는 디스코드 `/연결 코드` · `Linked` 역할 · 해제 | `link/*` |
| 디스코드 별명 = 게임 닉네임(`link.sync-nickname`) — 연결할 때·접속 5초 뒤·타이틀포지 닉네임 바꿀 때. 끊어도 그대로 | `link/Nicknames.kt` |
| 업적 신호 `discord/link`·`chat`·`share`(core `SignalCatalog`) | `Signals.kt` |

### InteractiveChat (게임 안)

| 기능 | 어디 |
|---|---|
| `[item]/[i]` · `[inv]/[inventory]` · `[ender]/[e]` — 호버(아이템은 바닐라 아이템 호버), 누르면 읽기 전용 보기 화면(5분) | `chat/ChatListener.kt` · `chat/ViewMenu.kt` · `chat/Snapshots.kt` |
| `@이름` 멘션 — 실명·타이틀포지 닉네임 · `@here`/`@everyone`(권한) · 보는 사람마다 강조 · 소리·제목·보스바(쿨타임) · 개인 설정으로 끄기 | `chat/MentionScan.kt` · `chat/MentionAlerts.kt` |
| 보낸 사람 이름 호버(월드·생물군계·체력·핑)·클릭(`/msg`) | `ChatListener.Decorated` |
| `[/명령어]` 누르면 입력창에 | `ChatListener.commandTag` |
| 사용자 자리표시(`placeholders.yml`) — 보낸 사람/보는 사람 기준 | `config/CustomPlaceholder.kt` |
| 채팅 탭 완성 — 접속할 때 한 번 | `ChatListener.refreshCompletions` |

채팅에 얹는 방법: 타이틀포지가 `AsyncChatEvent` LOW 에서 렌더러를 정한다(`InMc-TitleForge/…/nickname/NameDisplayService.kt`).
우리는 NORMAL 에서 `event.message()` 를 바꾸고(타이틀포지 렌더러의 `<message>` 가 바뀐 것을 받는다) 그 렌더러를 **감싼다.** 패킷은 건드리지 않는다.

### 안 옮긴 것

- DiscordSRV: 발전과제 메시지(켜져 있었지만 본문·임베드가 다 비어 아무것도 안 보냈다), 그룹·닉네임·밴 동기화, alerts, 음성, 미리 정한 답, JDBC 계정 저장,
  연결 필수 접속, `urb` 채널(urb 는 자체 웹훅 — `inmc-urb/…/integration/DiscordHook.kt`), LP 컨텍스트.
- InteractiveChat: `[loohpjames]`, 번지코드, 탭 완성 이름 툴팁(명령어 패킷), 토스트. **패킷 단위 처리가 없어서 `/msg` 나 다른 플러그인 방송 속 [item] 은 바뀌지 않는다.**

## 옮기기 (`config/Migration.kt`)

처음 켤 때 `config.yml` 에 `migrated-from` 이 없고 토큰이 비어 있으면 `plugins/DiscordSRV`·`plugins/InteractiveChat` 에서 옮긴다.

- DiscordSRV: 설정·메시지(이 서버에서 한국어로 고쳐 둔 글 그대로) + `accounts.aof` → `links.yml`. DiscordSRV 파일은 점이 든 열쇠를 써서
  경로 구분자를 바꿔 읽는다(`loadFlat`).
- IC: 동작 값만(켜기·정규식·멘션 수치·탭 완성). 글은 우리 한국어 기본값. 사용자 자리표시는 IC 기본 일곱 개가 아닌 것만.
- **봇 토큰은 파일에서 파일로만.** 로그·메시지에 안 나온다(`MigrationTest`·`Settings.Bot.toString`).

## 저장

| 파일 | 내용 |
|---|---|
| `config.yml` | 봇·채널·중계·콘솔·주제·감시·연결·채팅 꾸미기 |
| `messages.yml` | 게임 메시지 + `discord-*`(디스코드로 보내는 글) |
| `placeholders.yml` | 사용자 자리표시 |
| `links.yml` | 마인크래프트 uuid → 디스코드 id (바뀔 때 바로 저장) |
| `panel.yml` | 서버 현황 패널의 **모양**(관리자가 고침 — 칸 틀·토큰·색·TPS 등급·단추·링크, `config/PanelDesign`) |
| `panel-data.yml` | 패널 메시지의 채널·id · 점검 표시 · 오늘 접속한 사람과 최고 동시(날짜별). 같은 날 잠깐 `panel.yml` 이었다 — 켤 때 옮긴다 |
| `assets/client.jar` · `assets/objects/…` | 그림 자원(위 표) |
| `cache/skins/` | 스킨 그림(textures.minecraft.net) |
| `preview-maps.yml` | 첨부 미리보기에 쓰는 사람마다의 지도 번호 |
| `logs/Console-<날짜>.log` | 디스코드에서 친 콘솔 명령어 기록 |

## 그림 엔진 (`render/` — InteractiveChat 디스코드 애드온 수준)

디스코드로 보내는 [item]·[inv]·[ender] 를 서버가 직접 PNG 로 그린다. **그림 일꾼(낮은 우선순위 데몬 `render.threads` 개)에서만** 그린다.

| 층(뒤가 위) | 어디서 |
|---|---|
| 바닐라 클라이언트 jar | `assets/client.jar` — 없으면 처음 켤 때 Mojang 에서(약 40MB) |
| 자산 객체 | `assets/objects/assets/minecraft/…` — 한글 글꼴(`font/unifont.zip`·`font/include/unifont.json`)·번역(`lang/ko_kr.json`) |
| 서버 팩 | `render.server-pack`(기본 커스텀아이템 `pack.zip`) — overlay·아틀라스 별칭까지 |

| 부분 | 파일 | 마크 원본 |
|---|---|---|
| 글꼴 — bitmap·space·reference·unihex(한글), 굵게·기울임·밑줄·취소선·난독화·그림자 | `font/` | `FontSet`·`BakedGlyph` |
| 툴팁 — 줄은 서버가 `computeTooltipLines` 로, 9조각 배경·테두리·`tooltip_style` | `images/Tooltips.kt` | `TooltipRenderUtil` |
| 아이템 정의(`items/*.json`) — model·condition·select·range_dispatch·composite·special·변환·틴트 | `model/ItemModels.kt` | 26.x `ItemModel` |
| 모델 상속·요소·표시 변환 → 소프트웨어 래스터(z-버퍼, 최근접), GUI 조명 | `model/Models.kt` · `raster/` | `ItemTransform`·`Lighting` |
| 특수 모델 — 상자·셜커·머리(스킨)·피글린·방패·깃발(무늬)·콘듀잇 | `special/SpecialModels.kt` | 각 `*Renderer` 의 `createBodyLayer` |
| 가방 화면 — 플레이어 모델(넓은/슬림, 바깥 층)·갑옷 층(`equipment/*.json`, 염색)·양손 아이템·레벨 | `images/Containers.kt` · `special/PlayerModel.kt` | `InventoryScreen`·`PlayerModel`·`ItemInHandLayer` |
| 3줄 상자(엔더)·칸 장식(개수·내구도) | `images/Containers.kt` | `ContainerScreen`·`renderItemDecorations` |
| 책 쪽·지도·탭리스트·호버(커서) | `images/Pages.kt` · `images/Tablist.kt` · `Cursor.kt` | `BookViewScreen`·`PlayerTabOverlay` |

**스킨**(`Skins.kt` · `Renderer.skinOf`): 접속자는 프로필의 텍스처 주소. 머리 아이템은 스킨 덮어쓰기(리소스팩 텍스처) → 박힌 텍스처 → 그 주인인 접속자 →
프로필 조회(그림 일꾼, Paper 캐시 다음 Mojang) 순. 그림은 마크처럼 다듬는다(몸 기본 층 불투명, 옛 스킨 모자 층 처리).

**아직 대체 그림(파티클 한 장)인 것**: 용 머리·꾸며진 항아리. 몸에 입은 갑옷의 장식(trim) 무늬, 꾸러미 툴팁의 칸 그림은 없다.
미리보기: `PreviewTest` 가 이 PC 의 26.2 클라이언트로 `build/preview/` 에 그린다.

## 디스코드 상호작용 (`bot/Interactions.kt` · `bot/SlashCommands.kt`)

- [inv]·[ender] 그림 밑 **칸 고르기**(선택 메뉴 25개씩 두 줄) → 그 아이템 그림(본인에게만). 셜커·꾸러미 **내용 보기**, 책 **책 보기**(쪽 넘김), 지도 **지도 보기**.
  아이템은 스냅샷에서 꺼낸다(`keywords.timeout-minutes` 뒤 만료). 상자 내용·지도 색은 메인에서 뽑고 그림은 일꾼에서.
- 슬래시: `/연결 코드` · `/접속목록`(탭리스트 그림, `order-by`) · `/정보 [플레이어]` · `/아이템 [칸] [멤버]` · `/인벤 [멤버]` · `/엔더 [멤버]`
  (멤버는 `slash.share.others-roles` 만, 게임 채팅에도 [item] 같은 글).
- 본문 호버(자리표시·`[/명령어]`) → 툴팁 + 커서 그림(메시지 하나에 3장까지).
- `/정보` 는 `sendInfo` 하나 — 패널의 "내 정보" 단추도 같은 길(누른 사람에게만). `{lastseen}`·`{firstjoin}`·`{playtime}` 은 평문으로 바꾼 **뒤**
  끼운다(디스코드 타임스탬프 `<t:…>` 를 MiniMessage 가 먹지 않게). 접속 안 한 사람의 플레이 시간은 `<월드>/players/stats/<uuid>.json`(26.x) 을 봇 일꾼이 읽는다.

## 서버 현황 패널 (`status/Panel.kt` · `PanelLayout.kt` · `PanelStore.kt`, 2026-10-09)

- `panel.channel` 의 봇 메시지 하나를 고친다(`editMessageById`). 지워졌으면(Unknown Message) 새로 올리고, 채널을 바꾸면 옛 것을 지운다.
- 상태: 🟡 켜지는 중(봇 연결 ~ 첫 틱) → 🟢(첫 틱 = "서버 켜진 시간") → 🟠(감시가 멈춤을 봄 — 감시 스레드가 마지막 값으로 그린다) → 🔴(`Bot.stop` 이 JDA 를 닫기 전, 최대 5초 기다림).
  크래시는 적을 수 없다 — "마지막 갱신 `<t:…:R>`" 이 대신 알린다.
- 모양은 순수 `PanelLayout.view`(`PanelTest`) — `panel.yml` 의 칸 틀에 토큰을 끼우고, 값이 없는(null) 토큰이 든 줄은 뺀다. `ansi: true` 칸은 `&` 색 코드를
  ANSI(디스코드 코드 블록이 그리는 여덟 색)로 바꾼다. 필드 1024자·임베드 6000자 안(`/디스코드 관리 검증` 이 200명으로 네 상태를 그려 본다).
- 단추 열쇠 `inmcd:panel:me|refresh|notify` — `Bot` 이 `Interactions` 보다 먼저 패널에 묻는다.

## 첨부 그림 → 게임 (`preview/`)

디스코드 첨부는 게임에 `[이름] (원본)`. 그림이면 이름을 누르면(`/디스코드 그림 <id>`) **지도 제작대 화면**에 지도로 보인다 — 패킷을 쓰지 않는다
(제작대 화면은 첫 칸의 지도를 크게 그린다, 색은 `Player.sendMap`). GIF 는 프레임마다 다시 보내고 아래 두 줄에 재생 막대. 지도는 사람마다 한 장
(`preview-maps.yml`)을 다시 쓴다. 디스코드 CDN 만, `attachments.max-mb` 까지만 받는다.
