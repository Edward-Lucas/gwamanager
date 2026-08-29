# Gecko Web App Manager 구현 계획

## 1. 프로젝트 개요

Android 태블릿에서 여러 WebApp을 관리하고 실행할 수 있는 로컬 기반 애플리케이션을 구현한다.

사용자는 Manager App에서 WebApp 정보를 등록한다.

- WebApp 이름 입력
- WebApp URL 입력
- User-Agent 유형 선택
    - 기본값: 모바일 User-Agent
    - 태블릿/PC 옵션 선택 시: PC용 User-Agent
- 저장된 WebApp을 목록에서 선택하여 실행

각 WebApp은 GeckoView를 통해 표시한다.

Manager App은 서버를 사용하지 않으며, WebApp 설정과 데이터는 모두 태블릿 내부에 저장한다.

---

## 2. 핵심 요구사항

### 2.1 브라우저 엔진

- Mozilla GeckoView를 사용한다.
- Android WebView는 사용하지 않는다.
- Firefox Android 전체 애플리케이션 구조를 사용하지 않는다.
- GeckoRuntime, GeckoSession, GeckoView를 직접 연결한다.
- WebApp 화면에는 웹 콘텐츠를 표시하는 GeckoView를 사용한다.

### 2.2 Android 상태 표시줄

일반 WebApp 화면에서는 Android 시스템 상태 표시줄을 유지한다.

웹 콘텐츠가 표준 Fullscreen API를 요청한 경우에는 전체화면 표시를 위해
`GeckoSession.ContentDelegate.onFullScreen()`을 통해 상태 표시줄만 일시적으로 숨긴다.
전체화면이 종료되면 상태 표시줄과 WebApp 화면의 시스템 인셋을 복원한다.

유지해야 하는 요소:

- 시간
- 배터리
- Wi-Fi 및 통신 상태
- 알림 아이콘
- Android 시스템 상태 표시줄

일반 WebApp 화면에서는 다음을 사용하지 않는다.

- Immersive Fullscreen
- 상태 표시줄 숨김
- `WindowInsetsCompat.Type.statusBars()` 숨김
- 전체 시스템 바 숨김

일반 WebApp 화면은 Android 상태 표시줄 아래에 표시한다.
웹 콘텐츠 전체화면 중에는 상태 표시줄 영역까지 GeckoView 콘텐츠를 확장한다.

### 2.3 User-Agent

Manager App에서 WebApp을 등록하거나 수정할 때 User-Agent 유형을 선택할 수 있어야 한다.

#### 기본 동작

사용자가 태블릿/PC 옵션을 선택하지 않은 경우:

```text
모바일 User-Agent 사용
```

사용자가 태블릿/PC 옵션을 선택한 경우:

```text
PC용 User-Agent 사용
```

#### User-Agent 정책

- 기본값은 모바일 User-Agent이다.
- 선택 상태는 WebApp별로 저장한다.
- WebApp 실행 시 해당 WebApp의 User-Agent 설정을 적용한다.
- WebApp 실행 중 User-Agent 설정이 변경되면 다음 실행부터 적용해도 된다.
- 초기 구현에서는 사용자가 직접 User-Agent 문자열을 입력하는 기능을 만들지 않는다.
- 초기 구현에서는 다음 두 가지 모드만 지원한다.
    - Mobile
    - Desktop/PC

#### 권장 모델

```kotlin
enum class UserAgentMode {
    MOBILE,
    DESKTOP
}
```

WebApp 설정에는 다음 필드를 포함한다.

```kotlin
data class WebAppConfig(
    val id: String,
    val name: String,
    val url: String,
    val userAgentMode: UserAgentMode
)
```

실제 User-Agent 문자열은 별도의 상수 또는 설정 객체에서 관리한다.

---

## 3. 통합 세션 정책

모든 WebApp은 하나의 통합 브라우저 세션과 저장소를 공유한다.

### 3.1 세션 공유

- 하나의 공용 `GeckoRuntime`을 사용한다.
- 모든 WebApp은 동일한 GeckoRuntime을 사용한다.
- WebApp 간 쿠키를 공유한다.
- WebApp 간 로그인 상태를 공유한다.
- WebApp 간 localStorage, IndexedDB 등의 브라우저 저장소를 공유한다.
- WebApp 간 브라우저 데이터가 서로 영향을 주고받아도 괜찮다.
- 초기 구현에서는 WebApp별 독립 프로필을 만들지 않는다.

