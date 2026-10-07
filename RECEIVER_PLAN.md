# План: Movie Portal TV — мінімальний приймач для movie-portal

Похідна робота від [jellyfin-androidtv](https://github.com/jellyfin/jellyfin-androidtv) (GPL-2.0, файл `LICENSE`
збережено). Гілка `receiver`.

## 1. Результати дослідження оригіналу

| Що | Де в оригіналі | Висновок |
|---|---|---|
| Модулі Gradle | `:app`, `:design`, `:preference`, `:playback:core`, `:playback:jellyfin`, `:playback:media3:exoplayer`, `:playback:media3:session` | Новий плеєр (`playback/*`) у застосунку використовується лише для музики, відео грає старий `ui/playback/VideoManager.java` + `PlaybackController.java`. Обидва глибоко зав'язані на UI бібліотек, Leanback, Koin і налаштування. |
| DI | Koin (`di/*Module.kt`), `androidx.startup` ініціалізатори | Для 10 класів DI не потрібен: простий `AppGraph` у `Application`. |
| SDK | Jellyfin Kotlin SDK 1.8.12 (`createJellyfin`, `ApiClient`, OkHttp 5) | Залишається для REST і моделей (`DeviceProfile`, `PlaybackInfoDto`, звіти). |
| WebSocket і команди | `data/eventhandling/SocketHandler.kt`, SDK `api.webSocket` | Сокет живе лише поки процес у стані `RESUMED` (`repeatOnLifecycle(RESUMED)`), тобто **у фоні оригінал команд не отримує**. Пишемо свій сокет у Foreground Service. |
| Quick Connect | `ui/startup/*`, `auth/repository/*` | Поряд із паролем, вибором сервера й користувача. Пишемо заново, лише Quick Connect. |
| Запуск Activity з фону | Немає: `SYSTEM_ALERT_WINDOW` і full-screen intent відсутні, `PlaybackHelper.retrieveAndPlay` просто викликає `startActivity` з контексту застосунку | Реалізуємо самі (див. розд. 4). |
| DeviceId | `androidDevice(context)` у SDK (на основі `ANDROID_ID`) + `forUser()` | Свій UUID, похідний від `ANDROID_ID` з власною «сіллю», щоб не збігатися з офіційним застосунком. |
| Профіль пристрою | `util/profile/*` (MediaCodec-запити AVC/HEVC/AV1/DoVi/HDR) | Беремо `codec/*` майже без змін, `deviceProfile.kt` спрощуємо (без налаштувань користувача). |
| Аналітика/збої | ACRA (`telemetry/`), toast-звіт | Видаляємо. |

Репозиторій порталу за шляхом `/home/vaksick/movie-portal` на цій машині відсутній, тому вимоги порталу взято з опису
завдання (розд. 5).

## 2. Що лишається

- `LICENSE` (GPL-2.0), `buildSrc` (версії), `gradle/wrapper`, `detekt.yaml`, `android-lint.xml`, `.editorconfig`.
- Модуль `:app` (повністю новий код у пакеті `ua.movieportal.tv`).
- Логіка визначення кодеків з `util/profile/codec/*` (перенесено в `ua.movieportal.tv.profile.codec`) і спрощений
  `deviceProfile.kt`, константи `Codec`.

## 3. Що видаляється

- Модулі `:design`, `:preference`, `:playback:core`, `:playback:jellyfin`, `:playback:media3:exoplayer`,
  `:playback:media3:session` (разом з `AndroidMediaService`, що експортувався назовні).
- Увесь старий код `app/src/main/java/org/jellyfin/androidtv/**`: бібліотеки, головна, деталі, пошук, Live TV,
  музика, фото, заставка (DreamService), трейлери, теми, «Наступна серія», канали Leanback/Watch Next
  (`MediaContentProvider`, `ImageProvider`, `tvprovider`), голосовий пошук, вхід паролем, вибір сервера/користувача,
  усі екрани налаштувань, ACRA, aboutlibraries, Markwon, Coil, Koin, WorkManager, Compose, Leanback, Navigation3.
