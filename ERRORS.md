# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.
메시지 문구는 바뀔 수 있으니 분기는 예외·결과 **타입**으로 한다. 어떤 메시지에도 받은 URI 원문은 들어가지 않는다.

## deeplink-core

예외는 두 종류뿐이다. 설정·호출 인자가 틀리면 `IllegalArgumentException`(받은 값과 허용 범위),
소비자가 꽂은 인터셉터·라우터가 실패하면 `DeepLinkException`(원인은 `cause`)인데 이것은 던지지 않고
`DeepLinkOutcome.Failed` 에 실어 돌려준다. 외부에서 온 URI 가 틀린 것은 예외가 아니라 `Rejection` 값이다.

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `UriRules` 의 상한이 1 미만일 때 | 값을 바로잡는다 |
| `IllegalArgumentException` | `DeepLinkUri.parse` 가 받을 수 없는 URI 일 때 — 메시지는 사유(`Rejection`)만 | 사유가 필요 없으면 `parseOrNull` |
| `IllegalArgumentException` | 템플릿 형식 오류: 파라미터 이름 규칙·중복, 짝 없는 중괄호, 조각 일부만 차지한 `{}`, 쿼리·프래그먼트·포트, URI 규칙 위반 | 템플릿을 고친다 |
| `IllegalArgumentException` | `Param` 이름이 영숫자와 `_-.` 1~64자가 아닐 때, `maxLength < 1`, 빈 `range`·`allowed`·`allowedHosts`, 소문자 호스트가 아닌 `allowedHosts` 항목 | 선언을 고친다 |
| `IllegalArgumentException` | 경로 id 형식(영숫자와 `_.:-` 1~64자), 템플릿에 없는 경로 파라미터 선언, 같은 이름 두 번 선언, 경로·쿼리 이름 충돌, 매처의 스킴·호스트가 비었거나 소문자가 아님 | 경로 정의를 고친다 |
| `IllegalArgumentException` | `DeepLinkHandler.Builder.build` 에 경로가 없거나, 경로 id 가 겹치거나, 라우터가 없을 때 | 조립을 고친다 |
| `IllegalArgumentException` | `CallerIdentity` 의 빈 패키지 이름, 또는 패키지 없이 `NONE` 이 아닌 검증 수준. `TrustPolicy.standard("")` | 값을 고친다 |
| `IllegalArgumentException` | `ResolvedDeepLink.get` 으로 값이 없는 파라미터를 읽을 때, `get`·`getOrNull` 에 그 경로에 선언한 `Param` 객체가 아닌 것을 넘길 때 | 선택 값은 `getOrNull`, 선언한 객체를 키로 쓴다 |
| `IllegalArgumentException` | `resume` 에 다른 처리기가 만든 링크를 넘길 때 | 만든 처리기로 재개한다 |
| (전파) `CancellationException`·`Error` | 인터셉터·라우터·검증기가 던진 취소와 `Error` 는 삼키지 않는다 | 코루틴 규칙대로 둔다 |
| (전파) 리스너 예외 | `DeepLinkListener.onOutcome` 이 던진 예외는 `handle`·`resume` 호출부로 간다 | 리스너가 던지지 않게 한다 |

### 값으로 알리는 신호

`DeepLinkOutcome` (`handle`·`resume` 결과):

| 결과 | 언제 | 호출부가 할 일 |
|---|---|---|
| `Handled` | 라우터가 `true` | 없음 |
| `Unhandled` | 검증은 통과했지만 라우터가 `false` | 기본 화면으로 보낸다 |
| `Rejected` | 검증 거부 또는 인터셉터 거부 | "열 수 없는 링크" 정도로 알리고 기본 화면 |
| `Deferred` | 인터셉터가 미룸(예: 로그인 필요) | 조건이 갖춰지면 `resume(link)` |
| `Failed` | 인터셉터·라우터가 `Exception` 을 던짐. `cause` 는 `DeepLinkException`, 그 `cause` 가 원래 예외. `proceed()` 를 두 번 부른 경우 원래 예외는 `IllegalStateException` | 원인을 크래시 리포트로 보내고 기본 화면 |

`Rejection` (`resolve` 거부·`Rejected` 결과). 판정 순서대로:

