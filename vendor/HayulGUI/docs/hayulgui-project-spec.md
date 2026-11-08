# HayulGUI — 신규 프로젝트 생성 Spec

> 작성일: 2026-09-25 / 소스 검증: Hayul(https://github.com/ye-seola/hayul), HayulBasicStub,
> apksig(aosp), SigKill. 본 문서는 **새 별도 앱**으로 HayulGUI 를 만드는데 필요한 모든 정보를 담는다.
> 부모 저장소 IrisGUI(`party.qwer.irisgui`) 와의 관계는 §10 참조.

---

## 1. 한 줄 정의

**HayulGUI = 루트/adb/DeviceOwner 없이 동작하는 "APK shared-uid 패치 워크스테이션"** —
서명키 관리 + (Downloads 에 있는 / 설치된 앱의) APK 입력 + 패치 산출물 생성 + 설치 유도.

## 2. 배경 — 왜 이 모양인가 (검증된 사실 요약)

- IrisGUI 와 KakaoTalk(`com.kakao.talk`) 을 **같은 sharedUserId 그룹**으로 만들면, 이후 runtime 은
  root 없이도 KakaoTalk 데이터(`crypto_database`, keystore alias) 에 DAC+MLS 접근이 가능해진다
  → IrisGUI 의 "non-root DB 모드"가 완성된다.
- Android 는 설치된 앱의 uid 를 사후 변경할 수 없다 (`pm install -r` → `INSTALL_FAILED_UID_CHANGED`).
  따라서 **두 앱 모두 uninstall → patched APK 재설치**가 필요하다. Hayul 원본이 하는일과 동일.
- patched 재설치 시 서명이 KEY.jks 로 바뀌므로 KakaoTalk 의 자체 서명검증에 걸린다 →Hayul stub 처럼
  in-process 서명위조(hook) 가 필요. (HayulBasicStub = SigKill 패턴)
- 앱은 **남의 설치된 APK 를 읽을 수 없다** (`/data/app/.../base.apk` = SELinux `apk_data_file`,
  untrusted_app 차단). `sourceDir` 은 경로만 제공할 뿐 open() EACCES. 이건 root/adbd/shizuku/DO 가
  없으면 기술적으로 불가능 → 본 프로젝트의 입력 소스는 원칙적으로 "Downloads 에 있는 APK".
  설치된 앱 선택은 **획득 어시스턴스**(명령/경로 표시)로만 제공한다 (§5.2).
- 설치 실행은 사용자의 몫으로 위임한다: uninstall confirm dialog + installer 탭, 또는
  PC adb(`adb install-multiple`). HayulGUI 는 설치 채널을 스스로 가지지 않는다 → 이게 design 의 핵심 단순화.
  - (검증 결과: redroid 는 `pm create-user` 불가 = clone/프로필 방식 폐기. root 채널 의존도 폐기.
    DeviceOwner 방식은 최초 `dpm set-device-owner` adb 필요로 인해 이 설계보다 복잡/불요.)

### 목표 동작 (종단 시나리오)

1. HayulGUI 설치 (평범한 Play/file 설치. 이후 uninstall 하지 않는것이 권장됨 — 키가 이 앱에 영속)
2. HayulGUI 가 KEY 생성/보관 (§5.1)
3. 입력 확보:
   - 카톡: 실기기 user build → HayulGUI 가 `adb pull <sourceDir>` 명령을 클립보드 표시(§5.2 어시스트),
     또는 사용자가 PC/다운로드 앱으로 확보한 `base.apk`(+splits) 를 Downloads 에 놓음
   - irisgui: 현재 설치된 own APK 는 self-copy 가능 (`ApplicationInfo.sourceDir` 은 **자기자신**은 읽기 가능)
4. HayulGUI 로 patch → `patched-<pkg>-<ts>/` 산출물 (§5.3)
5. 사용자 설치: uninstall confirm → 설치 (설치 성공)
6. 검증: patched IrisGUI 의 IrisGUI 상태 탭이 "shared uid = KakaoTalk = 10xxx" 표시 (§5.5)
7. IrisGUI 의 DB 접근이 root 없이 동작 (KeystoreRawKey/root 경로 폐기)

---

## 3. 프로젝트 기본

| 항목 | 값 |
|---|---|
| 패키지명 | `party.qwer.hayulgui` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 (IrisGUI 동일) |
| 언어/도구 | Kotlin, AGP(현행과 동일 계열), `./gradlew`, FAIL_ON_PROJECT_REPOS, google()+mavenCentral() |
| UI | Compose + Material3 (IrisGUI 코드 재사용: 상태 화면 패턴 참고) |
| 서드파티 | `com.android.tools.build:apksig:8.x` (Apache-2.0) 만 — zipalign 은 apksig 내장 pure-Java `ZipAligner`, BC 불요 (self-signed 는 §5.1-B 직접 작성) |
| 저장소 레이아웃 | 신규 git repo 별도. 또는 IrisGUI repo 내 최상위 `hayulgui/` module (settings 추가) — 산출물이 IrisGUI assets 와 무관하므로 repo 공동관리 가능 |

## 4. 비목표 (명시적 제외)

- 설치 자동화 없음 (root/adbd/DO/shizuku 채널 사용 안함)
- 멀티유저/클론 프로파일 (redroid `max user limit` 확인됨 — 폐기)
- Play 스토어 배포용 (서명키 영속 정책상 부적합 — 사이드로딩용)
- Hayul 원본 코드/`patcher.dex` 무단 재사용 금지 (Hayul 에 LICENSE 없음; 원작자와의 관계와 무관하게
  기본은 **SigKill 패턴의 clean-room 재구현**. 재사용허락 을 문서화한다면 stub 만 교체 가능)

## 5. 컴포넌트 명세

### 5.1 키 관리 — `core/SigningKey.kt`

- 최초 실행 시 없으면 생성, filesDir 에 영속. 재패치/재설치·업데이트 전부 **동일 키** 재사용.
- 내용: RSA-2048 keypair (KeyPairGenerator) + self-signed X.509 (SHA256withRSA).
  - **A 경로 (기본)**: `PrivateKey`/`X509Certificate` 직렬화 — PKCS#8 DER (KeyFactory 가 이미 인코딩 제공)
    와 cert DER 를 filesDir 에 파일로. 주의: `PKCS8EncodedKeySpec` 로 private 재로드는 가능하나 cert DER 로
    `CertificateFactory X.509` 로드 가능. 즉 저장/복원 둘다 SDK만으로 완결.
  - **B 경로 (신규 생성)**: Java 표준 API 로는 self-signed cert 생성 불가 → minimal ASN.1/DER 인코더 (~150줄)
    직접: TBSCertificate(serial=now ms, sigAlg SHA256-RSA, issuer=subject=CN=HayulGUI, validity 10000d,
    spki from `getEncoded("X.509")` pubkey) + `Signature("SHA256withRSA")` 후 CERTIFICATE 로 서명.
    또는 `openssl req -x509 -newkey rsa:2048 -keyout k.pem -out c.pem -nodes -days 10000` 사용자 보조형 옵션.
- 포맷 변환용 어댑터 `SigningKey.toApksignKeyPair(): KeyPair` (apksig 는 raw KeyPair 허용 — §5.4).
- UI: "키 없음/존재(지문 표시)" 상태, 지문 = cert SHA-256.

### 5.2 입력 — `core/ApkSource.kt`

- `InstalledSource` (어시스트 전용): `pm` 패키지 목록 (`getInstalledPackages`) 에서 선택 받음.
  선택 시 표시만 하는 정보 생성:
  - 경로: `ApplicationInfo.sourceDir` + `splitSourceDirs`
  - 안내: `adb pull <base.apk> && adb pull <split_i.apk>` (복사용지 버튼) — "다운로드 폴더에 놓아주세요" 문구.
  - 자체 패키지만 실제 읽기 허용 (sourceDir 자기자신은 untrusted_app 도 읽을수 있음: own apk = apk_data_file... own 은 readable 검증 필요. 실패하면 same 어시스트.)
- `DownloadsSource`: MediaStore `EXTERNAL_CONTENT_TYPE`/`Downloads` 쿼리 + SAF `ACTION_OPEN_DOCUMENT`
  (항상 동작하는 유니버설 입력). `*.apk` 필터, split set 감지 (`base.apk`+`config-*.apk` 패턴,
  `ApplicationInfo.splitSourceDirs` 는 대상없으므로 파일명 매칭).
- 입력 체크: valid zip + `AndroidManifest.xml` entry 존재.

### 5.3 패치 엔진 — `core/Axml.kt`, `core/Patch.kt`

패치 내용 (원본 유지, 변경은 대상 한정):

1. `AndroidManifest.xml` (AXML binary) — attribute 변경:
   - `manifest` element 에 `android:sharedUserId = "<value>"` 추가/교체
   - `application` element 에 `android:appComponentFactory = "<pkg>.PatcherAppComponentFactory"` 추가/교체
   - AXML 구조: RES_XML_* chunk, string pool (UTF-16 기본), attr 단위는 (nameRef, rawValue, typedValue)
   - ⚠ 실측함매 두 가지 (Android 12, libandroidfw/XmlBlock 해석):
     1. **name 필드**: resmap(0x0180 chunk)이 있는 파일에서 android-ns 속성의
        name 필드는 **resmap index** 여야 한다. libandroidfw 는
        `getAttributeNameResID(): name < mResIds.size ? mResIds[name] : 0` 으로
        id 를 조달하고 PMS 는 그 id 로 sharedUserId 등을 lookup 한다. pool ref 를
        그냥 넣으면 id 조달 실패 -> PMS 가 "속성 없음" 취급 -> uid 공유가 조용히
        무시된다. (우린 이 때문에 1차 패치가 전부 실패했었다.)
     2. **rawValue**: 문자열 속성의 값은 typedValue.data 가 아니라 rawValue 의
        pool reference 로 해석된다. raw=-1 로 두고 typed 만 채우면
        getAttributeValue() 는 null 을 반환한다.
     그래서 setStringAttribute 는 (a) attr id 를 resmap 에 append(재사용)하고
     name=map index, (b) raw=typed.data=같은 pool ref 로 쓴다. 검증은
     PatchEngineTest/RealApkTest 의 resourceMapIds() assert 로커버.
     — attribute style value `TYPE_STRING` valueRef 로 pool 에 string 추가, `RES_XML_ATTRIBUTE_CHUNK`
     size/upCount 필드 updat, element chunk size 도 체인 반영. **res-auto attr 이므로 hardcoded
     attr-id 불요** (nameRef 는 그냥 문자열). Hayul `attrib.json` 의 attribute sort 순서 유지 문제는:
     attribute 추가 위치는末尾로 충분 — PMS파서는 순서비순종. (실기기 검증 체크리스트 포함)
2. `stub.dex` append: `classes<N+1>.dex` (원본 max N+1). 멩등성/교체를 위해 `party.qwer.hayulgui.orig`
   에 **원본 서명 DER hex** 저장 (Hayul 의 `dev.seola.apppatcher.sig.orig` 동일 역할, 이름만 우리것).
   재패치 감지: marker 파일 존재하면 old `classes*+.dex` 를 교체(추가 제거)+서명 되찾기.
3. zipalign (apksig `ZipAligner`, page-align=4, uncompressed `.so` 등 유지)
4. apksig 서명 (§5.4) — **동일 set 은 전부 같은 키로** (split 일 경우 base+split 전부, 서명 일관성 필수)

Split 전략 (미판결 — 실측 전까지):
- KakaoTalk base만 있는지 split 인지 `pm path` 결과(또는 pull 된 파일 set) 로 판정.
- split: base 만 AXML patch, split 은 manifest 무변경+재서명. **판정 필요**: split manifest 가
  `sharedUserId` 속성을 반복 선언하면 base 와 불일치 → split manifest 도 동일 속성으로 patch 할것.
  (Hayul 은 split manifest 미대응. 첫 개발 시 반드시 실측체크: split의 `AndroidManifest.xml` attribute 조회)
- 산출물 형식: `<outdir>/` = patched base.apks+patched split*.apk (split set) 또는 `base.apk` (single)
  + `install.txt` (명령·手順. `adb install-multiple -r` / file manager 탭)

### 5.4 서명 — `core/ApkSigner.kt` (apksig 래퍼)

```kotlin
val signer = ObjectSigningConfig.Builder()
    .setSignerConfig(SigningConfigUtils... ) // raw PrivateKey + Certificate chain 사용
val cfg = ApkSignerConfig.Builder()...  // apksig 버전: ApkSigner(input, output, SignerParams) 패턴은 API21+? 실버전 확인
```

- API 주의: 최신 apksig 는 `SignConfig` + `ObjectSigningConfig.Builder()...` + `ApkSigner` 조합 또는
  `SigningConfig` (version-dependent) 실측 필요. v2+v3 는 enabled, v1 은 minSdk<24 일경우만.
- 서명 후 `ApkVerifier.verify()` 붙여넣기 (산출물 품질 체크).

### 5.5 검증/상태 — `core/Verify.kt` + 상태 탭

HayulGUI 는 남의 uid 를 읽지 못하므로 검증은 두 갈래:
1. 산출물 검증 (patch 직후): `ApkVerifier` + `AndroidManifest` decompile (apktool 없이: 우리 AXML reader로
   되읽어 sharedUserId 확인) + `classesN+1.dex` 포함 확인.
2. 설치 후: **IrisGUI 쪽** 확인. patched IrisGUI 는 카톡과 uid 공유가성립하면
   `packageManager.getPackageInfo("com.kakao.talk",0).applicationInfo.uid == (own uid)` 비교 가능.
   → IrisGUI 상태 탭에 `shared uid: <uid> == <uid> OK/FAIL` 렌더.
   HayulGUI 는 그 결과를 수동 확인하는 UI (text 입력/스캔불요 — "동작 확인되면 OK" 체크박스 기록).

### 5.6 Stub (패치 산출물에 포함되는 dex) — `stub/` module

clean-room SigKill 패턴 (Apache-2 참고 재구현, Hayul 코드 미포함):
- `PatcherAppComponentFactory : AppComponentFactory` (manifest 가 지정) — static 초기화: `ServiceManager.getService("package")` →
  `IPackageManager$Stub.asInterface` → dynamic proxy 교체 + `ActivityThread.sPackageManager` 교체.
  `getPackageInfo*` 응답의 `PackageInfo.signatures[0]` ← 저장된 `sig.orig` DER hex 위조.
- **확정 필요**: KakaoTalk 가 `PackageInfo.signatures` 레거시필드만 검증? `signingInfo` (API28+) 도 보면
  SigningInfo 위조 추가(realDevice 테스트 필요). stub 에 옵션으로 구현여지 남기기.
- stub 에 `Build.*` 모델위조(SM-X906N) 는 **포함하지 않음** (필요 판명 시에만 추가 결정).

## 6. UI 화면

1. **Home** — 키 상태(지문)/`키 생성`/`임포트`/`백업`, sharedUserId 설정(기본
   `party.qwer.shared`, prefs 저장 — 이후 모든 패치에 적용), How-to 요약
2. **앱목록** — 세그먼트 둘만 (선택 버튼/설명문 없음):
   - `앱 추출 (ADB)`: 설치앱 flat 목록(앱명+패키지명) + `복사` → `adb pull` 명령 클립보드
   - `Downloads`: apk/xapk/apkm 목록 + 파일별 `패치` 버튼 = staging(xapk/apkm 은 unzip 하여
     base+split 복원)→ 즉시 패치 실행. MediaStore 비활경로 대비 `파일 선택`(OpenDocument,
     복수선택=split 세트) 폴백. 전체파일접근 필요시 설정화면 링크 1줄
3. **설치** — 산출물 목록 + 우측 `설치` 버튼(`PackageInstaller` 세션, base+split 단일 commit)
   + `삭제` 버튼(디렉터리 제거). 상태 규칙: 미설치→설치 / 서명일치→재설치 /
   설치됨+서명불일치→설치 버튼 비활성("서명불일치") — 제거 버튼은 없음(오탈 사고 방지,
   uninstall 은 사용자가 직접).
   ※세션 API 때문에 split 이라도 adb 불요 — SessionInfo/getInstallError는 @hide라
   EXTRA_STATUS public extras 로 성공/실패 판정.
※ 3-탭으로 축소(패치 탭/상태/로그 탭 폐기). 패치 실행은 Downloads `패치` 버튼에서 즉시.
   로그는 filesDir/logs 에만 남음.

권한/manifest (HayulGUI 자신퍼): READ_MEDIA_*/READ_EXTERNAL_STORAGE(maxSdk=32), 파일은 MediaStore+SAF 로 대응.
IrisGUI 에 ACTION_* 통지는 없음 (분리 유지, 양방향 intent 도 optional — §10).

## 7. 저장소 구조 (신규앱)

```
hayulgui/
  app/                 (Compose UI: ui/*.kt, MainActivity)
  core/                (library module: SigningKey, ApkSource, Axml, Patch, ApkSigner, Verify, ArtifactStore)
  stub/                (library module → compileJava + d8 → dist stub.dex. Gradle task `stubDex`
                        산출물을 core 에 copy; Java 만 허용, desugaring 없음 (API26 base))
  tools/axmldump/     (optional JVM CLI: AXML 리팩 확인용. dev only)
```

Gradle deps (app/core 만): `apksig`, androidx compose bom, kotlin(x) — 전부 mavenCentral/google.
`stub/` 은 dependency 없이 `android.jar` compileOnly + javac → `d8 --min-api 26 --release`.

## 8. 테스트 계획

개발 초기에 코딩보다 먼저 실측 (IrisGUI 가 이미 가진 실기기/redroid 어드밴티지):
- [ ] 실기기+redroid 의 KakaoTalk `pm path` → split set 확인; split manifest 에 sharedUserId 속성 유무
- [ ] `ApplicationInfo.sourceDir` 자기 APK 를 untrusted app 읽을수 있는지 (self-patch UX 결정)
- [ ] Kakao 검증 API: logcat/hook 실험 — `signatures` vs `signingInfo` (stub 범위 결정)
- [ ] patched 카톡 설치 후 정상 로그인/동작 (stub 유효성)
- [ ] AXML 추가 attribute 의 PMS 수용 (순서/pool rewrite) — 가장 낮은 리스크이나 기본체크 필수

단위 테스트 (JVM): `AxmlTest` round-trip (샘플 manifest: patch→re-read assert),
`SigningKeyTest` (DER 재로드 + self-signed 검증), `ApkSignerTest` (test keystore 로 sign+verify).
instrumented: `PatchInstrumentedTest` — min APK(공부용 dummy) 로 patch+`pm install -r`+getPackageInfo.

## 9. 리스크/주의

| 리스크 | 대응 |
|---|---|
| 카톡이 서버측/Play Integrity 서명검증 사용 → hook 실패 가능 | stub signingInfo 확장, 실측으로만 판단. 이 프로젝트 범위 밖은 아님(동작확인 게이트) |
| 키 유실(HayulGUI uninstall) = 이전 산출물 전부 재패치불가 | Home 화면 경고/백업(Export KEY) 기능 |
| 카톡 업데이트 = 산출물 stale | Status 의 versionCode 메모 + `재패치` 유도. (Hayul 의 already-patched marker 로 원서명 보존되어있음) |
| split install 를 사용자가 installer 탭만으로 못함 | 산출물 형식을 `base.apk+config-*.apk` zip 과 `install.txt` 로 명시. (Android installer 는 multi-APK 직접 탭불가 → split 은 adb 가 확실히 shortest) |
| uninstall 순서 착각 시 INSTALL_FAILED_UID_CHANGED | Install Guide 단계 체크: `uninstall 선행` 표시 (현재 설치 version 의 signature/uid 로 상태 분기) |

## 10. IrisGUI와의 관계 (연계)

- HayulGUI 는 독립 앱 (shared uid 그룹 미가입, 서명 불일치 OK). 두 앱 간 통신문 없음.
- 그룹 가입자 = patched `party.qwer.irisgui` + patched `com.kakao.talk`. 두 앱 모두 **같은 임의의** sharedUserId 문자열로 패치하면 그룹 성립. 기본값 `party.qwer.shared` (양쪽 모두에 동일한 값 입력 필요).
- 이후 IrisGUI 측 변화(별도 작업, 이 문서 범위 외): `AppMode` 에 shared-uid 직독 모드, `KeystoreRawKey` 생략,
  `PathUtils` 단순화, Status 에 HayulGUI 연동배지.

## 11. Milestone

- **M0 (실측)**: §8 체크리스트 전부, 실기기+redroid 에서. 이 결과로 patch 상세(split manifest, signingInfo, self-read) 확정.
- **M1**: core 모듈 + stub — unit/instrumented 통과 (dummy APK 전체 루프).
- **M2**: HayulGUI UI 전부 + 산출물 생성/설치가이드 완결.
- **M3**: 실기기 end-to-end (카톡→group 성립), IrisGUI status 연동.

## 12. 진행 상태 (2026-09-26)

- **M1 완료**: `core` (Axml/Zip/SigningKey/PatchEngine/ApkSigning/ApkSources/Artifacts/Status/Logx) + `stub`
  (clean-room SigKill: PatcherAppComponentFactory / SigHook / StubConfig → d8 `stub.dex`).
  JVM 테스트 전부 통과. 실제 카톡 base.apk(240MB) 패치→서명→`apksigner verify`까지 JVM 검증.
- **M2 완료**: `app` Compose UI (IrisGUI 디자인 그대로: GlassBackground/CardComponents/AppColors/AppTypography).
  탭 = 홈/입력/패치/설치/상태/로그. `:app` debug APK 빌드·설치·기기 실행 정상.
  `stub.dex` is `core` assets 에 번들 (`:stub:stubDex` → `mergeAssets` 의존).
  self-patch 실측: 우리 base.apk → `sharedUserId=party.qwer.irisgui`, `appComponentFactory`=stub,
  `classes8.dex` append, HayulGUI 키(v2+v3 `Verifies`, cert `6450c07e…`) 서명, `hayulgui.cfg` marker. 검증 완료.
  ※설치는 사용자 위임(안드로이드 정책상 self-install 불가/불요). 산출물만 생성.
- **M3 대기**: 카톡·IrisGUI 를 그룹으로 묶는 실기기 end-to-end + IrisGUI 상태 연동. (설치는 사용자/`adb install-multiple`)
- 기기: redroid `172.30.10.100:5555` 만 사용 (172.30.10.66 금지). HayulGUI 는 self-patch 로만 테스트, 카톡/IrisGUI 설치에 손대지 않음.