- Ресурси: усі старі layout, drawable, anim, 70+ локалізацій `values-*`, `xml/searchable.xml`, логотипи Jellyfin.
- Тести старого коду (`app/src/test/kotlin/**`), `fastlane/` (опис магазину Jellyfin), `.github/` (CI/шаблони
  Jellyfin), `CODEOWNERS`, `renovate.json` (пресети Jellyfin).
- Залежності з `libs.versions.toml`, що стали непотрібні.

## 4. Що пишеться заново (`ua.movieportal.tv`)

| Файл | Призначення |
|---|---|
| `MoviePortalApp` | `Application`: Timber, канали сповіщень, `AppGraph`, старт служби, якщо є токен. |
| `data/AppSettings` | SharedPreferences: адреса, назва пристрою, DeviceId, токен, userId, ім'я акаунта. |
| `jellyfin/JellyfinClient` | Створення `ApiClient` SDK з `ClientInfo("Movie Portal TV", версія)` і `DeviceInfo(id, назва)`; заголовок `Authorization: MediaBrowser …`. |
| `ui/MainActivity` | Один екран з трьома станами: налаштування → код Quick Connect → очікування. |
| `ui/SetupController`, `ui/QuickConnectController`, `ui/StatusController` | Логіка кожного стану. |
| `service/ReceiverService` | Foreground Service (`START_STICKY`, тип `specialUse`), сокет, capabilities, обробка команд. |
| `service/SessionSocket` | OkHttp WebSocket: backoff 1→60 с, `KeepAlive`/`ForceKeepAlive`, 401 → відкликаний токен. |
| `service/NetworkMonitor` | `ConnectivityManager.NetworkCallback` → миттєве перепідключення. |
| `service/BootReceiver` | `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`. |
| `service/PlayerLauncher` | Запуск плеєра з фону (див. нижче). |
| `remote/RemoteMessage` | Розбір JSON-повідомлень сервера в типізовані команди. |
| `remote/RemoteHub` | Шина між службою і плеєром (`StateFlow`/`SharedFlow`). |
| `player/PlayerActivity` | Media3 ExoPlayer + власна мінімальна панель, керування з пульта. |
| `player/StreamResolver` | `POST /Items/{id}/PlaybackInfo`, вибір DirectPlay/Transcode, URL, зовнішні субтитри. |
| `player/PlaybackReporter` | `Playing` / `Progress` (10 с + події) / `Stopped`. |
| `profile/*` | DeviceProfile з реальних можливостей кодеків. |

### Запуск плеєра з фону (Android 10+)

1. Якщо є видиме вікно нашого застосунку (екран очікування), просто `startActivity`.
2. Якщо дозвіл `SYSTEM_ALERT_WINDOW` надано, система знімає обмеження (`BAL_ALLOW_SAW_PERMISSION`, перевірено на
   емуляторі Android TV 14). Вимога Android 15 про видиме overlay-вікно стосується лише старту Foreground Service з фону,
   не Activity, тому overlay-вікно не створюється.
3. На Android < 10 обмеження немає, тож просто `startActivity`.
4. Запасний варіант: сповіщення високого пріоритету («Натисніть, щоб відкрити плеєр»). Публікується, якщо через
   3 с плеєр не відкрився, бо система блокує фоновий старт мовчки. Full-screen intent прибрано, бо Google Play дозволяє
   `USE_FULL_SCREEN_INTENT` лише для дзвінків і будильників, а на Android TV він майже не працює.

Екран налаштувань пояснює потребу в дозволі «Поверх інших застосунків» і відкриває
`ACTION_MANAGE_OVERLAY_PERMISSION`. На багатьох збірках Android TV цього екрана немає, тоді показуємо команду
`adb shell appops set ua.movieportal.tv SYSTEM_ALERT_WINDOW allow`.

### Технічні рішення

- **UI на звичайних View** (без Compose/AppCompat): `EditText` + системна екранна клавіатура найнадійніше працюють
  з D-pad, а залежностей менше.
- **Власний WebSocket**, а не `api.webSocket` SDK: потрібні точний backoff, реакція на зміну мережі й
  розпізнавання 401. JSON розбирається через `kotlinx.serialization.json.JsonObject`, тож зміни схеми SDK не ламають
  розбір.
