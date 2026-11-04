# ForgeGen Bridge — endpointy, wymagania i body

Wersja rozszerzenia: **1.0.0**. Wersja API: **1.0**.
Dokument sprawdzony z lokalnym `openapi.json` i kodem routera 2026-11-04.
Zawiera wszystkie **34 operacje: 18 GET, 10 POST, 5 DELETE i 1 PATCH**, oraz
obsługę `OPTIONS` dla znanych ścieżek. Poprawna nazwa metody to `OPTIONS`, nie `OPTION`.

Ten dokument opisuje implementację. Nie potwierdza, że nowy pomost został już
załadowany przez działający proces Forge. Ostatnie sprawdzenie localhost miało
timeout; instalacja nie uruchamiała ani nie restartowała Forge.

## Adresy i oznaczenia

Domyślna baza lokalna: `http://127.0.0.1:7860`.
Prefiks wszystkich endpointów: `/forgegen/v1`.
Przykład pełnego adresu: `http://127.0.0.1:7860/forgegen/v1/info`.
Jeżeli Forge działa pod subścieżką, np. `/forge`, zachowaj ją przed prefiksem API.

Identyfikatory z `{nawiasami}` zastępuj wartościami otrzymanymi z API.
Lokalne `model_id`, `extension_id`, `device_id`, `operation_id` są identyfikatorami
zasobów; nie podawaj zamiast nich ścieżek systemowych. `model_id`, `version_id`
i `file_id` Civitai są dodatnimi identyfikatorami liczbowymi dostawcy.
`root_id` pochodzi z `GET /forgegen/v1/models/roots`.

Wartości w przykładach JSON są fikcyjne. Nie ma tu tokenów urządzeń,
kodów parowania, haseł administratora ani klucza Civitai.

## Nagłówki i wspólne wymagania

| Nagłówek | Wymaganie |
| --- | --- |
| `Authorization` | Dla chronionych endpointów: schemat `Bearer` i token urządzenia otrzymany przy parowaniu. Wartość sekretu nie jest pokazana w dokumencie. |
| `Idempotency-Key` | Wymagany przy każdym POST, PATCH i DELETE. Niepusty string, maksymalnie 200 znaków; najlepiej osobny losowy UUID dla każdego zamiaru użytkownika. |
| `Content-Type` | Przy wysyłaniu JSON ustaw `application/json`. |
| `Accept` | Zalecane `application/json`; OPTIONS odpowiada bez body. |
| `X-ForgeGen-API-Version` | Opcjonalny. Jeśli występuje, musi mieć wartość `1.0`; inna wartość daje `426 INCOMPATIBLE_PROTOCOL`. |

Zastane uwierzytelnianie Forge lub reverse proxy pozostaje niezależne. Publiczny
endpoint pomostu nie omija dodatkowego logowania narzuconego przez hosta.

- Body żądania ma limit **64 KiB**. Nadmiarowe pola w zdefiniowanych modelach JSON
  są odrzucane przez walidację.
- **DELETE nie przyjmuje body**, nawet `{}`. Parametry dodatkowe podawaj w query.
- GET i OPTIONS wysyłaj bez body. POST ponowienia pobrania też wysyłaj bez body.
- POST ze zdefiniowanym body wymaga obiektu JSON. Dla prostego `WaitRequest`
  wystarczy `{}`; brak body nie jest równoważny temu obiektowi.
- Ten sam `Idempotency-Key` i ten sam zamiar zwracają poprzednio przyjęty wynik.
  Zmieniona treść lub query dla tej samej mutacji daje `409 IDEMPOTENCY_CONFLICT`.
- Uprawnienia pochodzą z serwera. Klient nie może sam dopisać scope do żądania.
- Odpowiedzi mają `Cache-Control: no-store`, `X-Request-ID` i
  `X-ForgeGen-API-Version`.

## Aktualne ograniczenia tej instalacji

Polityka właściciela to **`count_only`**. Pozwala udostępnić skonfigurowane
identyfikatory katalogów i bezpośrednie liczniki modeli. Katalog modeli z nazwami,
szczegóły, skanowanie/odświeżanie, przenoszenie, usuwanie i pobieranie są
zablokowane z `403 PRIVACY_POLICY`. Mają implementację i testy na sztucznych
zasobach, lecz dokumentacja nie stanowi zgody na użycie ich na istniejących modelach.
Panel i API nie mogą zmienić tej polityki.