### 3.2 세션 생명주기

- `GeckoRuntime`은 애플리케이션 전체에서 하나만 생성한다.
- Activity가 재생성되어도 GeckoRuntime을 불필요하게 다시 생성하지 않는다.
- WebApp Activity가 종료되거나 백그라운드로 이동할 때 GeckoSession을 무조건 닫지 않는다.
- 사용자가 명시적으로 WebApp 데이터를 삭제하는 경우에만 관련 데이터 삭제를 검토한다.
- 앱 프로세스가 종료될 가능성은 고려하되, 초기 구현에서는 세션 상태 복원의 최소 구조만 준비한다.

### 3.3 권장 구조

```text
Application
├── GeckoRuntimeProvider
├── WebAppRepository
├── SessionManager
└── LocalStorage

ManagerActivity
└── WebApp 목록 및 관리

WebAppActivity
└── GeckoView + GeckoSession
```

### 3.4 WebApp 실행 단위

초기 버전은 하나의 APK와 Android 패키지를 유지한다. Android에서 런처에 동적으로
등록할 수 있는 진입점은 `ShortcutManager`이므로, 각 WebApp은 고유한 Intent URI와
문서 Task로 실행한다.

- WebApp마다 고유한 문서 Task와 최근 앱 항목을 사용한다.
- 같은 WebApp을 다시 열면 해당 WebApp Task를 재사용한다.
- WebApp 간 GeckoRuntime, 쿠키, 로그인 상태 공유 정책은 유지한다.
- 런처 아이콘은 WebApp Task를 여는 동적/고정 런처 진입점으로 동작한다.
- PWA `manifest.json`의 `icons`에서 Mobile은 192x192, Desktop/PC는 512x512 아이콘을 우선 선택한다.
- 선택한 아이콘은 런처 shortcut과 최근 앱의 WebApp Task 아이콘에 동일하게 적용한다.
- 런처에서 shortcut을 삭제한 경우 Manager의 WebApp 항목에서 다시 추가할 수 있어야 한다.

---

## 4. Manager App의 로컬 기반 정책

Manager App은 철저하게 로컬 기반으로 동작한다.

### 4.1 허용되는 통신

WebApp 자체가 필요로 하는 네트워크 통신은 허용한다.

예시:

```text
WebApp 페이지 로드
WebApp의 API 호출
WebApp의 로그인
WebApp의 이미지·동영상·스크립트 로드
```

이 통신은 Manager App이 서버와 통신하는 것이 아니라 GeckoView 안에서 실행되는 WebApp의 통신이다.

### 4.2 금지되는 Manager App 통신

Manager App 자체는 다음 서버와 통신하지 않는다.

- 자체 백엔드 서버
- 사용자 계정 서버
- 설정 동기화 서버
- 원격 분석 서버
- 사용 통계 서버
- 광고 서버
- 자체 업데이트 서버
- 원격 WebApp 목록 서버

### 4.3 데이터 저장

다음 데이터는 모두 로컬에 저장한다.

- WebApp 이름
- WebApp URL
- User-Agent 모드
- WebApp 정렬 순서
- WebApp 활성화 상태
- 마지막 실행 정보
- PWA manifest 기반 WebApp 아이콘 정보
- Manager App 설정

초기 구현에서는 다음 중 하나를 사용한다.

- Room
- DataStore
- JSON 파일

권장 방식:

- WebApp 목록과 구조화된 데이터: Room
- 단순 앱 환경설정: DataStore

초기 버전에서는 구현 복잡도를 줄이기 위해 DataStore 또는 로컬 JSON 저장을 먼저 사용할 수 있다.

---

## 5. 구현 순서

## Phase 0. 개발 환경 및 프로젝트 생성

### 목표

기본 Android 프로젝트를 생성하고 실제 테스트 태블릿에 APK를 설치한다.

### 작업

- Kotlin 기반 Android 프로젝트 생성
- Gradle 프로젝트 구성
- 최소 지원 Android 버전 결정
- target SDK 설정
- 실제 태블릿 USB 디버깅 설정
- Debug APK 빌드
- 태블릿에 APK 설치
- 기본 Activity 실행 확인