- **Heartbeat**: кожні 4 хв повторно шлемо `POST /Sessions/Capabilities/Full`. Це оновлює `LastActivityDate` сесії
  (портал фільтрує `activeWithinSeconds=600`) і водночас виявляє відкликаний токен.
- **DeviceId** = `UUID.nameUUIDFromBytes("ua.movieportal.tv:" + ANDROID_ID)`, генерується один раз і зберігається.
  `ANDROID_ID` на Android 8+ стабільний для пари «ключ підпису + користувач», тому після перевстановлення з тим самим
  підписом id той самий. Якщо `ANDROID_ID` недоступний, береться випадковий UUID.
- **Тип FGS `specialUse`**: з Android 15 служби `dataSync`/`mediaPlayback` не можна стартувати з `BOOT_COMPLETED`.
- **`LOCKED_BOOT_COMPLETED` не використовується**: токен лежить у credential-encrypted сховищі. На ТВ без блокування
  `BOOT_COMPLETED` приходить одразу після нього.
- **ASS/SSA**: libass (`ass-media`) видалено, рендерить вбудований парсер Media3 (спрощено: текст, базові стилі,
  без анімацій і караоке).
- **FFmpeg-декодер аудіо** (`org.jellyfin.media3:media3-ffmpeg-decoder`) залишено: AC3/EAC3/DTS/TrueHD
  декларуються в профілі, якщо є passthrough, апаратний декодер або підтримка у FFmpeg.
- **Мова інтерфейсу**: лише українська (`values/strings.xml`).
- **Виклики SDK тільки на `Dispatchers.IO`**: OkHttp-клієнт SDK читає тіло відповіді в контексті виклику. На Main
  це дає `NetworkOnMainThreadException` (знайдено під час тестування Quick Connect).
- **Вихід із плеєра**: Home або інший застосунок поверх плеєра зупиняє відтворення зі звітом `Stopped`, а не ставить
  його на паузу у фоні. Back, Stop і кінець відео повертають на екран очікування.
- **Відмова DirectPlay**: якщо ExoPlayer не зміг відтворити файл напряму, один раз автоматично запитується
  транскодування з поточної позиції.

## 5. Як я розумію вимоги порталу до звітів

Портал кожні 10 с читає `GET /Sessions` і бере з сесії свого ТВ-акаунта `NowPlayingItem.Id`,
`NowPlayingItem.RunTimeTicks`, `PlayState.PositionTicks`, `PlayState.IsPaused`. Сервер Jellyfin заповнює
`NowPlayingItem`/`PlayState` **лише** з наших звітів:

- `POST /Sessions/Playing` (PlaybackStartInfo) одразу після готовності потоку (перший `STATE_READY`), щоб
  `NowPlayingItem` з'явився якнайшвидше;
- `POST /Sessions/Playing/Progress` кожні 10 с, а також одразу після паузи/відновлення, перемотування, зміни
  аудіо/субтитрів, гучності;
- `POST /Sessions/Playing/Stopped` при стопі (Back, команда Stop, кінець відео, помилка, заміна відео новим
  `PlayNow`), щоб сервер записав позицію/«переглянуто» і прибрав `NowPlayingItem`.

У кожному звіті: `ItemId`, `MediaSourceId`, `PositionTicks`, `IsPaused`, `PlaySessionId`, `PlayMethod`,
`AudioStreamIndex`, `SubtitleStreamIndex` (−1, якщо вимкнено), `CanSeek`, `IsMuted`, `VolumeLevel`.
При перезапуску потоку (зміна аудіо під час транскодування, вмикання/вимикання «вшитих» субтитрів) `Stopped`
**не** шлеться. Стара сесія транскодування зупиняється через `DELETE /Videos/ActiveEncodings`, а `Progress` іде вже з
новим `PlaySessionId`, тож портал не бачить хибного «стопу».

Модель `PlaybackStopInfo` у Jellyfin не має полів `IsPaused`, `PlayMethod`, `AudioStreamIndex` і
`SubtitleStreamIndex`, тому стоп-звіт містить `ItemId`, `MediaSourceId`, `PositionTicks`, `PlaySessionId` і `Failed`.