Pobieranie i zarządzanie istniejącymi modelami oznaczono poniżej jako **P**.
Warunki konfiguracyjne:

| Oznaczenie | Znaczenie |
| --- | --- |
| B | Pomost włączony: `enabled = true`. |
| M | Moduł modeli włączony: `models_enabled = true`. |
| D | Moduł pobierania włączony: `downloads_enabled = true`. |
| C | Civitai włączone, wybrana potwierdzona domena i klucz skonfigurowany wyłącznie po stronie serwera. Domyślnie integracja jest wyłączona. |
| E | Moduł rozszerzeń włączony: `extensions_enabled = true`. |
| S | Moduł administracji serwerem włączony: `server_enabled = true`. |
| F | Flaga `--enable-insecure-extension-access`, scope `extensions.manage` oraz zgoda wynikająca z `disable_extension_access` i globalnych ograniczeń Forge. |
| P | Pełny dostęp do modeli; **niedostępny przy obecnej polityce `count_only`**. |

Brak scope daje `403 SCOPE_REQUIRED`. Brak flagi F daje `403 FLAG_REQUIRED`;
inne ograniczenia Forge mogą dać `403 FORGE_RESTRICTED`. Brak konfiguracji
Civitai jest raportowany jako `NOT_CONFIGURED`, zwykle z HTTP 403 przy sprawdzaniu
dostępności funkcji. Aktualne rozróżnienie znajdziesz
w `/capabilities`: `supported`, `enabled`, `authorized`, `reason_code`.

## GET — odczyt i wykrywanie

Wszystkie poniższe żądania mają **puste body**, a poprawna odpowiedź to HTTP **200**.

| Metoda i ścieżka | Scope | Dodatkowe warunki | Query | Wynik |
| --- | --- | --- | --- | --- |
| `GET /forgegen/v1/info` | Publiczne | Brak tokenu pomostu | Brak | Identyfikator pomostu, SemVer, wersja API, UUID serwera, sposób parowania i adres OpenAPI |
| `GET /forgegen/v1/openapi.json` | Publiczne | Brak tokenu pomostu | Brak | Dokument OpenAPI 3.1 |
| `GET /forgegen/v1/devices/me` | Dowolny ważny token | Brak dodatkowego scope | Brak | Bieżące urządzenie i zapisane scope |
| `GET /forgegen/v1/capabilities` | Dowolny ważny token | Dostępne również do rozpoznania wyłączonych funkcji | Brak | Możliwości i powody blokad dla tego urządzenia |
| `GET /forgegen/v1/devices` | `devices.manage` | Brak wymogu B | `offset`, `limit` | Lista urządzeń bez tokenów |
| `GET /forgegen/v1/models/roots` | `models.read` | B, M | `offset`, `limit` | Identyfikatory i typy katalogów, bez ścieżek systemowych |
| `GET /forgegen/v1/models/counts` | `models.read` | B, M | `offset`, `limit` | Bezpośrednie liczniki modeli; `recursive: false` |
| `GET /forgegen/v1/models` | `models.read` | B, M, P | `offset`, `limit` | Katalog zarejestrowanych modeli |
| `GET /forgegen/v1/models/{model_id}` | `models.read` | B, M, P; istniejący zasób | Brak | Model: UUID, typ, nazwa, rozmiar, dostępne metadane i źródło |
| `GET /forgegen/v1/downloads` | `downloads.manage` | P; historia dostępna także po wyłączeniu modułu pobierania | `offset`, `limit` | Lista operacji pobierania |
| `GET /forgegen/v1/downloads/{operation_id}` | `downloads.manage` | P; operacja musi być pobieraniem | Brak | Stan wskazanego pobierania |
| `GET /forgegen/v1/civitai/models` | `civitai.search` | B, C | `query`, `offset`, `limit` | Wyniki wyszukiwania, wersje i projekcje plików |
| `GET /forgegen/v1/civitai/models/{model_id}` | `civitai.search` | B, C; dodatni identyfikator liczbowy | Brak | Szczegóły modelu, źródło, informacje licencyjne i wersje |
| `GET /forgegen/v1/civitai/versions/{version_id}` | `civitai.search` | B, C; dodatni identyfikator liczbowy | Brak | Wersja i jej lista plików z `file_id` |
| `GET /forgegen/v1/extensions` | `extensions.read` | B, E; flaga F nie jest wymagana do odczytu | `offset`, `limit` | UUID, nazwa, wersja lub null, stan obecny i na następny start, wymagany restart |
| `GET /forgegen/v1/server/status` | `status.read` | B; odczyt statusu nie wymaga włączenia administracyjnego modułu S | Brak | Zajętość, dostępna faza, postęp, pamięć i ostatnie błędy pomostu |
| `GET /forgegen/v1/operations` | Dowolny ważny token | Lista jest filtrowana według scope dla rodzaju operacji; może być pusta | `offset`, `limit` | Lista dostępnych operacji |
| `GET /forgegen/v1/operations/{operation_id}` | Scope rodzaju operacji | Zasady w tabeli poniżej | Brak | Szczegóły jednej operacji |

