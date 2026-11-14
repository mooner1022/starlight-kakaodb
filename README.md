# KakaoTalk DB (StarLight 플러그인)

카카오톡 데이터베이스(`KakaoTalk.db`, `KakaoTalk2.db`)를 직접 읽어 메시지·삭제·입퇴장 같은 이벤트를 StarLight 프로젝트에 전달하는 플러그인입니다.
`KakaoDBRouter`(WebSocket 브리지)를 StarLight 플러그인으로 다시 구현하고 기능을 넓혔습니다.

알림 기반 수신과 비교하면 이런 점이 다릅니다.

- 방 알림을 꺼 두었거나 카카오톡을 보고 있어도 메시지를 받습니다.
- 정확한 방/유저 ID, 멘션, 답장 원본, 미디어 정보를 함께 받습니다.
- 메시지 삭제, 가리기, 입장, 퇴장, 강퇴, 부방장·방장 변경 같은 피드 이벤트를 받습니다.
- 방 목록, 멤버, 친구, 지난 메시지, 안 읽은 사람 수를 조회할 수 있습니다.

## 요구 사항

- StarLight nightly (플러그인 API 1.0.0). StarLight 1.1.0(스냅샷 1000147) 이상에서는 StarLight AI가 이 플러그인의 API·이벤트 설명과 가이드를 읽어요(아래 "StarLight AI 문서").
- 플러그인이 카카오톡 DB 파일을 **읽을 수 있어야** 합니다. 카카오톡의 비공개 폴더(`/data/data/com.kakao.talk/databases`)에 있는 파일이라 보통은 읽을 수 없습니다.
  - **직접 모드**(현재 지원): 파일을 이미 읽을 수 있는 경우입니다. 권한을 직접 설정했거나, SELinux가 꺼진 환경(redroid 등)이거나, 파일을 다른 곳으로 복사해 두고 그 폴더를 지정한 경우가 해당돼요.
    - SQLite는 WAL 모드 DB를 읽기 전용으로 열 때도 `-shm` 파일에 쓰기 권한이 필요해요. 폴더에는 실행(x) 권한이 있어야 하고, 파일 변경을 바로 감지하려면 폴더 읽기(r) 권한도 있어야 해요.
  - **루트 모드**: 루팅된 기기(Android 10 이상)용입니다. su로 루트 도우미를 띄워 카카오톡 DB를 그 자리에서 읽어요. 아래 "루트 모드" 참고.
  - **공유 UID 모드**: 카카오톡과 같은 UID로 설치한 별도 도우미를 통해 읽어요. 아래 "공유 UID 모드" 참고.
  - SELinux가 enforcing이면 직접 모드에서는 파일 권한과 상관없이 읽기가 막힐 수 있어요. 루트 모드는 이 제약을 받지 않아요.

## 빌드

```sh
./gradlew :app:assembleReleaseSlp     # app/build/outputs/starlight/release/kakaodb-release.slp
./gradlew :app:testReleaseUnitTest    # JVM 단위 테스트
```

Gradle 8.13은 JDK 24 이상에서 실행되지 않으니 JDK 17~21을 쓰세요. KSP 2.3.13이 AGP 8.12 이상을 요구해서 AGP는 StarLight 앱과 같은 8.13.2를 써요.

플러그인은 StarLight의 `PluginCore`와 `ConfigDSL`에 맞춰 컴파일해요. 두 AAR은 `app/libs`에 있고, StarLight 저장소(nightly)에서 `./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease`로 빌드해 `PluginCore/build/outputs/aar/PluginCore-full-release.aar`를 `PluginCore-release.aar`로, `ConfigDSL/build/outputs/aar/ConfigDSL-release.aar`를 그대로 복사하면 돼요. PluginCore는 1.1.0부터 `full`/`play` 플레이버로 나뉘는데, 플러그인은 `full`에 맞춰 빌드해요. `getSetupStructure()` 같은 API가 ConfigDSL 타입을 쓰니 둘은 같은 StarLight 리비전에서 가져오세요.

### StarLight AI 문서

StarLight 1.1.0의 StarLight AI는 플러그인 패키지에서 두 가지를 읽어요. 둘 다 빌드에 포함되고, 따로 등록할 것은 없어요.

- **KDoc**: `app/build.gradle`에 적용한 KSP로 StarLight Gradle 플러그인(0.5.0)이 공개 클래스의 KDoc과 Kotlin 시그니처를 `starlight/agent/kakaodb.api.json`으로 뽑아요. 스크립트가 닿는 클래스(`KakaoClient`, `script/`의 객체, `MediaInfo`·`LocationInfo`·`EmoticonInfo`, `event/`의 이벤트)는 공개 멤버마다 KDoc을 달아 주세요. 이벤트 클래스의 KDoc에는 스크립트 함수 인자마다 `@param`을 써요. 문서는 영어로 쓰고, 규칙은 StarLight 저장소의 `docs/agent-docs.md` 3절을 따라요.
- **가이드**: `app/src/main/assets/agent/guides/<id>.md`. `title`과 `summary`가 있는 front matter로 시작해야 하고 BOM이 없어야 해요. KDoc에서는 `@see guide:<id>`로, 가이드끼리는 `guide:<id>` 링크로 가리켜요.