### 완료 조건

- 앱이 태블릿에서 실행된다.
- 앱이 정상적으로 설치 및 제거된다.
- `adb logcat`으로 앱 로그를 확인할 수 있다.
- ManagerActivity가 표시된다.

---

## Phase 1. GeckoView 최소 구현

### 목표

Manager App에서 WebApp을 실행하고 GeckoView로 웹 페이지를 표시한다.

### 작업

- GeckoView 의존성 추가
- `GeckoRuntime` 초기화
- `GeckoSession` 생성
- `GeckoSession`을 GeckoRuntime에 연결
- `GeckoView`에 GeckoSession 연결
- 테스트 URL 로드
- 페이지 이동 확인
- 뒤로가기 및 Activity 생명주기 확인

### 권장 구성

```text
WebAppActivity
└── GeckoView
```

### 완료 조건

- 지정한 URL이 GeckoView에 표시된다.
- 링크를 클릭하면 새로운 URL로 이동한다.
- 페이지 이동이 정상적으로 작동한다.
- 일반 화면에서 Android 상태 표시줄이 표시된다.
- 앱이 불필요하게 전체 화면으로 전환되지 않는다.

---

## Phase 2. 로컬 WebApp 데이터 모델 구현

### 목표

WebApp 설정을 태블릿 내부에 저장한다.

### 작업

- `WebAppConfig` 데이터 모델 생성
- WebApp ID 생성
- 이름 저장
- URL 저장
- User-Agent 모드 저장
- WebApp 목록 조회
- WebApp 수정
- WebApp 삭제
- 데이터 영속성 확인

### 기본 데이터 모델

```kotlin
data class WebAppConfig(
    val id: String,
    val name: String,
    val url: String,
    val userAgentMode: UserAgentMode
)
```

### 완료 조건

- 앱을 종료해도 WebApp 목록이 유지된다.
- WebApp을 추가할 수 있다.
- WebApp을 수정할 수 있다.
- WebApp을 삭제할 수 있다.
- 저장된 URL을 다시 실행할 수 있다.

---

## Phase 3. Manager App 화면 구현

### 목표

사용자가 최소한의 입력만으로 WebApp을 등록하고 실행할 수 있도록 한다.

### 화면

```text
ManagerActivity
├── WebApp 목록
├── WebApp 추가 버튼
└── WebApp 설정 화면
```

### WebApp 추가/수정 입력 항목

- WebApp 이름
- URL
- 태블릿/PC 옵션
- 저장 버튼
- 취소 버튼

### 기본 UI 동작

```text
Manager App 실행
→ WebApp 목록 표시
→ WebApp 추가 선택
→ 이름과 URL 입력
→ 필요하면 태블릿/PC 옵션 선택
→ 저장
→ 목록에 WebApp 표시
→ WebApp 선택
→ WebAppActivity 실행
```

### 완료 조건

- 사용자가 WebApp 추가 흐름을 쉽게 이해할 수 있다.
- URL이 비어 있으면 저장할 수 없다.
- 잘못된 URL 형식은 사용자에게 안내한다.
- 저장 후 목록에 즉시 표시된다.
- 목록의 WebApp을 누르면 해당 URL이 실행된다.

---

## Phase 4. User-Agent 적용

### 목표

WebApp별 User-Agent 모드를 GeckoSession에 적용한다.

### User-Agent 모드

```text
Mobile
Desktop/PC
```

### 작업

- 모바일 User-Agent 정의
- PC User-Agent 정의
- WebApp별 User-Agent 모드 조회
- GeckoSession 생성 전에 User-Agent 적용
- WebApp별 동작 확인
- 테스트 페이지에서 실제 User-Agent 확인

### 적용 정책

- WebApp 실행 시 설정값을 읽는다.
- User-Agent 설정에 따라 GeckoSession을 구성한다.
- 설정값이 없으면 Mobile을 사용한다.
- 사용자가 Desktop/PC를 선택한 경우 PC User-Agent를 사용한다.

### 완료 조건

- 기본 설정에서 모바일 User-Agent가 전송된다.
- 태블릿/PC 옵션 선택 시 PC User-Agent가 전송된다.
- WebApp A와 WebApp B가 서로 다른 User-Agent 모드를 가질 수 있다.
- 앱 재실행 후에도 모드가 유지된다.