### Query dla list

| Parametr | Typ i ograniczenia | Domyślnie |
| --- | --- | --- |
| `offset` | Integer, co najmniej 0 | `0` |
| `limit` | Integer 1–100 | `50`; dla wyszukiwania Civitai `20` |
| `query` | String, maksymalnie 200 znaków; tylko wyszukiwanie Civitai | Pusty string |

Dla wyszukiwania Civitai wymagane jest `offset % limit == 0`.
Przykład query: `?query=example&offset=0&limit=20`.
Liczniki nie przeglądają podfolderów ani nie zwracają nazw, rozmiarów lub metadanych plików.

Scope operacji zależy od jej `kind`:

| Rodzaj operacji | Scope odczytu lub anulowania | Dodatkowo |
| --- | --- | --- |
| `models.*` | `models.manage` | P |
| `download.*` | `downloads.manage` | P |
| `extensions.*` | `extensions.manage` | Odczyt i anulowanie już przyjętej operacji nie wymagają ponownego rozpoczynania zmiany rozszerzeń |
| `server.*` | `server.manage` | Odczyt i anulowanie już przyjętej operacji |

Scope dotyczą rodzaju operacji, a nie tylko autora zadania. Urządzenie z prawem
zarządzania danym rodzajem widzi dostępną wspólną kolejkę tego rodzaju.

## POST — parowanie i operacje

Każdy POST wymaga `Idempotency-Key`. Poza wymianą zaproszenia wymagany jest też
token urządzenia. Pola body i nazwy komunikatów pozostają po angielsku.

### `POST /forgegen/v1/pairing/exchange`

Wymagania: ważne, niewygasłe zaproszenie administratora. Token urządzenia nie jest
jeszcze wymagany. Sukces: **200**, model `PairResponse`.

Body `PairRequest`:

| Pole | Typ | Wymagane | Znaczenie |
| --- | --- | --- | --- |
| `invitation_id` | String 1–100 znaków | Tak | Identyfikator pobrany z QR |
| `code` | Tajny string 16–256 znaków | Tak | Jednorazowa wartość z QR; celowo bez wartości przykładowej |
| `device_name` | String 1–100 znaków | Tak | Nazwa urządzenia, np. `Android phone` |

Zaproszenie domyślnie wygasa po 120 sekundach. Kod jest jednorazowy; identyczne
ponowienie z tym samym kluczem jest możliwe tylko przed wygaśnięciem zaproszenia
i odwołaniem urządzenia. Body nie może zawierać `scopes` ani dodatkowych pól.
Nie ma anonimowego endpointu tworzącego zaproszenie lub administratora.

Odpowiedź zawiera `device`, `token_type` oraz tajne `access_token`.
Wartości tokenu nie umieszczono w przykładach ani w tym dokumencie.

### `POST /forgegen/v1/models/scan`

Wymagania: `models.manage`, B, M, P. Sukces: **202**.
Body wymagane; minimum `{}`.

```json
{
  "wait_for_idle": true
}
```

`wait_for_idle` jest opcjonalnym booleanem, domyślnie `false`.
Obejmuje skanowanie zarejestrowanych katalogów i odświeżenie list Forge.
W obecnej instalacji operacja jest zablokowana polityką prywatności.