Сесія має належати акаунту пристрою: усі запити (REST і WebSocket) ідуть з одним `DeviceId`, `Client` і токеном,
отриманим через Quick Connect.

## 6. Ризики

- Запуск з фону без `SYSTEM_ALERT_WINDOW` на Android 10+ ненадійний (лише сповіщення).
- Агресивні «оптимізатори» виробників ТВ можуть вбивати службу. `START_STICKY` і автозапуск це пом'якшують, але не
  гарантують.
- Відповідність індексів Jellyfin (`MediaStream.Index`) і доріжок ExoPlayer при DirectPlay рахується за порядком
  у контейнері. Для екзотичних файлів можливий зсув.
- Немає доступного сервера Jellyfin у середовищі розробки: наскрізний сценарій перевіряється частково.

## 7. Етапи

1. Каркас: налаштування → Quick Connect → очікування (новий пакет, старий код `app` видалено).
2. Фонова служба + WebSocket + capabilities + автозапуск.
3. Плеєр + обробка команд + звіти.
4. Видалення модулів і залежностей.
5. Ідентифікатори (`ua.movieportal.tv`), назва, іконка, README.

## 8. Безпека (аудит перед публікацією)

| Тема | Рішення |
|---|---|
| Зберігання токена | AES-256-GCM, ключ у Android Keystore (`TokenCipher`). Старий незашифрований токен мігрується при першому читанні. Якщо Keystore на прошивці ТВ зламаний, токен лишається в приватних SharedPreferences (`plain:`), щоб приймач працював. |
| Резервні копії | `allowBackup=false` і `dataExtractionRules`, які виключають усе з хмарного бекапу та перенесення між пристроями. `adb backup` вимкнено. |
| Логи | `RedactingTree` маскує `api_key`/`ApiKey`/`access_token`/`secret`, `Token="…"` і `X-Emby-Token` у повідомленнях і стек-трейсах. Release пише лише WARN/ERROR, виклики `Timber.v/d/i` вирізає R8, внутрішні логи Media3 обмежено рівнем ERROR. OkHttp logging interceptor не використовується. |
| Мережа | Cleartext дозволено: Jellyfin у домашній мережі часто працює по `http://`, а заборона зламала б основний сценарій. Для будь-якої адреси `http://` (з 1.1.1, і в автопошуку, і в ручному вводі) показується однакове попередження, що код прив'язки й токен ідуть без шифрування; продовжити можна лише явним підтвердженням. Release довіряє лише системним CA, користувацькі CA приймаються тільки в debug (`debug-overrides`). Перевірку імені хоста ніде не вимкнено. |
| Токен і чужі хости | Заголовок `Authorization` додає `ServerAuthInterceptor` лише для origin спареного сервера. Зовнішні субтитри (`IsExternalUrl`) і віддалені джерела отримуються без токена. При редиректі на інший хост OkHttp сам прибирає `Authorization`. `api_key` у URL є лише у WebSocket до свого сервера, як вимагає протокол Jellyfin. Токен може потрапити в access-лог reverse proxy; це сервер користувача. |
| Компоненти | `MainActivity` експортована (лаунчер), але не читає даних з intent. `PlayerActivity` і `ReceiverService` не експортовані. Плеєр бере URL і токен лише з внутрішнього `RemoteHub`, а не з extras. `BootReceiver` експортований, бо інакше не отримає BOOT_COMPLETED. Він перевіряє action і лише запускає власну службу, якщо пристрій уже спарений. Усі `PendingIntent` мають `FLAG_IMMUTABLE` і явний компонент. |
| Повідомлення сервера | Невалідний JSON не ламає розбір. `ItemIds` і `MediaSourceId` мають бути GUID. Тіки обмежено діапазоном 0…100 діб, індекси доріжок діапазоном −1…9999. `DisplayMessage` показується як звичайний текст у Toast і обрізається до 100/500 символів. |
| `SYSTEM_ALERT_WINDOW` | Використовується лише як виняток для запуску власного плеєра. Застосунок нічого не малює поверх інших. |
| R8 | Release мінімізується (`isMinifyEnabled`, `isShrinkResources`). Власних keep-правил немає, лише `-assumenosideeffects` для логування. |
| Залежності | OkHttp 5.3.2, Media3 1.11.1, Jellyfin SDK 1.8.12, kotlinx-serialization 1.11.0, coroutines 1.11.0. Відомих мені CVE для цих версій немає. Старий CVE-2021-0341 (OkHttp hostname verifier) виправлено ще в 4.9.2. Окремий ризик — нативний FFmpeg-декодер аудіо (`org.jellyfin.media3:media3-ffmpeg-decoder` 1.9.0+1): він розбирає медіадані, хоч і лише з сервера користувача, тому його варто оновлювати разом з Media3. Бази CVE я не перевіряв, бо для цього потрібні мережеві інструменти. Актуальність варто перевірити. |