---

## Phase 5. 통합 GeckoRuntime 및 세션 관리

### 목표

모든 WebApp이 동일한 GeckoRuntime과 브라우저 저장소를 사용하도록 한다.

### 작업

- Application 수준의 `GeckoRuntimeProvider` 구현
- GeckoRuntime 중복 생성 방지
- SessionManager 구현
- WebApp ID와 GeckoSession 연결
- Activity와 GeckoSession 생명주기 분리
- WebApp 전환 시 기존 세션 유지
- 쿠키와 로그인 상태 공유 확인

### 권장 구조

```kotlin
class App : Application() {
    lateinit var geckoRuntime: GeckoRuntime
}
```

실제 구현에서는 초기화 시점과 스레드 안정성을 고려한다.

### 완료 조건

- 앱 내부에서 GeckoRuntime이 하나만 사용된다.
- WebApp 간 쿠키가 공유된다.
- 한 WebApp에서 로그인한 상태가 다른 화면 전환 후 유지된다.
- Activity가 재생성되어도 세션 연결이 안정적으로 작동한다.
- WebApp Activity 종료 시 세션이 무조건 닫히지 않는다.

---

## Phase 6. 전역 WebExtension 구조

### 목표

Manager App이 설치한 하나의 내장 WebExtension을 모든 WebApp에 적용한다.

### 정책

- 확장 기능은 APK 내부에 포함한다.
- 앱 최초 실행 또는 GeckoRuntime 초기화 시 한 번 설치한다.
- 모든 WebApp의 GeckoSession에서 사용할 수 있도록 구성한다.
- Manager에서 로컬 `.xpi` 파일을 선택해 사용자 WebExtension을 설치할 수 있다.
- Manager의 AMO 화면에서 Mozilla 서명 원격 `.xpi`를 설치할 수 있다.
- 임의의 외부 URL에서 WebExtension을 설치하지 않는다.
- 확장 기능은 공통 경험 제공을 목적으로 한다.

### 가능한 공통 기능

- 공통 CSS 적용
- 특정 웹 요소 숨김
- 공통 JavaScript 실행
- WebApp 화면 보정
- 사이트별 호환성 처리
- 웹 페이지와 Android 코드 간 메시지 통신

### 권장 확장 기능 구조

```text
app/src/main/assets/global-extension/
├── manifest.json
├── content.js
├── background.js
└── styles.css
```

### 초기 권한 원칙

- 필요한 권한만 선언한다.
- 처음부터 `<all_urls>` 권한을 무조건 사용하지 않는다.
- 실제 대상 도메인 목록을 기준으로 권한을 제한한다.
- WebApp별 적용 여부를 나중에 확장할 수 있도록 구조를 만든다.

### 완료 조건

- 내장 WebExtension이 정상적으로 설치된다.
- 앱 재실행 후 확장 기능이 중복 설치되지 않는다.
- 모든 WebApp에서 확장 기능이 적용된다.
- WebApp 이동 후에도 확장 기능이 동작한다.
- 확장 기능 오류가 WebApp 전체를 중단시키지 않는다.
- AMO의 설치 버튼으로 서명된 WebExtension을 설치할 수 있다.
- AMO에서 설치한 WebExtension이 앱 재실행 후 복원된다.

---

## Phase 7. 웹 알림 기본 지원

### 목표

WebApp의 일반 Notification API 요청을 Android 알림으로 연결한다.

### 범위

초기 단계에서는 열린 WebApp에서 발생하는 일반 웹 알림을 우선 지원한다.

### 작업

- Android Notification Channel 생성
- Android 알림 권한 처리
- GeckoView 웹 알림 콜백 연결
- 웹 알림 제목·내용 추출
- Android 알림 생성
- 알림 클릭 시 해당 WebApp으로 복귀
- WebApp별 알림 식별자 관리

### 완료 조건

- WebApp이 알림 권한을 요청할 수 있다.
- Android 알림이 표시된다.
- 알림 제목과 내용이 표시된다.
- 알림을 누르면 관련 WebApp으로 이동한다.
- Android 알림 권한이 거부된 경우 앱이 비정상 종료되지 않는다.

---