### `POST /forgegen/v1/models/{model_id}/move`

Wymagania: `models.manage`, B, M, P; zarejestrowany model, katalog docelowy tego
samego typu, wolna nazwa docelowa. Zasób nie może pozostawać w użyciu. Sukces: **202**.

```json
{
  "root_id": "checkpoint-default",
  "name": "example-checkpoint.safetensors",
  "wait_for_idle": true
}
```

Wymagane: `root_id` (1–100 znaków), `name` (1–255 znaków).
Nazwa jest pojedynczą bezpieczną nazwą pliku, bez ścieżki. Rozszerzenie pliku musi
być obsługiwane. Przeniesienie zachowuje lokalny identyfikator modelu.

### `POST /forgegen/v1/downloads`

Wymagania: `downloads.manage`, B, D, P; skonfigurowany katalog i dozwolony URL.
Sukces: **202**.

Szablon body — w URL należy zastąpić `{version_id}` i `{file_id}` identyfikatorami źródła:

```json
{
  "url": "https://civitai.com/api/download/models/{version_id}?fileId={file_id}",
  "root_id": "checkpoint-default",
  "name": "example-checkpoint.safetensors",
  "expected_sha256": null,
  "wait_for_idle": false
}
```

| Pole | Wymaganie |
| --- | --- |
| `url` | Wymagane, 1–8192 znaków; HTTPS na porcie 443, źródłowo `civitai.com` lub `civitai.red`; bez loginu, hasła, fragmentu i klucza API w query. |
| `root_id` | Wymagane, 1–100 znaków; identyfikator skonfigurowanego katalogu. |
| `name` | Wymagane, 1–255 znaków; bezpieczna pojedyncza nazwa pliku. |
| `expected_sha256` | Opcjonalny string dokładnie 64 cyfr szesnastkowych albo null; jeśli podany, musi się zgadzać przed publikacją. |
| `wait_for_idle` | Opcjonalny boolean, domyślnie false. True dodatkowo czeka przed kontaktem ze źródłem. |

Publikacja i odświeżenie zawsze czekają na bezczynność. Istniejący plik nie jest
nadpisywany. Obsługiwane końcówki: `.safetensors`, `.ckpt`, `.pt`, `.pth`, `.bin`,
`.sft`, `.gguf`. Bezpośrednie pobranie nie przekazuje automatycznie klucza Civitai;
dla pliku wymagającego uwierzytelnienia dostawcy użyj `/civitai/downloads`.

### `POST /forgegen/v1/downloads/{operation_id}/retry`

Wymagania: `downloads.manage`, B, D, P; operacja pobierania w stanie `failed`,
`interrupted` lub `cancelled`. Sukces: **202**. **Body: brak**.

Ponowienie zachowuje `operation_id`. Nie przekazuj nowego URL, nazwy ani hasha.
Wznowienie częściowego pliku zależy od potwierdzonego Range i tożsamości pliku;
gdy bezpieczne wznowienie nie jest możliwe, pobranie zaczyna się od początku.

### `POST /forgegen/v1/civitai/downloads`

Wymagania: `downloads.manage`, B, C, P; wybrana dokładna wersja i plik.
Sukces: **202**. Scope `civitai.search` nie jest wymagany do samego zlecenia pobrania.

```json
{
  "version_id": 200,
  "file_id": 300,
  "root_id": "checkpoint-default",
  "wait_for_idle": false
}
```

Wymagane: dodatnie integer `version_id`, dodatnie integer `file_id`, string `root_id`.
Podane liczby są fikcyjne. Dane źródła, nazwa, typ, hash i informacje licencyjne
są ustalane przez serwer. Nie przekazuj klucza API w body lub URL.
Typ katalogu musi pasować do wybranego pliku.

Moduł D musi być włączony, aby worker wykonywał pobieranie. Jeśli D jest wyłączony,
zlecenie tego endpointu może zostać przyjęte do kolejki, ale nie rozpocznie transferu.

### `POST /forgegen/v1/extensions`

Wymagania: `extensions.manage`, B, E, F; nowy katalog docelowy.
Sukces: **202**, wymagany restart po instalacji.