## 9. Перший запуск без введення тексту (1.1.0)

- **Пошук сервера:** власна реалізація Jellyfin discovery (`discovery/ServerDiscovery`): UDP «who is JellyfinServer?» на
  порт 7359, на 255.255.255.255 і broadcast-адреси всіх інтерфейсів. Під час пошуку тримається
  `WifiManager.MulticastLock` (`CHANGE_WIFI_MULTICAST_STATE`, з `android.hardware.wifi` `required=false`, щоб не
  відсіяти ТВ з Ethernet). У debug-збірці запит додатково йде unicast на 10.0.2.2 для UDP-ретранслятора емулятора
  (`tools/emulator-discovery/3proxy.cfg`).
- **Адреси (з 1.1.1):** використовується **лише** повідомлена сервером адреса (`Address`), нормалізована так само,
  як ручний ввід. Запасних адрес, зокрема IP відправника відповіді, немає: це рішення користувача. Якщо адреса
  `http://`, додатково перевіряються `https://<той самий хост>` (443) і `https://<хост>:8920` з тим самим шляхом.
- **Перевірка кандидатів:** усі кандидати паралельно перевіряє `GET /System/Info/Public` зі звичайною перевіркою TLS (без
  trust-all); `Id` має збігатися. Пріоритет: будь-який робочий https, http — лише якщо https не працює. Якщо нічого не
  працює, сервер показується «недоступним» з підказкою про «Опубліковані URI сервера». Скасування пошуку не
  перетворюється на «недоступний».
- **Автовибір:** рівно один доступний сервер з https вибирається автоматично. Якщо працює лише http, сервер не
  вибирається мовчки: з'являється попередження з кнопками «Все одно підключитися» і «Ввести адресу вручну», а пошук
  на цей час зупиняється. Підтвердження запам'ятовується за `Id` сервера і стирається при «Змінити сервер» /
  «Відключити». У списку (кілька серверів або повернення через «Змінити сервер») http-сервери позначено
  «незахищений», і при виборі такого сервера з'являється те саме попередження. Жодного сервера — повторний пошук
  кожні 5 с і кнопка «Ввести адресу вручну». Ручний ввід попереджає про будь-який `http://` тим самим текстом.
- **Назва пристрою:** при першому запуску один раз береться з системи (`Settings.Global "device_name"`, потім
  `Settings.Secure "bluetooth_name"`, потім `Build.MODEL`) і зберігається; далі читається лише збережене значення.
  Назва доступна як `StateFlow`; служба сама перепідключається, коли змінюються облікові дані або назва (рівно одне
  підключення).
- **QR:** `com.google.zxing:core` (Apache-2.0), лише кодер, рендер у Bitmap з цілим масштабом без згладжування, рівень
  корекції M, 4 модулі тихої зони, 420 dp на білому тлі, `scaleType=centerInside` (без повторного масштабування). Payload:
  `{"t":"mptv","v":1,"code":…,"did":…,"sid":…,"name":…}`, генерується заново для кожного нового коду Quick Connect і
  коли пізніше надходить `sid`. Код показується одразу, не чекаючи `/System/Info/Public`, бо той запит іде паралельно з
  тайм-аутом. Вимоги до порталу: `PORTAL_PAIRING.md`.
- **Тестування:** на емуляторі broadcast не виходить за NAT, тож автопошук там не перевірити. Перевірено гілку
  «нічого не знайдено → ввести вручну», екран QR і декодування QR зі скриншоту. Розбір відповідей, вибір кандидатів і
  QR покривають unit-тести.