`assembleReleaseSlp`가 `i: [StarLight] StarLight AI docs: … classes in starlight/agent/kakaodb.api.json, … guides` 줄로 결과를 알려 줘요. 기기에서는 StarLight의 **설정 → 개발자 모드 → StarLight AI 문서**에서 AI가 보는 그대로 확인할 수 있어요.

### 설정 도구 서명 키

공유 UID 설정 도구(`:setup`)는 패치용 서명 키를 자기 앱 데이터에 보관해요. 다른 키로 서명된 설정 도구는 기존 도구를 제거해야 설치할 수 있고, 제거하면 이 키도 지워져요. 그래서 릴리스 빌드는 고정된 키로 서명해야 해요. `~/.gradle/gradle.properties`(저장소 밖)에 다음을 넣으세요.

```properties
kakaodb.setup.storeFile=C:/keys/kakaodb-setup.jks
kakaodb.setup.storePassword=...
kakaodb.setup.keyAlias=setup
kakaodb.setup.keyPassword=...
```

- 설정하지 않으면 이 PC의 debug 키로 서명하고 빌드 중에 경고를 띄워요.
- 이미 debug 키로 서명된 설정 도구를 설치해 두었다면, 이 PC의 `~/.android/debug.keystore` 사본을 지정하면 그대로 업데이트할 수 있어요(비밀번호 `android`, 별칭 `androiddebugkey`).
- 이 keystore는 저장소에 올리지 마세요. 이 키를 가진 사람은 설정 도구의 "업데이트"를 만들어 패치용 서명 키를 빼낼 수 있어요.

## 설정

| 분류 | 항목 | 설명 |
|---|---|---|
| 연결 | 상태 | 연결 여부, 계정, 전달한 이벤트 수, 경고를 보여줘요. |
| | 연결/연결 끊기, 다시 연결 | |
| | 자동 연결 | 플러그인이 켜지면 바로 연결합니다. |
| 데이터베이스 | 접근 방식 | 직접 / 루트 / 공유 UID. 공유 UID의 처음 패치·설치는 초기 설정에서 해요. |
| | 데이터베이스 폴더 | `KakaoTalk.db`와 `KakaoTalk2.db`가 있는 폴더입니다. 기본값은 `/data/data/com.kakao.talk/databases`예요. |
| | 변경 확인 주기 | 파일 변경 감지와 별도로 주기적으로 확인하는 간격입니다(끔/0.5/1/2/5초, 기본 1초). |
| 이벤트 | 내 메시지 무시 | 기본값은 켜짐이에요. 봇이 보낸 메시지로 다시 이벤트가 발생하는 걸 막아요. |
| | onMessage 호환 이벤트, onMessageDeleted 호환 이벤트 | 아래 "호환 이벤트" 참고 |
| 전송 | 전송 간격 | 메시지 사이 최소 간격입니다(기본 600ms). |
| | 전송 가능한 방 | 답장 기능을 확보한 방 목록입니다(아래 "메시지 보내기" 참고). |
| 계정 | 내 사용자 ID / 이름 | 비워 두면 DB에서 찾아요. 찾지 못하면 연결 상태에 경고가 표시돼요. |
| 공유 UID 도우미 | 도우미만 다시 패치, 패치된 도우미 설치 | 접근 방식과 상관없이 이 분류는 항상 보여요. 플러그인을 업데이트한 뒤 도우미를 맞출 때 써요. 아래 "공유 UID 모드" 참고 |
| | 서명 키 백업, 서명 키 복원 | 플러그인을 지우기 전에 백업하고, 처음 패치하기 전에 복원해요. |
| 로그 | 이벤트 로그, 디버그 로그 | |

데이터베이스 관련 설정을 바꾸면 연결된 상태에서 자동으로 다시 연결합니다. 연결에 실패한 상태나 연결하는 도중에 바꿨을 때도 새 설정으로 다시 시도해요.

### 연결 실패와 자동 재시도

연결하지 못하면 플러그인이 알아서 다시 시도해요. 재부팅 직후처럼 카카오톡이 아직 DB를 열지 않았거나 도우미가 준비되기 전에 StarLight가 먼저 뜬 경우를 위한 거예요.