```json
{
  "repository_url": "https://github.com/example/forge-extension.git",
  "directory_name": "example-extension",
  "wait_for_idle": true
}
```

Wymagane: `repository_url` (1–2048 znaków), `directory_name` (1–100 znaków).
Repozytorium musi mieć adres HTTPS `github.com/owner/repository` bez danych logowania,
query i fragmentu. Katalog ma prostą nazwę z liter ASCII/cyfr/`_`/`.`/`-`, rozpoczynającą
się literą ASCII lub cyfrą. Nie może istnieć ani wskazywać katalogu pomostu.
Przykładowy adres repozytorium jest fikcyjny.

Worker klonuje repozytorium bez uruchamiania jego `install.py`. Zastosowanie kodu
i zależności następuje zgodnie ze zwykłym mechanizmem Forge przy restarcie.

### `POST /forgegen/v1/extensions/bulk`

Wymagania: `extensions.manage`, B, E, F. Sukces: **202**.
Body wymaga `action`; `wait_for_idle` jest opcjonalne i domyślnie false.

Wyłączenie wskazanych rozszerzeń:

```json
{
  "action": "disable",
  "extension_ids": [
    "11111111-1111-4111-8111-111111111111"
  ],
  "wait_for_idle": true
}
```

Dozwolone `action`: `enable`, `disable`, `uninstall`, `disable_others`.
Dla pierwszych trzech podaj 1–100 unikalnych, zarejestrowanych `extension_ids`.
Identyfikator z przykładu jest fikcyjny. Pomost i rozszerzenia wbudowane są chronione.

Wyłączenie pozostałych rozszerzeń z zachowaniem pomostu:

```json
{
  "action": "disable_others",
  "extension_ids": [],
  "wait_for_idle": true
}
```

Dla `disable_others` lista musi być pusta lub pominięta; serwer wybiera bezpieczne
cele. Operacja nie ustawia globalnej opcji wyłączającej wszystkie rozszerzenia.

### `POST /forgegen/v1/server/unload`

Wymagania: `server.manage`, B, S; dostęp do bezczynnego serwera.
Sukces: **202**. Body wymagane; minimum `{}`.

```json
{
  "wait_for_idle": true
}
```

Wywołuje obsługiwany mechanizm zwolnienia modelu w Forge. Nie usuwa plików modelu.

### `POST /forgegen/v1/server/restart`

Wymagania: `server.manage`, B, S oraz obsługiwany mechanizm nadzorowanego restartu
Forge. Sukces: **202**; bez dostępnego mechanizmu `501 UNSUPPORTED_BACKEND`.
Body wymagane; minimum `{}`.

```json
{
  "wait_for_idle": true
}
```

Wynik operacji potwierdza zlecenie restartu, a nie gotowość nowego procesu.
Po restarcie klient musi się ponownie połączyć. Po utracie odpowiedzi ponawiaj
żądanie z tym samym `Idempotency-Key`, aby nie zlecić drugiego restartu.

## PATCH — włączanie i wyłączanie rozszerzenia

### `PATCH /forgegen/v1/extensions/{extension_id}`

Wymagania: token, `extensions.manage`, `Idempotency-Key`, B, E, F.
Sukces: **202**. Pole `enabled` jest wymaganym booleanem.

```json
{
  "enabled": false,
  "wait_for_idle": true
}
```

`enabled: true` włącza, `enabled: false` wyłącza rozszerzenie na następny start.
Pomost oraz rozszerzenia wbudowane są chronione. Stan po zmianie wymaga restartu;
API nie deklaruje natychmiastowego wyładowania zaimportowanego kodu.
`PUT` nie jest zamiennikiem tej operacji i zwraca `405 METHOD_NOT_ALLOWED`.

## DELETE — usuwanie, odwołanie i anulowanie

Wszystkie DELETE wymagają tokenu i `Idempotency-Key`. **Nie wysyłaj body ani `{}`**.