## Phase 8. 백그라운드 미디어 재생 조사 및 구현

### 목표

WebApp에서 음악을 재생한 후 Manager App 또는 홈 화면으로 이동해도 음악이 계속 재생되도록 한다.

### 기본 요구사항

```text
WebApp에서 음악 재생
→ 홈 화면 이동
→ 음악 계속 재생
```

### 작업 순서

1. GeckoView 내 HTML audio/video 재생 확인
2. WebAppActivity가 백그라운드로 이동할 때 재생 상태 확인
3. GeckoSession을 닫지 않는 구조 확인
4. GeckoView MediaSession 이벤트 로그 출력
5. Android foreground service 설계
6. Android MediaSession 연결 검토
7. 미디어 알림 추가
8. 재생/일시정지 명령 연결
9. 화면 잠금 상태에서 테스트
10. 오디오 포커스 처리
11. 태블릿 제조사의 배터리 제한 확인

### 중요한 범위 구분

초기 구현의 목표:

- 홈 화면 이동 후 음악 재생 유지
- 기본 재생 상태 확인
- 최소한의 미디어 알림 표시

후속 구현:

- 알림에서 재생/일시정지
- 잠금 화면 제어
- Bluetooth 버튼 제어
- 다음 곡/이전 곡
- 제목·아티스트·앨범 이미지 표시
- 오디오 포커스 완전 처리
- 앱 재실행 후 미디어 상태 복원

### 완료 조건

- 특정 테스트 WebApp에서 음악이 재생된다.
- 홈 화면으로 이동해도 일정 조건에서 음악이 계속 재생된다.
- WebAppActivity가 사라져도 GeckoSession을 불필요하게 닫지 않는다.
- 재생 중 Android 시스템 알림이 표시된다.
- 알림에서 일시정지 동작을 수행할 수 있다.
- 태블릿 화면을 잠근 상태에서 동작을 확인한다.

---

## Phase 9. 안정성 및 오류 처리

### 목표

실제 태블릿에서 반복적으로 사용할 수 있는 수준으로 안정화한다.

### 확인 항목

- 잘못된 URL
- 네트워크 연결 끊김
- 페이지 로드 실패
- 인증서 오류
- 리디렉션
- 팝업
- 새 창
- 파일 업로드
- 파일 다운로드
- 권한 요청
- 알림 권한 거부
- 앱 백그라운드 전환
- 화면 회전
- Activity 재생성
- 메모리 부족
- 태블릿 절전
- 앱 재실행
- 앱 강제 종료
- WebApp 삭제
- 중복 WebApp
- 빈 이름
- 공백 URL
- 지원하지 않는 URL 스킴

### 오류 원칙

- 예외를 무시하지 않는다.
- 사용자에게 이해할 수 있는 오류 메시지를 제공한다.
- GeckoView 오류는 Logcat에 기록한다.
- Manager App의 로컬 데이터가 손상되어도 앱 전체가 즉시 종료되지 않도록 한다.
- 네트워크 오류와 Manager App 오류를 구분한다.

---

## 6. 반드시 구현할 것

### 필수 기능

- [ ] Kotlin 기반 Android 앱
- [ ] GeckoView 사용
- [ ] ManagerActivity
- [ ] WebAppActivity
- [ ] WebApp 추가
- [ ] WebApp 수정
- [ ] WebApp 삭제
- [ ] WebApp 목록 표시
- [ ] 로컬 데이터 저장
- [ ] WebApp 이름 저장
- [ ] WebApp URL 저장
- [ ] WebApp별 User-Agent 모드 저장
- [ ] 모바일 User-Agent 기본 적용
- [ ] 태블릿/PC 선택 시 PC User-Agent 적용
- [ ] 통합 GeckoRuntime
- [ ] 통합 브라우저 세션 및 저장소
- [ ] 쿠키 및 로그인 상태 유지
- [ ] 일반 화면에서 Android 시스템 상태 표시줄 유지
- [ ] 웹 콘텐츠 전체화면에서 상태 표시줄 숨김 및 종료 후 복원
- [ ] GeckoView 기반 웹 페이지 탐색
- [ ] 내장 전역 WebExtension 구조
- [ ] 기본 웹 알림 처리
- [ ] 백그라운드 미디어 재생 검증
- [ ] 실제 Android 태블릿 테스트
- [ ] 로컬 기반 동작
- [ ] 서버 없는 Manager App