- 5초 뒤에 처음 다시 시도하고, 실패할 때마다 간격을 두 배로 늘려 최대 5분마다 시도해요. 연결되거나 직접 **연결**/**다시 연결**을 누르면 간격은 처음으로 돌아가요.
- 시도하는 동안 상태는 "오류"로 남고, 설명에 다시 시도 중이라고 표시돼요. 같은 이유로 계속 실패하면 로그와 초기 설정 창은 처음 한 번만 떠요.
- 그만두려면 연결 버튼(**재시도 중지**로 바뀌어 있어요)을 누르세요.
- 사용자가 해결해야 하는 실패는 다시 시도하지 않아요: `su`가 없거나 거부됐을 때, 루팅 관리 앱의 권한 요청에 응답이 없었을 때, 루트 도우미를 준비하지 못했거나 도우미가 바로 종료됐을 때예요(도우미가 제때 응답하지 않은 경우는 다시 시도해요). 원인을 해결한 뒤 **다시 연결**을 누르세요.
- 루트 모드에서는 간격이 30초를 넘으면 도우미가 이미 종료된 뒤라서 시도할 때마다 `su`를 실행해요. 루팅 관리 앱이 그때마다 알림을 띄울 수 있어요.

### 초기 설정

플러그인이 DB를 읽는 방법을 모르거나 읽지 못하면 StarLight가 **초기 설정** 창을 띄워요. 이 창은 위 표의 `접근 방식`과 `데이터베이스 폴더`를 묻고, 저장되는 값은 플러그인 설정과 같아요. 접근 방식을 **공유 UID**로 고르면 처음 설치에 필요한 세 단계(카카오톡·도우미 패치, 카카오톡 설치, 도우미 설치)가 그 아래에 나타나요(다른 방식을 고르면 숨겨져요). 플러그인을 업데이트한 뒤 도우미를 다시 패치하는 일과 서명 키 백업·복원은 플러그인 설정의 **공유 UID 도우미**에서 해요.

- **열리는 때**
  - 접근 방식을 한 번도 저장하지 않았고 아직 연결되지 않았을 때(처음 설치). 창 위에는 기본 안내문이 나와요.
  - 접근 방식을 저장한 뒤 연결이 실패했을 때. 창 위에 실패한 이유가 나와요.
  - 플러그인 목록의 "초기 설정" 버튼(항상)이나 목록 위 알림의 "설정하기"에서도 열 수 있어요. 이때는 지금 쓰는 값이 미리 선택돼 있어요.
  - 직접 모드로 이미 잘 읽고 있다면 접근 방식을 저장한 적이 없어도 묻지 않아요.
- **"완료"를 누르면** 고른 방식으로 파일을 읽을 수 있는지 먼저 확인해요. 루트 모드는 `su`를 실행하고(허용 창이 뜰 수 있어요), 공유 UID 모드는 도우미에 연결해 봐요. 실패하면 이유를 창 아래에 보여 주고 아무것도 저장하지 않아요. 통과하면 저장하고 바로 다시 연결해요.
- **"나중에"** 로 닫으면 같은 이유로는 다시 묻지 않아요(목록의 "설정 필요" 표시와 알림은 남아요). 실패 이유가 달라지면 다시 물어요.
- 공유 UID 단계는 번호 순서대로 진행한 뒤 **완료**를 누르세요. 완료가 도우미에 연결해 확인해요. 패치와 설치는 창을 닫아도 계속돼요.
  - 고른 APK의 사본은 패치가 끝나면(실패해도) 지우고, 패치된 카카오톡은 설치에 성공하면 지워요. 둘 다 카카오톡만큼 커서예요. 설치에 실패하면 남겨 두니 다시 설치할 수 있고, 성공한 뒤 다시 설치하려면 1단계부터 하세요. 패치된 도우미는 작아서 남겨 둬요.
- 이 기능은 PluginCore의 초기 설정(`isSetupRequired`, `getSetupStructure`, `onSetup`, `requestSetup`)을 써요. 이 기능이 없는 StarLight에서는 창이 열리지 않아요. 접근 방식과 폴더는 플러그인 설정에서 바꿀 수 있지만, 공유 UID의 첫 패치·설치는 설정에 없으니 독립 실행 설정 도구(`setup/`)를 쓰세요.

## 공유 UID 모드

루트 없이 사용하려면 플러그인 목록에서 KakaoTalk DB의 **초기 설정**을 열고 접근 방식을 **공유 UID**로 고르세요. 처음 패치·설치는 거기서 하고, 도우미 업데이트와 서명 키 관리는 플러그인 설정의 **공유 UID 도우미**에서 해요. 플러그인에 [HayulGUI](https://github.com/dolidolih/HayulGUI)의 패치 엔진과 `sharedd` APK가 포함되어 있습니다. StarLight 자체는 패치하지 않습니다.

```sh
./gradlew :app:assembleReleaseSlp
```

1. 카카오톡 데이터를 백업하고 카카오톡 APK 또는 base와 split이 모두 담긴 XAPK/APKM/APKS 파일을 준비합니다.
   사용하는 Android 버전이 APK의 최소 지원 버전 이상이어야 합니다. 패치 전에 이 조건을 확인합니다.
2. 기존 설정 도구를 사용했다면 먼저 그 앱의 **서명 키 백업**을 사용하고, 플러그인 설정의 **공유 UID 도우미** 아래 **서명 키 복원**으로 가져옵니다. 새로운 설치라면 이 단계를 건너뜁니다. 키 없이 1단계를 누르면 새 키를 만들기 전에 한 번 확인해요.
3. 초기 설정에서 **1. 카카오톡과 도우미 패치**를 눌러 파일을 선택합니다. 같은 `sharedUserId`와 서명 키로 패치한 후 **2. 패치된 카카오톡 설치**, **3. 패치된 도우미 설치**로 각각 설치합니다. 기존 설치의 서명이나 UID가 다르면 기존 앱을 제거해야 합니다.
4. 도우미 앱(**KakaoDB 도우미**)을 열어 상태를 확인합니다. 카카오톡을 실행하고 로그인해 데이터베이스가 생성된 다음 초기 설정의 **완료**를 누릅니다. UID 일치만으로는 DB가 생성되지 않습니다.

도우미 앱 화면은 아래 항목을 보여 주고 2초마다 갱신돼요. 도우미는 화면을 열지 않아도 StarLight가 연결할 때 백그라운드에서 실행돼요.

- 공유 UID 패치 여부, 카카오톡과의 UID 일치, StarLight 설치
- `KakaoTalk.db`/`KakaoTalk2.db`의 상태(`-wal`/`-shm` 포함), `crypto_user_database`와 SQLCipher의 상태
- StarLight 연결 세션 수와 마지막 연결 시각, 거부한 연결
- 도우미 빌드 ID와 프로토콜 버전

### 플러그인을 업데이트한 뒤

도우미는 직접 설치하는 앱이라 플러그인과 따로 놀 수 있어요. 그래서 도우미가 자기 빌드 ID를 알려 주고, 플러그인은 자기와 다르면 연결 상태에 경고를 띄워요. 빌드 ID는 도우미 소스(`sharedd`, `rootd`의 소스)에서 계산해요. 프로토콜 버전이 다르면 연결하지 않고 다시 설치하라고 안내해요.

1. KakaoTalk DB의 플러그인 설정을 열고 **공유 UID 도우미** 아래의 **도우미만 다시 패치**를 누릅니다. 기존 서명 키로 내장 도우미만 패치하므로 카카오톡 APK가 필요 없어요.
2. 바로 아래 **패치된 도우미 설치**로 설치합니다. 연결 중이던 플러그인은 바로 다시 연결해요.

도우미 앱 화면에서 설치된 도우미 빌드를 확인할 수 있어요.

서명 키는 StarLight의 비공개 플러그인 데이터에 저장됩니다. StarLight를 삭제하거나 플러그인을 데이터와 함께 제거하기 전에 플러그인 설정의 **서명 키 백업**으로 PKCS#12 파일을 저장하세요. 새 설치에서는 처음 패치하기 전에 플러그인 설정의 **서명 키 복원**을 사용합니다. 기존 키가 있는 경우 덮어쓰지 않습니다. 이전 설정 도구의 키도 백업·복원으로 옮길 수 있으니 이전이 끝나기 전에 해당 앱을 삭제하지 마세요. 공유 UID 지원 여부는 Android 버전과 기기 환경에 따라 다를 수 있습니다.

공유 UID 모드는 설치된 도우미 서비스를 바인딩하고, SQL과 파일 변경 알림을 소켓 쌍으로 주고받습니다. `crypto_user_database`도 SQLCipher로 읽습니다. 세션의 읽기는 `poll`로 30초 타임아웃을 걸어, 도우미가 멈추면 연결을 끊고 다시 연결해요.

설정 도구에 포함된 HayulGUI 패치 엔진은 `vendor/HayulGUI`에 있고, 이 저장소에서 고친 부분은 `[kakaodb]` 주석으로 표시했어요. 특히 매니페스트에 새 속성을 넣을 때 리소스 ID 순서를 지키도록 고쳤어요. 원래는 맨 앞에 넣어서 `appComponentFactory`가 없는 APK(도우미)의 `theme`, `label`, `icon`, `allowBackup`이 무시됐어요. 테스트는 `./gradlew :hayulcore:testReleaseUnitTest`로 돌려요.

도우미는 자신과 카카오톡의 UID가 같은지, 호출자가 설치된 StarLight의 UID인지 확인하고 카카오톡의 데이터베이스 디렉터리만 허용합니다. StarLight 내부의 다른 플러그인은 같은 UID로 실행되므로 이 검사로 구분할 수 없습니다. StarLight가 설치된 도우미의 UID도 카카오톡의 UID와 비교합니다.

## 루트 모드

Android 10 이상에서는 다른 앱 데이터에 SELinux MLS 카테고리가 붙어 있어서, su로 파일 권한을 열어도 StarLight 프로세스는 카카오톡 DB를 읽을 수 없어요. 그래서 루트 모드는 DB를 직접 열지 않고, root로 도는 도우미 프로세스에게 쿼리를 맡깁니다.

- **도우미**: `rootd` 모듈(Java)이 설치하지 않는 APK를 따로 빌드하고, 플러그인은 이걸 asset `rootd.apk`로 실어요. StarLight가 풀어 둔 이 파일을 연결할 때 su로 `/data/local/tmp/dev.mooner.kakaodb/build/<빌드>/`에 복사하고 `app_process`로 실행해요. 프로세스 이름은 `kakaodb-rootd`이고, 로그는 `/data/local/tmp/dev.mooner.kakaodb/rootd-<uid>.log`와 logcat(태그 `kakaodb-rootd`)에 남아요.
  - APK 모듈인 이유는 SQLCipher(`net.zetetic:sqlcipher-android`) 때문이에요. AGP가 aar과 네이티브 라이브러리를 처리하고, 도우미는 실행될 때 자기 APK에서 `libsqlcipher.so`를 꺼내 로드해요.
  - `libsqlcipher.so`는 ABI마다 4~5MB라 `arm64-v8a`, `x86_64`만 넣어요. 32비트 전용 기기라면 `rootd/build.gradle`의 `abiFilters`에 `armeabi-v7a`를 추가하세요.
  - StarLight는 `assetRevision`이 바뀔 때만 asset을 다시 풀어요. 그래서 `app/build.gradle`이 `rootd` 소스·`rootd/build.gradle`·`src/main/assets`의 내용으로 `assetRevision`을 계산해요. 이 파일들에 드러나지 않는 변경(AGP 버전 등)을 기기에 반영하려면 `assetRevisionOf`의 `SALT`를 바꿔 주세요.
- **읽기**: 도우미가 원본 DB를 읽기 전용으로 열고, 플러그인은 추상 UNIX 소켓으로 SQL을 보내 결과를 받아요. 복사하지 않으니 카카오톡이 커밋하는 대로 바로 보이고, 파일 변경은 도우미의 inotify가 알려 줘요(변경 확인 주기 설정도 그대로 동작해요).
- **보안**: 소켓은 StarLight의 uid만 접속할 수 있고, 같은 uid를 쓰는 다른 플러그인을 막기 위해 연결마다 토큰을 확인해요. 플러그인 쪽도 소켓 주인이 root인지 확인한 뒤에만 토큰을 보내요.
- **생명주기**: 연결이 모두 끊기고 30초가 지나면 도우미는 스스로 종료돼요. StarLight를 다시 시작하거나 플러그인을 업데이트하면 이전 도우미를 종료하고 새로 띄워요(이때 루팅 관리 앱이 su 알림을 띄울 수 있어요).
- **주의**: `KakaoTalk.db-wal`은 있는데 `-shm`이 없으면 연결하지 않아요. 도우미가 `-shm`을 만들면 카카오톡이 자기 DB를 못 열 수 있어서예요. 카카오톡을 한 번 실행한 뒤 다시 연결하세요.
- Magisk 기준으로 만든 구조예요. 다른 루팅 방식에서 "도우미를 시작했지만 연결하지 못했어요"가 나오면 SELinux가 앱 → 도우미 소켓 연결을 막는 경우일 수 있어요.

### 친구 목록 (`crypto_user_database`)

최근 카카오톡은 `KakaoTalk2.db`의 `friends` 테이블을 없애고, 친구 목록을 SQLCipher로 암호화된 `crypto_user_database`의 `user` 테이블로 옮겼어요. 그래서 `db2.friends`가 없으면 이 파일에서 친구를 읽어요.

- 암호는 저장돼 있지 않고, 카카오톡 DataStore(`files/datastore/Feature_DataStore.pref.preferences_pb`)의 `userDbPassPhraseSalt` 값으로 PBKDF2를 돌려 만들어요. 루트 도우미가 이 값을 읽고 DB를 읽기 전용으로 그 자리에서 열어요.
- 친구 이름은 `nickname` 컬럼에서 읽고, `user`에 없는 카카오 채널은 `talk_channel`에서 이름을 찾아요. `user`의 나머지 컬럼 구성은 확실하지 않아서, 연결할 때 실제 컬럼 목록을 로그에 남겨요.
- 루트 모드와 공유 UID 모드에서 읽을 수 있어요. 직접 모드에서는 친구가 "알 수 없는 사용자"로 보이고, 연결 상태에 경고가 떠요.
- 열거나 읽다 실패해도 메시지 이벤트는 계속 전달돼요(친구 이름만 비어요). 연결 상태에 원인이 표시되고, 5분마다 다시 시도해요.
- 이 부분은 [Iris](https://github.com/dolidolih/Iris)의 구현을 옮겨 왔어요(MIT, `THIRD_PARTY_NOTICES.md` 참고).

## 이벤트

프로젝트 설정에서 사용할 이벤트를 켜야 호출됩니다.

| 이벤트 ID | 함수 | 인자 |
|---|---|---|
| `kakaodb.message.create` | `onKakaoMessage(msg)` | `KakaoMessage` |
| `kakaodb.message.delete` | `onKakaoMessageDeleted(event)` | `KakaoMessageDeletedEvent` |
| `kakaodb.message.hide` | `onKakaoMessageHidden(event)` | `KakaoMessageHiddenEvent` |
| `kakaodb.member.join` | `onKakaoMemberJoined(event)` | `KakaoMemberJoinedEvent` |
| `kakaodb.member.leave` | `onKakaoMemberLeft(event)` | `KakaoMemberLeftEvent` |
| `kakaodb.member.role` | `onKakaoMemberRoleChanged(event)` | `KakaoMemberRoleChangedEvent` |
| `kakaodb.room.feed` | `onKakaoFeed(event)` | `KakaoFeedEvent` (모든 피드, 위 이벤트에 해당하는 것도 포함) |
| `kakaodb.compat.on_message` | `onMessage(msg)` | StarLight `Message` |
| `kakaodb.compat.on_message_deleted` | `onMessageDeleted(msg)` | StarLight `DeletedMessage` |

이벤트는 연결한 뒤에 DB에 새로 추가된 기록에 대해서만 발생합니다. 전역 전원이 꺼져 있으면 전달하지 않아요.

### 호환 이벤트

기존 `onMessage`/`onMessageDeleted` 스크립트를 그대로 DB 기반으로 돌릴 수 있게 하는 이벤트입니다. 플러그인 설정에서 켜고, 프로젝트에서도 켜야 해요.

- StarLight 기본 `onMessage`(알림 기반)와 함께 켜면 같은 메시지를 **두 번** 받아요. 둘 중 하나만 켜세요.
- DB에는 프로필 사진이 없어서 `sender.profileBitmap`은 1×1 빈 이미지이고, `image`는 항상 `null`이에요.
- StarLight 전역 `Events.Message.Create` 이벤트는 발생시키지 않습니다. 알림 기반 수신과 겹치지 않게 하려는 거예요.

## 스크립트 객체

- **ID는 모두 문자열입니다.** 카카오톡 ID는 2^53을 넘어서 JS 숫자로는 정확히 표현되지 않아요. 조회 함수에 ID를 넘길 때도 문자열로 넘기세요.
- 불리언 값은 `msg.isMine`처럼 속성으로 읽습니다.
  - 예외: StarLight `ChatRoom` 인터페이스에서 온 `isGroupChat`/`isDebugRoom`은 `room.isGroupChat()`처럼 호출하세요.
- 목록은 Java `List`입니다. `list.size()`, `list.get(i)`로 다루세요.
- 시간(`createdAt`)은 밀리초 단위입니다. `new Date(msg.createdAt)`로 쓸 수 있어요.

### KakaoMessage

| 이름 | 설명 |
|---|---|
| `id`, `roomId`, `senderId` | 로그 ID, 방 ID, 보낸 사람 ID |
| `text` | 본문 (복호화됨) |
| `type`, `typeCode` | `TEXT`, `PHOTO`, `REPLY`, `FILE` 등 (모르는 타입은 `UNKNOWN`), 카카오톡 타입 번호 |
| `createdAt` | 보낸 시각 (ms) |
| `isMine`, `isDeleted`, `isEmoticon` | |
| `mentions`, `hasMention`, `mentionsMe` | 멘션 목록(`userId`, `positions`, `length`), 멘션 여부, 나를 멘션했는지 |
| `replyTo` | 답장 원본: `id`, `senderId`, `text`, `type`, `typeCode`. 스레드 메시지는 아래 참고 |
| `threadId`, `isThreadMessage`, `scope` | 오픈채팅 스레드(답글): 스레드가 달린 원본 메시지의 로그 ID, 스레드 메시지인지, 카카오톡 scope 값 (1: 채팅방, 3: 채팅방에도 보이는 스레드 메시지) |
| `getThreadRoot()` | 스레드가 달린 원본 메시지(`KakaoMessage`). 스레드 메시지가 아니거나 DB에 없으면 null이고, 호출할 때마다 DB에서 읽어요 |
| `media` | 사진·동영상·음성·파일: `kind`, `url`, `thumbnailUrl`, `width`, `height`, `size`, `mimeType`, `name`, `expiresAt`, `durationSeconds` |
| `location`, `emoticon` | 위치(`latitude`, `longitude`, `address`, `title`), 이모티콘 정보 |
| `attachmentJson` | 복호화된 첨부 원본 JSON 문자열 |
| `room`, `sender` | `KakaoRoom`, `KakaoUser` |
| `db` | `KakaoClient` |
| `reply(text)`, `markAsRead()` | 같은 방에 전송 / 읽음 처리 |
| `getUnreadCount()` | 이 메시지를 안 읽은 멤버 수. 호출할 때마다 DB에서 읽고, 알 수 없으면 -1 |
| `toJson()` | 원본 기록 JSON |

#### 답장과 스레드

오픈채팅에서는 메시지에 대한 응답이 두 가지 방식으로 저장돼요.

- **답장**: `type`이 `REPLY`이고, 원본은 `replyTo`에 있어요.
- **스레드 메시지(답글)**: `type`은 `TEXT` 같은 보통 타입이라 `replyTo`가 null이에요. 원본은 `threadId`와 `getThreadRoot()`로 찾아요.

스레드 안에서 답장하면 두 정보가 모두 있어요. 이때 `replyTo`는 인용한 메시지이고, `threadId`는 스레드가 달린 원본이에요. 어떤 메시지에 대한 응답인지 확인하려면 둘 다 보세요.

```js
var target = msg.replyTo != null ? msg.replyTo.id : msg.threadId;
```

### KakaoRoom (StarLight `ChatRoom` 구현)

| 이름 | 설명 |
|---|---|
| `id`, `name`, `type` | `type`은 `DirectChat`, `MultiChat`, `OM`(오픈 그룹), `OD`(오픈 1:1), `MemoChat` 등 |
| `isOpenChat`, `isDirectChat`, `isSecretChat`, `isMemoChat`, `isGroupChat()` | |
| `customName`, `notice`, `memberCount`, `unreadMessageCount` | |
| `openLink` | 오픈채팅 정보: `id`, `name`, `url`, `imageUrl`, `description`, `hostId`, `memberLimit`, `type` |
| `canSend` | 지금 이 방에 보낼 수 있는지 |
| `send(text)`, `markAsRead()` | |
| `getMembers()`, `getMessages(limit?)`, `getMessage(id)` | DB 조회 |
| `toJson()` | |

### KakaoUser

`id`, `name`, `kind`(`self`/`friend`/`open_member`/`unknown`), `profileImageUrl`, `fullProfileImageUrl`, `statusMessage`(친구), `memberType`(오픈채팅: `HOST`/`MANAGER`/`NORMAL`/`BOT`), `isMe`, `isFriend`, `isHost`, `isManager`, `toJson()`

### 피드 이벤트 공통

`room`, `logId`(피드 기록 ID), `actor`(피드 기록의 사용자, 보통 그 일을 한 사람), `createdAt`, `db`

| 객체 | 추가 속성 |
|---|---|
| `KakaoMessageDeletedEvent` | `messageId`, `message` (DB에 남아 있으면 `KakaoMessage`, 없으면 null) |
| `KakaoMessageHiddenEvent` | `messageIds`, `messages` |
| `KakaoMemberJoinedEvent` | `members`, `inviter` (오픈채팅 입장이면 null), `isOpenLinkJoin` |
| `KakaoMemberLeftEvent` | `member`, `isKicked` |
| `KakaoMemberRoleChangedEvent` | `change` (`MANAGER_GRANTED`/`MANAGER_REVOKED`/`HOST_CHANGED`), `members`, `previousHost` |
| `KakaoFeedEvent` | `feedType`, `feedTypeCode`, `members` (`id`, `name`), `feedJson` |

### KakaoClient (전역 `KakaoDB`, `event.db`)

전역 `KakaoDB`와 이벤트 객체의 `db`(`msg.db`, `event.db`)는 같은 객체예요. NeonJS도 StarLight 스냅샷 1000105부터 플러그인 API 전역 객체를 받아요. 그보다 오래된 StarLight의 NeonJS에서는 `msg.db`/`event.db`를 쓰세요.

| 함수 | 설명 |
|---|---|
| `isConnected` | 연결 여부 |
| `getSelf()` | 내 정보 |
| `getRoom(roomId)`, `getRooms()` | 방, 전체 방 목록 (최근 활동 순) |
| `getFriends()`, `getUser(userId, roomId?)`, `getMembers(roomId)` | `roomId`로 오픈채팅을 지정하면 그 방의 프로필을 돌려줘요 |
| `getOpenLinks()` | 내 오픈채팅 링크 |
| `getMessages(roomId, limit?, beforeMessageId?)` | 최근 메시지, 또는 특정 메시지 이전 메시지 (오래된 순, 최대 1000개) |
| `getMessage(roomId, messageId)`, `getUnreadCount(roomId, messageId)` | |
| `send(roomId, text)`, `markAsRead(roomId)`, `canSend(roomId)`, `getSendableRoomIds()` | |

조회 함수는 DB 응답을 기다리는 동안 멈춰 있으니(최대 15초) 이벤트 함수 안에서 쓰세요. 연결되지 않은 상태에서 호출하면 예외가 발생합니다.

`getRoom`과 `getRooms`는 호출한 시점의 방을 DB에서 읽어요. 이벤트 객체의 `room`(`msg.room` 등)은 플러그인이 캐시해 둔 값이라, `unreadMessageCount` 같은 값은 최대 5분 전 것일 수 있어요. 지금 값이 필요하면 `KakaoDB.getRoom(room.id)`로 다시 읽으세요.

## 예시

Rhino:

```js
function onKakaoMessage(msg) {
    if (msg.text == "!읽음") {
        msg.reply("안 읽은 사람: " + msg.getUnreadCount() + "명");
    }
    if (msg.replyTo != null && msg.text == "!원본") {
        msg.reply("원본: " + msg.replyTo.text);
    }
    // 오픈채팅에서 봇 메시지에 스레드로 단 답글
    if (msg.isThreadMessage) {
        var root = msg.getThreadRoot();
        if (root != null && root.isMine)
            msg.reply("내 메시지에 답글을 달았어요: " + root.text);
    }
}

function onKakaoMemberJoined(event) {
    for (var i = 0; i < event.members.size(); i++)
        event.room.send(event.members.get(i).name + "님 환영합니다!");
}

function onKakaoMessageDeleted(event) {
    if (event.message != null)
        event.room.send(event.message.sender.name + "님이 삭제한 메시지: " + event.message.text);
}
```

NeonJS:

```js
export function onKakaoMessage(msg) {
    if (msg.text === "!방목록") {
        const rooms = msg.db.getRooms();
        const names = [];
        for (let i = 0; i < Math.min(rooms.size(), 10); i++)
            names.push(rooms.get(i).name);
        msg.reply(names.join("\n"));
    }
}
```

## 메시지 보내기

DB로는 메시지를 보낼 수 없어서, StarLight처럼 **카카오톡 알림의 답장 기능**으로 보냅니다.

- StarLight가 시작된 뒤 그 방의 알림을 한 번이라도 받아야 보낼 수 있어요. 알림을 끈 방에는 보낼 수 없습니다.
- `send()`는 대기열에 넣고 바로 `true`를 돌려줘요. 실제 전송은 전송 간격을 두고 순서대로 이뤄집니다. 보낼 수 없는 방이면 `false`예요.
- 카카오톡이 재시작되는 등의 이유로 답장 기능이 만료되면 그 방의 새 알림이 올 때까지 보낼 수 없어요(로그에 표시돼요).
- 알림 태그를 방 ID로 사용합니다. 알림의 `chatLogId`와 DB 기록을 대조해서 확인하고, 다르면 경고를 남긴 뒤 알림 태그를 그 방에 연결해요.

## 구조

```
dev.mooner.kakaodb
├─ core/        Android에 의존하지 않는 DB 해석 계층 (JVM 단위 테스트 대상)
│  ├─ crypto/   메시지 복호화 (키 생성 방식을 BouncyCastle과 대조해 검증)
│  ├─ parser/   기록 → 모델 (멘션, 답장, 미디어, 피드)
│  ├─ schema/   카카오톡 버전마다 다른 테이블/컬럼 확인 (crypto_user_database 포함)
│  └─ DatabaseDecoder  변경 감지(data_version), 이벤트 분류, 조회
├─ db/          Android SQLite 세션, 파일 접근 방식, 변경 감시
├─ send/        알림 답장 기능 보관, 전송 대기열
├─ setup/       내장 패치·설치 기능(EmbeddedSetup), 초기 설정의 공유 UID 단계와 설정의 도우미 관리(SharedUidSteps), 초기 설정 판단(InitialSetup)
├─ script/      스크립트에 넘기는 객체
├─ event/       프로젝트 이벤트 정의
├─ api/         전역 KakaoDB
└─ KakaoDBPlugin

rootd/          루트 도우미 APK (app_process로 실행)
├─ src/protocol/  플러그인·도우미가 같이 컴파일하는 통신 형식 (Protocol, WalFiles, ServedSql)
└─ src/main/      RootDaemon, CryptoUserDatabase (SQLCipher)
sharedd/        공유 UID 도우미 APK: rootd 소스 + SharedService, 상태 화면(HelperChecks, MainActivity)
setup/          독립 실행 설정 도구 APK: 카카오톡·도우미 패치와 설치. 플러그인에는 포함되지 않아요(플러그인은 같은 패치 기능을 초기 설정과 플러그인 설정에 내장)
vendor/HayulGUI/  패치 엔진(:hayulcore)과 스텁(:hayulstub)
```

## 면책 조항

이 플러그인, 그리고 함께 제공되는 도우미와 설정 도구(이하 "이 프로그램")를 사용하면서 발생하는 모든 문제와 불이익에 대해 개발자는 책임지지 않습니다. 여기에는 계정 이용 제한, 데이터 손실, 기기 이상 등이 포함되며 이에 한정되지 않습니다.
이 프로그램과 그 코드의 사용은 전적으로 사용자 본인의 책임입니다.