| Metoda i ścieżka | Scope | Query | Dodatkowe warunki | Wynik |
| --- | --- | --- | --- | --- |
| `DELETE /forgegen/v1/devices/{device_id}` | `devices.manage` | Brak | Istniejące urządzenie; brak wymogu B | **200**, `Device`: odwołanie dostępu, ustawienie `revoked_at`, żądanie anulowania jego aktywnych/pending operacji |
| `DELETE /forgegen/v1/models/{model_id}` | `models.manage` | `wait_for_idle=false` lub `true` | B, M, P; zarejestrowany, nieużywany model | **202**, `Accepted`: usunięcie pliku modelu i odświeżenie list |
| `DELETE /forgegen/v1/downloads/{operation_id}` | `downloads.manage` | Brak | P; istniejąca operacja pobierania, która jeszcze się nie zakończyła | **202**, `Accepted`: anulowanie pobierania, z zachowaniem wpisu historii |
| `DELETE /forgegen/v1/extensions/{extension_id}` | `extensions.manage` | `wait_for_idle=false` lub `true` | B, E, F; zarejestrowane rozszerzenie, chronione cele zabronione | **202**, `Accepted`: odinstalowanie do kwarantanny, wymagany restart |
| `DELETE /forgegen/v1/operations/{operation_id}` | Scope rodzaju operacji | Brak | Operacja jeszcze niezakończona; dla modelu/pobrania P | **202**, `Accepted`: żądanie anulowania, bez kasowania historii |

Domyślne `wait_for_idle` wynosi false. Query do oczekiwania:

```http
DELETE /forgegen/v1/models/{model_id}?wait_for_idle=true
DELETE /forgegen/v1/extensions/{extension_id}?wait_for_idle=true
```

Do powyższych linii dodaj nagłówki autoryzacji i idempotencji opisane na początku;
celowo nie zawierają wartości sekretów.

Anulowanie jest kooperacyjne. Już zatwierdzona operacja atomowa może zakończyć się
przed obsłużeniem żądania anulowania. Operacja w stanie końcowym daje
`409 OPERATION_CONFLICT`. Anulowanie pobierania pozostaje możliwe po wyłączeniu
modułu D; nadal wymaga scope oraz polityki P.

## OPTIONS — dostępne metody danej ścieżki

Żądanie ma postać `OPTIONS` na **konkretnej, istniejącej ścieżce**, bez body.
Pomost nie wymaga dla niego tokenu ani `Idempotency-Key`; zastane zabezpieczenia
hosta nadal obowiązują. Nie używaj literalnych nawiasów `{}` w adresie wysłanego żądania.

Przykład z fikcyjnym identyfikatorem:

```http
OPTIONS /forgegen/v1/models/11111111-1111-4111-8111-111111111111
X-ForgeGen-API-Version: 1.0
```

Odpowiedź:

```http
HTTP/1.1 204 No Content
Allow: DELETE, GET, OPTIONS
X-ForgeGen-API-Version: 1.0
Cache-Control: no-store
```

Przykładowe wartości `Allow`:

| Ścieżka | Metody |
| --- | --- |
| `/forgegen/v1/info` | `GET, OPTIONS` |
| `/forgegen/v1/pairing/exchange` | `POST, OPTIONS` |
| `/forgegen/v1/downloads` | `GET, POST, OPTIONS` |
| `/forgegen/v1/models/{model_id}` | `DELETE, GET, OPTIONS` |
| `/forgegen/v1/extensions/{extension_id}` | `DELETE, PATCH, OPTIONS` |
| `/forgegen/v1/server/restart` | `POST, OPTIONS` |

OPTIONS sprawdza istnienie wzorca ścieżki, nie istnienie zasobu o danym ID ani
uprawnienia do jego użycia. Nieznana ścieżka daje `404 ENDPOINT_NOT_FOUND`.
Odpowiedź nie rozluźnia CORS hosta. Występuje w kodzie routera, lecz nie jest
osobną operacją w wygenerowanym OpenAPI.

## Odpowiedzi, śledzenie operacji i błędy

Przyjęcie długiego zadania ma postać HTTP 202 z body `Accepted`, np.:

```json
{
  "operation_id": "11111111-1111-4111-8111-111111111111",
  "status_url": "/forgegen/v1/operations/11111111-1111-4111-8111-111111111111"
}
```