| 사유 | 언제 |
|---|---|
| `Malformed(EMPTY)` | 빈 문자열·공백뿐 |
| `TooLong(URI_LENGTH)` | 전체 길이가 `maxUriLength` 초과 |
| `Malformed(ILLEGAL_CHARACTER)` | 원문에 공백·제어·서식(양방향·폭 없음)·사설 영역·미할당 문자, 역슬래시·큰따옴표·꺾쇠·백틱·중괄호·세로 막대·캐럿. 또는 디코딩한 값에 공백 외 제어·서식 문자 |
| `Malformed(INVALID_SCHEME)` | 스킴이 없거나 `[a-z][a-z0-9+.-]*` 가 아님 |
| `Malformed(MISSING_AUTHORITY)` | `scheme:` 뒤에 `//` 가 없음 |
| `Malformed(USERINFO_NOT_ALLOWED)` | authority 에 `@` |
| `Malformed(INVALID_HOST)` | 빈 호스트, ASCII 도메인 라벨 형식이 아님(끝 점·`_`·IPv6·비 ASCII·`%` 포함), 253자 초과 |
| `Malformed(INVALID_PORT)` | 숫자가 아니거나 1..65535 밖 |
| `TooLong(PATH_SEGMENTS)` | 경로 조각 수 초과 |
| `Malformed(EMPTY_SEGMENT)` | 경로 중간의 `//` |
| `Malformed(INVALID_PERCENT_ENCODING)` | `%` 뒤 16진 두 자리가 아님, 잘못된·지나치게 긴 UTF-8, 서로게이트 |
| `Malformed(DOT_SEGMENT)` | `.`·`..`(인코딩 포함) |
| `Malformed(ENCODED_SLASH)` | 디코딩하면 `/`·`\` 인 경로 조각(`allowEncodedSlash` 가 꺼져 있을 때) |
| `Malformed(DOUBLE_ENCODING)` | 디코딩한 경로 조각·쿼리 이름에 `%XX` 가 남음(`allowDoubleEncoding` 이 꺼져 있을 때) |
| `TooLong(SEGMENT_LENGTH)` | 디코딩한 경로 조각 길이 초과 |
| `TooLong(QUERY_PARAMETERS)` | 쿼리 쌍 개수 초과(선언 외 포함) |
| `Malformed(INVALID_QUERY)` | 쿼리 이름이 빔 |
| `TooLong(QUERY_VALUE_LENGTH)` | 디코딩한 쿼리 값 길이 초과 |
| `UnknownScheme` | 어느 경로에도 없는 스킴 |
| `UnknownHost` | 그 스킴에 없는 호스트, 또는 기본 포트가 아닌 포트 |
| `NoMatchingRoute` | 스킴·호스트는 맞지만 경로가 어느 매처에도 맞지 않음 |
| `InsufficientTrust` | 신뢰 정책 결과가 경로의 `requiredTrust` 미만(파라미터 검증보다 먼저 본다) |
| `InvalidParameter` | 경로·쿼리 값이 검증기를 통과하지 못함. 검증기가 예외를 던지면 사유는 `검증기 예외 <타입명>` |
| `UnexpectedParameter` | 선언 외 쿼리가 왔고 경로가 `UnknownQueryPolicy.REJECT` |
| `DuplicateParameter` | 선언한 쿼리 이름이 두 번 이상 |
| `MissingParameter` | 필수 쿼리가 없음 |
| `Intercepted` | 인터셉터가 직접 돌려준 거부 |
| `Custom` | 소비자 정의 단계가 돌려준 거부 |

`ParamCheck.Invalid` 의 기본 사유 문구: `비어 있다`, `길이 N 가 상한 M 을 넘는다`, `이중 인코딩된 값이다`, `형식이 맞지 않는다`,
`정수가 아니다`, `범위 a..b 밖이다`, `true·false 가 아니다`, `허용 값이 아니다`, `UUID 가 아니다`,
`URL 형식이 아니다`, `https 가 아니다`, `포트를 지정할 수 없다`, `허용하지 않은 호스트다`.

## deeplink

### 예외

| 예외 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IllegalArgumentException` | `TrustedCaller` 의 패키지 이름 형식 오류, 빈 다이제스트 목록, SHA-256(16진 64자, 콜론 허용) 형식이 아닌 다이제스트 | 값을 고친다 |
| `IllegalArgumentException` | `CallerVerifier.create`·`DeepLinkIntentReader.create` 의 `trustedCallers` 에 같은 패키지가 두 번 | 하나로 합친다 |
| `IllegalArgumentException` | `DeepLinkIntentReader.create` 의 빈 `allowedActions` | 액션을 적는다 |
| `IllegalArgumentException` | `SafeExtras.string` 의 `maxLength < 1` | 값을 고친다 |
| `IllegalArgumentException` | `AndroidDeepLinks.selfIntent`·`selfPendingIntent` 의 URI 가 `DeepLinkUri.parse` 를 통과하지 못함, `selfPendingIntent` 의 flags 에 `FLAG_MUTABLE` | URI·플래그를 고친다 |

### 값으로 알리는 신호

| 신호 | 언제 | 호출부가 할 일 |
|---|---|---|
| `IntentReadResult.NotDeepLink` | 인텐트가 null, 허용하지 않은 액션, data 없음 | 평소 시작 흐름 |
| `IntentReadResult.Unsafe(HAS_SELECTOR)` | 셀렉터가 붙은 인텐트 | 아무것도 열지 않고 평소 시작 흐름 |
| `IntentReadResult.Unsafe(UNREADABLE_EXTRAS)` | extras 를 풀 때 `RuntimeException`(`BadParcelableException` 등) | 위와 같다 |
| `CallerIdentity.UNKNOWN` | referrer 가 없거나 `android-app://<패키지>` 가 아님, 또는 발신자 판정 중 extras 를 풀 수 없음(가장 낮은 신뢰로 낮춘다) | 없음 — PUBLIC 경로만 열린다 |
| `CallerVerification.CLAIMED` | 현재·전달 인텐트에 `EXTRA_REFERRER`·`EXTRA_REFERRER_NAME` 이 있음 | 없음 — 기본 정책은 PUBLIC |
| `SYSTEM_REPORTED` 에 머묾 | 신뢰 목록 앱인데 서명이 다르거나, 미설치·패키지 가시성(`<queries>`) 누락으로 조회 실패 | 매니페스트 `<queries>`·다이제스트를 확인한다 |
| `ExtraRead.Absent` | 키가 없음 | 기본 동작 |
| `ExtraRead.Invalid(WRONG_TYPE)` | 다른 타입이거나 값이 null | 무시한다 |
| `ExtraRead.Invalid(TOO_LONG)` | 문자열이 상한보다 김 | 무시한다 |
| `ExtraRead.Invalid(UNREADABLE)` | extras 를 풀 수 없음 | 무시한다 |