---

## 7. 초기 버전에서 하지 말아야 할 것

다음 기능은 초기 버전에서 구현하지 않는다.

- [ ] Manager App용 백엔드 서버 구축
- [ ] 사용자 계정 시스템
- [ ] 클라우드 동기화
- [ ] WebApp 목록 원격 동기화
- [ ] 원격 분석
- [ ] 광고 시스템
- [ ] 사용자별 서버 데이터 저장
- [ ] WebApp마다 별도 APK 생성
- [ ] WebApp마다 별도 Android 패키지 생성
- [ ] WebApp마다 독립 브라우저 프로필 생성
- [ ] WebApp마다 쿠키 저장소 분리
- [ ] 임의의 외부 URL에서 `.xpi` 확장 기능 자유 설치
- [ ] 서명되지 않은 확장 기능을 제한 없이 설치
- [ ] 직접 입력하는 복잡한 User-Agent 편집기
- [ ] 모든 웹사이트의 Web Push 완전 지원
- [ ] 프로세스 강제 종료 후 백그라운드 실행 보장
- [ ] 모든 사이트의 미디어 기능 완전 호환 보장
- [ ] 일반 화면에서 Android 시스템 상태 표시줄 숨김
- [ ] Immersive Fullscreen
- [ ] Manager App과 WebApp의 데이터 완전 분리
- [ ] 초기부터 복잡한 다중 프로필 시스템
- [ ] 필요 이상의 서버 통신
- [ ] 한 번에 모든 기능을 구현하는 대규모 리팩터링

---

## 8. 개발 원칙

### 8.1 단계별 구현

한 번에 전체 앱을 구현하지 않는다.

각 Phase가 완료될 때마다 다음을 수행한다.

1. APK 빌드
2. 테스트 태블릿 설치
3. 기능 확인
4. Logcat 확인
5. 문제 수정
6. 다음 Phase 진행

### 8.2 작은 변경 단위

각 변경은 가능한 한 다음 중 하나만 포함한다.

- 하나의 기능
- 하나의 화면
- 하나의 서비스
- 하나의 데이터 모델
- 하나의 오류 수정

### 8.3 코드 생성 원칙

AI가 코드를 작성할 때 다음을 준수한다.

- 기존 파일을 먼저 확인한다.
- 프로젝트 구조를 임의로 변경하지 않는다.
- 필요하지 않은 라이브러리를 추가하지 않는다.
- 변경할 파일 목록을 먼저 제시한다.
- 전체 파일을 덮어쓰기 전에 변경 이유를 설명한다.
- 컴파일 가능한 코드를 작성한다.
- 사용되지 않는 import를 제거한다.
- Android API 버전별 차이를 고려한다.
- 모든 비동기 작업의 생명주기를 고려한다.
- GeckoSession과 GeckoRuntime의 생명주기를 Activity와 분리한다.
- 예외와 오류를 Logcat에 남긴다.
- 기능 구현 후 테스트 방법을 제시한다.

### 8.4 네트워크 정책

AI는 Manager App의 서버 통신 코드를 추가하지 않는다.

금지되는 예시:

```text
Retrofit을 이용한 자체 API 호출
Firebase 동기화
원격 설정 조회
사용자 데이터 업로드
분석 이벤트 전송
```

허용되는 예시:

```text
GeckoView에서 WebApp URL 로드
WebApp이 자체적으로 사용하는 네트워크 요청
WebApp의 로그인 및 API 사용
```

---

## 9. AI에게 작업을 요청하는 방식

AI에게는 다음 형식으로 한 번에 하나의 Phase만 요청한다.

```text
현재 프로젝트 구조를 먼저 확인하고, Phase N의 목표만 구현해줘.

조건:
1. 기존 구조를 최대한 유지할 것
2. 변경할 파일 목록을 먼저 제시할 것
3. 코드를 수정한 뒤 빌드 방법을 제시할 것
4. 실제 Android 태블릿에서 검증할 테스트 절차를 제시할 것
5. 서버 통신 코드를 추가하지 않을 것
6. 일반 화면에서 Android 상태 표시줄을 숨기지 않을 것
7. GeckoView만 사용할 것
8. 통합 GeckoRuntime 및 통합 세션 정책을 유지할 것
```