Stan pobieraj przez `GET /forgegen/v1/operations/{operation_id}`.
Możliwe statusy: `queued`, `waiting_idle`, `running`, `completed`, `interrupted`,
`failed`, `cancelled`. Nieznany postęp i rozmiar mają wartość null.
Identyfikator i przyjęcie są zapisane trwale przed wysłaniem 202.

Listy mają pola `items`, `offset`, `limit`, `total`; `total` Civitai może być null,
jeżeli dostawca go nie podał. Szczegóły modeli odpowiedzi znajdują się w OpenAPI:

| Model | Ważne pola |
| --- | --- |
| `Info` | `bridge_id`, `bridge_version`, `api_version`, `server_id`, `authentication`, `pairing`, `openapi_url` |
| `Device` | `id`, `name`, `scopes`, `created_at`, `revoked_at` |
| `ModelCount` | `root_id`, `type`, `count`, `recursive`, `reason_code` |
| `Model` | `id`, `type`, `name`, `root_id`, `size_bytes`, `metadata`, `source` |
| `Extension` | `id`, `name`, `version`, `builtin`, `protected`, `current_enabled`, `next_enabled`, `installed`, `restart_required` |
| `Operation` | `id`, `kind`, `status`, `phase`, `progress`, `bytes_done`, `bytes_total`, `created_at`, `updated_at`, `result`, `error`, `restart_required` |
| `ServerStatus` | `busy`, `phase`, `progress`, `elapsed_seconds`, `memory`, `recent_errors`, `backend` |

Daty są UTC/ISO 8601. Nieznane parametry statusu są null. Ostatnie błędy obejmują
bezpieczne komunikaty pomostu; API nie odczytuje tekstów wyjątków zawierających prompty.

Spójny błąd API, z fikcyjnym identyfikatorem żądania:

```json
{
  "error": {
    "code": "SCOPE_REQUIRED",
    "message": "The device does not have the required scope.",
    "retryable": false
  },
  "request_id": "11111111-1111-4111-8111-111111111111"
}
```

| HTTP | Przykładowe znaczenie |
| --- | --- |
| 400 | Brak lub niepoprawna wartość `Idempotency-Key`, niepoprawna ścieżka lub paginacja |
| 401 | `AUTH_REQUIRED`, `TOKEN_REVOKED`, `PAIRING_INVALID` |
| 403 | `SCOPE_REQUIRED`, `FLAG_REQUIRED`, `FORGE_RESTRICTED`, `PRIVACY_POLICY`, `MODULE_DISABLED`, `NOT_CONFIGURED`, `BRIDGE_PROTECTED` |
| 404 | Nieznany endpoint, model, urządzenie, rozszerzenie lub operacja |
| 405 | Metoda nieobsługiwana przez tę ścieżkę |
| 409 | Konflikt klucza, zajęty/używany zasób, istniejący cel, zakończona operacja |
| 413 | Zbyt duże żądanie lub pobranie |
| 422 | Walidacja body, body przy DELETE, błędny hash |
| 426 | Niezgodna wersja protokołu |
| 429 | Limit prób lub pełna kolejka |
| 500 | Wewnętrzny błąd pomostu, bez ujawniania sekretów |
| 501 | Nieobsługiwany mechanizm backendu |
| 502/503 | Niedostępność dostawcy, brak konfiguracji lub błąd połączenia |
| 507 | Brak miejsca |

Brak pomostu, timeout albo brak autoryzacji pomostu nie powinny wyłączać
podstawowego klienta Forge ani istniejącej integracji Infinite Image Browsing.

## Pliki źródłowe kontraktu

- [openapi.json](openapi.json) — dokładne schematy żądań i odpowiedzi.
- [APP_INTEGRATION.md](APP_INTEGRATION.md) — kontrakt dla klienta Android i scenariusze fallback.
- [Przykładowe odpowiedzi](examples/manifest.json) — 14 fikcyjnych odpowiedzi z przypisaniem do schematów.
- [INTEGRATION_HANDOFF.md](../INTEGRATION_HANDOFF.md) — instalacja, środowisko, wyniki testów i aktywacja.

Dokument przygotowano wyłącznie na podstawie kodu i kontraktu. Nie wykonano
opisanych operacji na działającym Forge, nie użyto computer-use i nie odczytano
promptów, modeli ani wygenerowanych obrazów.