### Phase별 요청 예시

#### GeckoView 기본 구현

```text
Phase 1을 구현해줘.

목표:
- GeckoView를 화면에 표시한다.
- 테스트 URL을 로드한다.
- GeckoRuntime과 GeckoSession을 연결한다.
- 일반 화면에서는 Android 상태 표시줄을 유지한다.
- 웹 콘텐츠 전체화면에서는 상태 표시줄을 일시적으로 숨기고 종료 후 복원한다.
- 별도의 서버 통신은 추가하지 않는다.

먼저 현재 프로젝트 구조와 Gradle 설정을 확인하고,
변경할 파일 목록을 제시한 뒤 구현해줘.
```

#### User-Agent 구현

```text
Phase 4를 구현해줘.

요구사항:
- WebApp 설정에 User-Agent 모드를 저장한다.
- 기본값은 MOBILE이다.
- 태블릿/PC 옵션을 선택하면 DESKTOP User-Agent를 적용한다.
- WebApp 실행 시 설정값을 읽고 GeckoSession에 적용한다.
- 사용자가 직접 User-Agent 문자열을 입력하는 기능은 추가하지 않는다.
- 기존의 통합 세션 정책을 유지한다.
```

#### 전역 WebExtension 구현

```text
Phase 6을 구현해줘.

요구사항:
- APK 내부 assets에 내장 WebExtension을 추가한다.
- GeckoRuntime에 확장 기능을 한 번만 설치한다.
- 모든 WebApp의 GeckoSession에서 확장 기능을 사용할 수 있게 한다.
- 확장 기능 중복 설치를 방지한다.
- Manager에서 로컬 xpi 선택 설치와 AMO 서명 xpi 설치를 지원한다.
- AMO가 아닌 외부 URL의 xpi 설치는 허용하지 않는다.
- 필요한 권한만 사용한다.
```

#### 백그라운드 미디어 구현

```text
Phase 8을 구현해줘.

먼저 현재 GeckoView 미디어 재생 구조를 분석하고,
다음 단계로 나누어 구현해줘.

1. GeckoView MediaSession 이벤트 로그
2. Activity 백그라운드 전환 시 세션 유지
3. Foreground Service 구조
4. Android MediaSession 연결
5. 미디어 알림
6. 재생/일시정지 명령 전달

한 번에 전체 기능을 구현하지 말고 1단계만 먼저 구현해줘.
```

---

## 10. 최종 기능 목표

최종적으로 사용자는 다음 흐름을 경험해야 한다.

```text
Manager App 실행
→ WebApp 추가 선택
→ WebApp 이름 입력
→ WebApp URL 입력
→ 필요하면 태블릿/PC 옵션 선택
→ 저장
→ WebApp 목록에서 선택
→ GeckoView로 WebApp 실행
→ 일반 화면에서는 Android 상태 표시줄 표시
→ 웹 콘텐츠 전체화면 요청 시 상태 표시줄을 일시적으로 숨김
→ WebApp 로그인 및 세션 유지
→ 공통 WebExtension 적용
→ WebApp에서 알림 및 미디어 기능 사용
→ 홈 화면으로 이동해도 가능한 기능은 백그라운드에서 유지
```

Manager App은 전체 과정에서 로컬로 동작한다.

```text
Manager App
→ 로컬 설정 저장
→ 로컬 WebApp 목록 관리
→ 로컬 GeckoRuntime 및 세션 관리
→ 별도 서버 통신 없음
```

---

## 11. 최종 우선순위

구현 우선순위는 다음과 같다.

1. Android 프로젝트 생성 및 태블릿 테스트
2. GeckoView 기본 표시
3. WebApp URL 로드
4. 로컬 WebApp 저장
5. Manager App 목록 및 추가 화면
6. WebApp 실행
7. 모바일/PC User-Agent 적용
8. 통합 GeckoRuntime 및 세션 유지
9. 전역 내장 WebExtension
10. 일반 웹 알림
11. 백그라운드 미디어 재생
12. Android 미디어 알림
13. 오류 처리 및 안정화

각 단계는 이전 단계가 정상적으로 작동하는 것을 확인한 뒤 진행한다.