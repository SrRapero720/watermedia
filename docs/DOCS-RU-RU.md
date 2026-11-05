# 🦆 ВВЕДЕНИЕ
WATERMeDIA представляет собой библиотеку с мультимедийным API для воспроизведения, декодирования, кодирования и
обработки видео, аудио и изображений. Пользоваться ей довольно просто, если вы работали с библиотеками вроде ExoPlayer
(на Android) или VLCJ, но здесь всё гораздо более сфокусировано, автоматизировано и готово к работе, поэтому вам никогда
не придётся разбираться с очень продвинутыми и техническими деталями вроде количества буферов, плоскостей на кадр,
порядка байтов или шага данных (stride).

Она разрешает медиассылки (YouTube, Twitch, прямые файлы и многое другое), декодирует их и передаёт
кадры вашему графическому движку (OpenGL, Vulkan, AWT, JavaFX), а звук вашему аудиодвижку
(OpenAL, Java Sound). Изображения и анимации проходят через наши собственные декодеры на чистой Java; видео и
аудио проходят через _FFmpeg_, который поставляется в сопутствующем моде _WATERMeDIA: Binaries_. Нужна Java
17 или новее.

Каждый пример в этом руководстве является фрагментом интеграции: переменные вроде `renderThread`,
`renderExecutor` или `mrl` берутся из вашего собственного кода.

# 🪁 СХЕМА ВЕРСИЙ
> [!IMPORTANT]
> Эта схема не распространяется на экспериментальные API и внутренний код

Многие разработчики используют базовую схему, и большинство из них просто надолго застревает на 0.x.y, выдавая
переписывания за патчи; здесь же правило прозрачно как вода. Эта схема помогает понять, что означает повышение
каждого числа

**СХЕМА ПОВЫШЕНИЯ ВЕРСИИ**
``X.Y.Z-<channel>.W``
- ``X``: Полное переписывание: всё, что вы знаете о библиотеке, выброшено и пересобрано с нуля
- ``Y``: Ломающие изменения: рефакторинг, переименования и удаления API, которые могут сломать или точно сломают вашу
  реализацию
- ``Z``: Хотфиксы, улучшения, новые API и аннотации устаревания, ничего ломающего.
- ``<channel>``: Каналы ALPHA или BETA, релиз не использует этот суффикс версии
  - Примечание: в некоторых худших случаях этот суффикс могут намеренно опустить, модлоадеры обрабатывают его
    некорректно и часто ломают диапазоны версий.
- ``W``: Номер выпуска ALPHA или BETA, опускается на релизном канале

Это ГАРАНТИЯ того, что при повышении ``Z`` НЕ БУДЕТ НИКАКИХ ЛОМАЮЩИХ ИЗМЕНЕНИЙ, так что вы можете смело требовать
watermedia в диапазоне ``[3.0.1,3.1)``

# 🗺 СОДЕРЖАНИЕ

Выберите путь, который подходит под то, что вы создаёте; в каждом перечислены нужные ему разделы.

## MINECRAFT: МОДДЕРЫ, ДОБАВЛЯЮЩИЕ ВИДЕО

1. [Подключение](#0-подключение): добавьте зависимость и соблюдайте диапазон версий.
2. [Программные ограничения](#11-программные-ограничения): поддерживаемые версии Minecraft и загрузчиков.
3. [Работа со ссылками](#2-работа-со-ссылками): превратите ссылку в медиаисточники.
4. [Создание плеера и управление им](#31-создание-плеера-и-управление-им).
5. [Выбор движков](#33-выбор-движков): OpenGL, Vulkan и OpenAL на собственных контекстах игры.
6. [Пространственный звук](#34-пространственный-звук): позиционные экраны, стереопары и динамики.
7. [Синхронное воспроизведение](#35-синхронное-воспроизведение): все плееры показывают один и тот же момент.
8. [Загрузка и скачивание файлов](#4-загрузка-и-скачивание-файлов): делитесь файлами через файловый сервер.

## ДЕСКТОПНЫЕ ПРИЛОЖЕНИЯ

1. [Подключение](#0-подключение): зависимости Gradle, библиотеки времени выполнения и [конфигурация](#02-конфигурация).
2. [Запуск](#1-запуск): запустите WATERMeDIA один раз и покажите экран загрузки.
3. [Работа со ссылками](#2-работа-со-ссылками) и [поиск](#23-поиск).
4. [Создание плеера и управление им](#31-создание-плеера-и-управление-им) и [аудиодорожки](#32-аудиодорожки-и-языки).
5. [Выбор движков](#33-выбор-движков): AWT, JavaFX, OpenGL или Vulkan, плюс OpenAL или Java Sound.
6. [Диагностика и примеры](#13-диагностика-и-примеры).

## ПРОГРАММНЫЕ ИНСТРУМЕНТЫ

Утилиты командной строки, боты, конвертеры и сервисы, которые работают без окна.

1. [Подключение](#0-подключение) и [запуск](#1-запуск): что нужно запускать, а что нет.
2. [Платформы](#22-платформы) и [поиск](#23-поиск): разрешайте ссылки и ищите через `PlatformAPI`, без всякого запуска.
3. [Скачивание](#42-скачивание): `NetRequest` с ограничением размера и проверкой скачанного.
4. [Декодеры](#51-декодеры) и [энкодеры](#52-энкодеры): изображения, анимации и видеофайлы.
5. [Headless-движок](#33-выбор-движков): получайте декодированные кадры без графического контекста.
6. [Синхронное воспроизведение](#35-синхронное-воспроизведение): `ServerMediaPlayer` работает и на серверах.

## ИИ-АГЕНТЫ

Прочитайте разделы [запуск](#1-запуск), [публичные и внутренние пакеты](#12-публичные-и-внутренние-пакеты),
[работа со ссылками](#2-работа-со-ссылками), [создание плеера и управление им](#31-создание-плеера-и-управление-им) и
[выбор движков](#33-выбор-движков), прежде чем писать код интеграции. Эти правила обязательны:

- Используйте только публичные пакеты (`org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player`,
  `api.util`, без их подпакетов). Плееры и движки создаются только через `PlayerAPI`; классы движков запечатаны
  (sealed).
- Внутри Minecraft никогда не вызывайте `WaterMedia.start`: это делает загрузчик. В остальных случаях вызывайте его один
  раз на процесс и никогда из колбэка или рабочего потока WATERMeDIA. Остановки нет.
- Проверяйте `WaterMedia.loaded()` и нужную вам возможность (например, `CodecsAPI.ffmpegLoaded()`), а не считайте их
  доступными заранее.
- Разрешение асинхронно: опрашивайте `mrl.status()` из тика и никогда не вызывайте `mrl.await(...)` в игровом потоке или
  потоке рендеринга.
- `PlayerAPI.player(...)` может вернуть null. Освобождайте плеер, когда `start()` бросает исключение или хост убирает
  поверхность.
- Передавайте движки как поставщики (supplier), никогда не делите один движок между плеерами и оставьте владение ими
  плееру.
- Читайте `player.texture()` заново при каждой отрисовке, в потоке рендеринга; 0 означает, что рисовать нечего.
- Никогда не вызывайте `release()` из слушателя статуса или потока воспроизведения и освобождайте каждый плеер до
  уничтожения контекста OpenGL, Vulkan или OpenAL, который он использует.
- Исполнитель (executor) OpenGL работает в потоке GL, и его задачи нужно выполнять каждый кадр, а также ещё раз после
  `release()`. OpenAL требует, чтобы его контекст был текущим в каждом потоке, который управляет звуком. На Vulkan
  держите `queueLock()` вокруг своих собственных submit и present и никогда не вызывайте `vkDeviceWaitIdle`, пока
  существуют плееры.
- Слушатели статуса расходуются при переходе в `STOPPED`, `ENDED` или `ERROR`; чтобы продолжать слушать, зарегистрируйте
  слушатель заново из колбэка.
- Булевы методы управления возвращают `true`, когда вызов сработал, и `false`, когда он был отклонён; проверяйте
  результат, а не предполагайте.
- Время указывается в миллисекундах (временные метки энкодера в микросекундах), громкость от 0 до 100, а скорость от
  чуть больше 0 до 4.
- `ImageReader.next()` переиспользует свой буфер: скопируйте его до следующего вызова. Вызовы decode, reader, sniff и
  encoder из `CodecsAPI` экспериментальные: не используйте их в модах; а его проверки статуса безопасны.
- На выделенных серверах доступны `ServerMediaPlayer`, `PlatformAPI`, загрузки через `NetworkAPI`, файловый сервер и
  кодеки изображений на чистой Java; плееры и движки там бросают исключение.

# 0. ПОДКЛЮЧЕНИЕ

В среде Minecraft отсюда ничего не требуется, кроме соблюдения диапазона версий jar: добавьте WATERMeDIA как зависимость
времени компиляции, объявите её обязательной зависимостью вашего мода в пределах диапазонов из раздела
[программные ограничения](#11-программные-ограничения), и загрузчик предоставит её во время выполнения. Клиентам также
нужен установленный _WATERMeDIA: Binaries_ (см. [Binaries и FFmpeg](#01-binaries-и-ffmpeg)).

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compileOnly "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.0
}
```

В собственных Java-приложениях или Java-играх окружение должно предоставить:

```gradle
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.SrRapero720:watermedia:$watermedia_version"
    implementation "com.github.SrRapero720:watermedia-binaries:$binaries_version" // FFMPEG, YT-DLP AND BOTGUARD, OPTIONAL
    implementation "com.github.SrRapero720:waterconfig:1.0.0.1" // MIN WATERCONFIG VERSION
    implementation "com.google.code.gson:gson:2.10" // MIN GSON VERSION
    implementation "org.apache.logging.log4j:log4j-api:2.26.1" // MIN LOG4J VERSION, PLUS THE BACKEND YOUR APP LOGS WITH
    implementation "org.joml:joml:1.10.8" // MIN JOML VERSION
    // LWJGL 3.3.6 MODULES, ONLY FOR THE ENGINES YOU USE (SEE THE TABLE BELOW)
}
```

| Движок | Дополнительные библиотеки времени выполнения |
| --- | --- |
| `GLEngine` | _LWJGL_ `lwjgl` и `lwjgl-opengl`, вместе с их нативными библиотеками |
| `VKEngine` | _LWJGL_ `lwjgl`, `lwjgl-vulkan` и `lwjgl-shaderc` (шейдеры компилируются во время выполнения), вместе с их нативными библиотеками (у `lwjgl-vulkan` нативные библиотеки есть только для macOS; Windows и Linux используют системный Vulkan) |
| `ALEngine` | _LWJGL_ `lwjgl` и `lwjgl-openal`, вместе с их нативными библиотеками |
| `JFXEngine` | Среда выполнения JavaFX 13+ |
| `AWTEngine`, `JSEngine`, `HeadlessGFXEngine` | Ничего дополнительного: их покрывает JDK |

- На Forge, NeoForge и Fabric WATERMeDIA включает _WATERCoNFIG_ как вложенный JAR, а остальные библиотеки уже
  поставляются с игрой.
- Автономный лаунчер извлекает этот JAR во временный файл, добавляет его в classpath перезапущенной JVM и по мере
  необходимости скачивает остальные библиотеки времени выполнения из своего списка; кэшированная или скачанная
  библиотека попадает в classpath, только если совпадает с SHA-256, зафиксированным при сборке.
- Приложение, которое встраивает библиотеку, использует собственный classpath. Зависимости Gradle для разработки не
  доказывают, что установка Minecraft поставляет остальные библиотеки.
- JAR Binaries встраивает и перемещает (relocate) свою зависимость _XZ_, а JAR WATERMeDIA содержит свои биндинги
  _JavaCPP_ и _FFmpeg_.

Нативные библиотеки _FFmpeg_ покрывают эти платформы; изображения декодируются везде, где работает Java.

| Архитектура | Windows | Linux | macOS | Android |
| --- | --- | --- | --- | --- |
| x86_64 | ✅ | ✅ | ✅ | ⛔ |
| aarch64 | ⛔ | ✅ | ✅ | ⛔ |

> [!WARNING]
> Нативных библиотек _FFmpeg_ для Windows на ARM64 и для Android нет: изображения там по-прежнему декодируются, видео и
> аудио не воспроизводятся, а установленный Binaries проваливает запуск, если не включён `codecs.ffmpeg.disable`.

## 0.1 BINARIES И FFMPEG

> [!IMPORTANT]
> Аппаратному декодированию видео нужны GPU и драйвер, которые умеют декодировать этот кодек: NVIDIA (CUDA), Intel
> (QSV), AMD (AMF), Direct3D 11 или 12 на Windows, VideoToolbox на macOS, VAAPI, VDPAU или DRM на Linux, либо
> [Vulkan](#декодирование-через-vulkan). Всё остальное, как и GPU, который не справляется, декодирует программно, а с
> `codecs.ffmpeg.hardwareAccel = false` декодирование всегда программное.

> [!NOTE]
> Настраивается: `codecs.ffmpeg.disable`, `customPath`, `hardwareAccel`, `analyzeDuration`, `probeSize`,
> `trackPacketReads`, `cache` и `cacheMaxResponse`. Смотрите их комментарии в `config/watermedia.toml`.

_WATERMeDIA: Binaries_ требует WATERMeDIA и загружается вместе с ней, подготавливая нативные библиотеки сразу после
конфигурации и до модулей сети, кодеков и плеера.

- Клиентам Minecraft он обязателен; выделенные серверы могут обойтись без него, поскольку там он ничего не
  подготавливает.
- Приложения, встраивающие Java API, тоже могут обойтись без него и сохранить декодирование изображений и резолверы
  платформ без нативного кода; они теряют декодирование видео и платформы, работающие через _yt-dlp_.
- Отключение _FFmpeg_ (`codecs.ffmpeg.disable`) пропускает извлечение.
- Пути к нативным библиотекам остаются опубликованными всё время жизни процесса, а отдельного API для инициализации или
  завершения Binaries нет.
- _yt-dlp_ и _BotGuard_ скачиваются при первом использовании и проверяются перед запуском. Системные свойства
  `watermedia.ytdlp.binary` и `watermedia.botguard.binary` позволяют указать вместо них ваши собственные исполняемые
  файлы.

Собственные сборки _FFmpeg_ указываются в `codecs.ffmpeg.customPath`: это папка с нативными библиотеками, а не программа
`ffmpeg.exe`. Они могут использовать GPL, LGPL или любую другую заявленную лицензию и версию: загрузчик проверяет
необходимые нативные возможности, а не белый список версий или лицензий, и проверяет целостность только у встроенных
нативных библиотек. При этом им всё равно нужны JNI-биндинги, совместимые с Java API, который использует WATERMeDIA, а
также поддержка TLS, как у встроенной сборки (см. [Доверие HTTPS и учётные данные](#25-доверие-https-и-учётные-данные)).

## 0.2 КОНФИГУРАЦИЯ

> [!NOTE]
> Каждое поле настраивается и описывает себя само: его комментарий в `config/watermedia.toml` и есть документация о том,
> как оно работает, а значения выбираете вы. Каждый раздел этого руководства перечисляет поля, которые на него влияют.

WATERMeDIA записывает свои настройки в `config/watermedia.toml` в рабочей директории, с комментарием над каждым полем.
Значения по умолчанию работают сразу, поля, читаемые при запуске, применяются при следующем запуске, а если какой-то
бэкенд кажется недоступным, первым делом проверьте этот файл.

- `codecs.*`: _FFmpeg_, аппаратное декодирование и декодеры изображений.
- `player.*`: качество по умолчанию и кэши.
- `platforms.*`: контент для взрослых, поиск и аккаунты платформ.
- `network.*`: файловый сервер и веб-запросы.

# 1. ЗАПУСК

На популярных модлоадерах с нативной поддержкой, таких как Forge, NeoForge и Fabric, загрузчик запускает WATERMeDIA за
вас: этот раздел можно пропустить.

**ПРИМЕР:**
```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded(); // true ONCE EVERY MODULE FINISHED; CHECK THE CAPABILITY YOU NEED TOO
String module = WaterMedia.loading(); // MODULE CURRENTLY LOADING
int step = WaterMedia.step(); // CURRENT LOADING STEP
int steps = WaterMedia.steps(); // TOTAL LOADING STEPS
```

Она загружает в таком порядке:
- Конфигурацию (`watermedia.toml`)
- Binaries (только если его JAR присутствует)
- `NetworkAPI`
- `CodecsAPI`
- `PlayerAPI`

Поэтому `steps()` равен 5 с Binaries и 4 без него, а `loading()`, `step()` и `steps()` можно безопасно опрашивать из
любого потока для экрана загрузки.

- `start` загружает каждый модуль один раз на всё время жизни процесса. Ничего никогда не выгружается: остановки нет,
  повторный `start` бросает исключение, а неудавшийся запуск нельзя повторить в том же процессе.
- Временная директория null превращается в `<java.io.tmpdir>/watermedia`, а рабочая директория null в текущую. Последний
  аргумент выбирает клиентское окружение; модули только для клиента (Binaries, кодеки, плеер) пропускают свой запуск на
  выделенном сервере.
- Необязательные части (файловый сервер, _FFmpeg_) записывают свою ошибку в лог и остаются выключенными, пока загрузка
  завершается, поэтому проверяйте нужную вам возможность, например `CodecsAPI.ffmpegLoaded()`.
- Любая другая ошибка (конфигурация, установленный модуль Binaries) заставляет `start` бросить `IllegalStateException` с
  этой ошибкой в качестве причины, и `loaded()` остаётся false.
- `start` отказывается работать из рабочего потока WATERMeDIA, поэтому никогда не вызывайте его из её колбэков.

Каждый API сам знает, загрузился ли его модуль:

- `PlayerAPI.mrl` и плееры из `PlayerAPI.player` бросают `IllegalStateException`, пока не запустится модуль плеера, а на
  выделенных серверах бросают всегда.
- `NetworkAPI.upload` бросает исключение, пока не запустится сетевой модуль. Он также сразу отклоняет всё, что не
  является существующим файлом; а недоступный или неправильно настроенный удалённый сервер вместо этого переводит в
  ошибку возвращённый статус.
- `PlatformAPI` не требует запуска: его реестр статический, и `register`, `fetch` и `search` работают в любой момент.
  Обработчики, которым нужен Binaries (YouTube, _yt-dlp_), падают со своим `PlatformException`, пока он не загрузится, а
  на выделенных серверах всегда; без JAR Binaries их нет вовсе, поэтому `fetch` возвращает null для их ссылок.
- Декодирование изображений на чистой Java через `CodecsAPI`, `ServerMediaPlayer`, `MathUtil` и `MathEases` вообще не
  требуют запуска.

Плеер владеет движками, созданными поставщиками, которые вы передаёте в `PlayerAPI.player`. Создавайте движки лениво
через поставщики, чтобы недоступный источник не мог привести к утечке заранее выделенных нативных ресурсов. Ваше
приложение владеет своими контекстами OpenGL, Vulkan и OpenAL: освобождайте каждый плеер на правильном контексте хоста,
прежде чем уничтожать эти контексты, и создавайте новые движки после перезагрузки устройства или контекста. Нативные
библиотеки никогда не выгружаются и не могут сменить версию внутри работающей JVM.

## 1.1 ПРОГРАММНЫЕ ОГРАНИЧЕНИЯ

### Заблокированные лаунчеры

Загрузочные модули (bootstrap) для Minecraft отказываются запускаться, если игру запустил _TLauncher_. Они распознают
его по внедрённому моду скинов, классам, файлам в classpath, опциям JVM, потокам и родительским процессам и завершаются
с ошибкой, называя найденные улики; остатки старой установки в директории игры или профиле пользователя лишь записывают
предупреждение в лог. Встраивающие приложения эту проверку пропускают.

### Совместимые версии

Forge, NeoForge и Fabric загружают WATERMeDIA на клиентах и выделенных серверах и отклоняют версии Minecraft, которых
нет в этом списке:

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 или новее (40.x) | ⛔ | 0.14 или новее (0.x) |
| 1.19.2 | 43.5.0 или новее (43.x) | ⛔ | 0.14 или новее (0.x) |
| 1.20.1 | 47.4.2 или новее (47.x) | ⛔ | 0.14 или новее (0.x) |
| 1.21.1 | 52.1.1 или новее (52.x) | 21.1.0 или новее (21.1.x) | 0.14 или новее (0.x) |
| 1.21.11 | 61.0.5 или новее (61.x) | 21.11.0 или новее (21.11.x) | 0.14 или новее (0.x) |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 или новее (0.x) |
| 26.2 | 65.x | 26.2.0.x | 0.14 или новее (0.x) |

- В Minecraft принимается Java от 17 до 25.
- WATERMeDIA и Binaries принимают друг друга от 3.0.0 до 3.1.0 (не включая); старые сборки 3.0.0 BETA отклоняются.
- Fabric не умеет ограничивать зависимость только клиентами, поэтому его bootstrap требует Binaries на клиентах, а его
  манифест отклоняет несовместимые версии Binaries.

## 1.2 ПУБЛИЧНЫЕ И ВНУТРЕННИЕ ПАКЕТЫ

Публичный API составляют пакеты `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` и
`api.util`, без их подпакетов. Всё остальное внутреннее: ридеры кодеков, энкодеры и структуры чанков, очереди и кэши
плееров, встроенные платформы, bootstrap, `org.watermedia.tools` и встроенные биндинги _FFmpeg_. Binaries тоже
использует `org.watermedia.tools` для своих утилит хэш-сумм, файлов и JSON.

JAR не объявляет Java-модуль, поэтому ни один загрузчик не блокирует внутренние пакеты; и всё же считайте эту границу
контрактом и используйте публичные точки входа, такие как `PlayerAPI.player` и `PlatformAPI.fetch`, а не внутренние
классы за ними. Контрибьюторы могут запустить `gradle javadocInventory` из корня проекта, чтобы получить список
публичных методов без документации с именами файлов и номерами строк; задача только сообщает и проверяет только
публичные пакеты, перечисленные в `public_packages` файла `gradle.properties`.

> [!WARNING]
> `CodecsAPI` экспериментальный: `decode`, `reader`, `sniff` и `encoder`, а также выдаваемые ими `ImageReader`,
> `Encoder` и фильтры могут измениться или сломаться в любом обновлении без соблюдения схемы версий, поэтому пока не
> используйте их в своих модах. Медиаплееры уже используют кодеки за вас, а проверки статуса (`ffmpegLoaded()`,
> `ffmpegError()`, `vulkanDecode()`) вызывать безопасно.

## 1.3 ДИАГНОСТИКА И ПРИМЕРЫ

Логгеры проекта называются `watermedia` и `watermedia_binaries`; для диагностики выставьте этим именам уровень DEBUG.

- Сообщения логов проекта показывают медиассылки полностью, чтобы пути и ID оставались полезными для отладки. Они
  маскируют пароли в ссылках (`user:password@`), голые JWT-токены, значения `Authorization` и `Cookie`, аргументы сессии
  Minecraft и похожие на секреты пары `key=value` (отправка логов из приложения проходит через тот же фильтр).
  Трассировки исключений, содержащие секрет, сохраняют свой тип, стек, причины и подавленные ошибки в виде очищенного
  текста.
- Ничего не агрегируется по временным окнам: аномалия, которая может повторяться (короткий буфер кадра, отклонённая
  раскладка, падающий колбэк, отклонённая загрузка), записывается в лог при первом появлении в каждом движке, плеере,
  сервере или кэше и дальше молчит, а медленные итерации воспроизведения подсчитываются и выводятся один раз по
  завершении работы.
- _FFmpeg_ по-прежнему пишет свои нативные строки лога в поток ошибок процесса, в обход логгеров проекта и их
  маскирования; перенаправление через логгер проекта готово, но отключено, пока не проверены macOS и Linux.
- `-Dwatermedia.glchecks=true` включает проверки ошибок OpenGL на пути загрузки кадров в движке.

`src/test/java/org/watermedia/ApiGuideExample.java` компилирует использование фабрик для каждого графического и
аудиодвижка и обе стороны синхронного воспроизведения: часы и ведомый плеер. Его пример декодирования изображений,
который пропускает каждый кадр через `DownscaleFilter`, выполняется в `ApiGuideExampleTest`; поведению GUI,
аудиоустройств и Minecraft нужна соответствующая интеграция с хостом. Обычные наборы тестов воспроизведения и
пространственного звука отдельно покрывают реальное поведение _FFmpeg_ и OpenAL. Тестовые прогоны, запущенные с
`-Prequire_natives=true` (нативные задачи CI), падают, а не пропускаются, если нативных библиотек _FFmpeg_ нет.

# 2. РАБОТА СО ССЫЛКАМИ

> [!NOTE]
> Настраивается: `platforms.allowMatureContent`, `searchLimit`, `searchCleanupInterval`, `biliBiliCookie` и
> `player.mrlCleanupInterval`. Смотрите их комментарии в `config/watermedia.toml`.

MRL (Multimedia Resource Location) превращает любую ссылку в медиа, которое она содержит: один или несколько источников,
у каждого свои варианты качества, аудиодорожки и метаданные. Он разрешается в фоне и остаётся в кэше.

## 2.1 РАЗРЕШЕНИЕ ССЫЛОК

```java
MRL mrl = PlayerAPI.mrl("https://example.com/movie.mp4");    // A WEB LINK, A FILE UNDER THE WORKING DIRECTORY OR A water:// LINK
MRL[] queue = PlayerAPI.preload(firstUri, secondUri);        // START RESOLVING SEVERAL AT ONCE

// FROM A GAME TICK OR A UI TIMER
switch (mrl.status()) {
    case FETCHING -> { }                                     // STILL RESOLVING
    case LOADED -> play(mrl);                                // READY TO PLAY
    case BLOCKED -> showMatureWarning();                     // MATURE CONTENT WHILE IT'S NOT ALLOWED
    case ERROR -> showError(mrl.exception());                // WHY IT FAILED; A MALFORMED LINK STARTS HERE
    case EXPIRED, FORGOTTEN -> mrl = mrl.reload();           // STALE OR EVICTED: KEEP THE HANDLE reload() RETURNS
}

mrl.subscribe(done -> refreshUi());                          // OR GET CALLED ONCE WHEN IT FINISHES OR FAILS
boolean finished = mrl.await(5_000);                         // OR WAIT, BUT NEVER ON THE GAME OR RENDER THREAD
```

```java
// ONE LINK CAN HOLD SEVERAL SOURCES, SUCH AS A GALLERY WITH AN IMAGE AT 0 AND A VIDEO AT 1
MRL.Source first = mrl.source(0);                            // null FOR A WRONG INDEX
MRL.Source video = mrl.sourceByType(MediaType.VIDEO);        // THE FIRST VIDEO, OR null
List<MRL.Source> all = mrl.sources();

MediaType type = video.type();                               // IMAGE, VIDEO, AUDIO...
Metadata info = video.metadata();                            // TITLE, DESCRIPTION, AUTHOR, DURATION...
URI thumbnail = video.thumbnail();
URI link = video.uri(MediaQuality.HIGH);                     // THE CLOSEST QUALITY AVAILABLE
List<Track> dubs = video.audioTracks();                      // SEPARATE AUDIO TRACKS, THE ORIGINAL FIRST

MediaPlayer player = PlayerAPI.player(mrl, 1, gfx, sfx);     // PLAY THE SOURCE AT INDEX 1 (SEE PLAYING MEDIA)
```

Прямая ссылка завершается ошибкой, если сервер отвечает статусом ошибки, веб-страницей или скриптом; плейлисты HLS
открываются как одно видео, а плейлисты IPTV как один источник на канал.

## 2.2 ПЛАТФОРМЫ

Ссылки с этих платформ разрешаются из коробки:

- 📺 YouTube, Twitch, Kick, BiliBili, Odysee, D.tube, VidLii
- 🐦 Twitter (X), Bluesky, TikTok, Facebook, Instagram
- 🎵 SoundCloud, Newgrounds (фильмы, музыка и арт)
- 🖼️ Imgur, Lightshot, Medal, Streamable, Sendvid
- 📁 Google Drive, Dropbox, MediaFire
- 🔞 PornHub (только при включённом `platforms.allowMatureContent`)
- 🔗 Прямые ссылки на любое видео, музыку, изображение или плейлист, локальные файлы и
  [ссылки `water://`](#24-протокол-water)

YouTube, SoundCloud, Facebook и Instagram воспроизводятся через _yt-dlp_, которому нужен Binaries. BiliBili
воспроизводится до 480p или до 1080p (8K с VIP) с cookie вашей сессии в `platforms.biliBiliCookie`.

```java
Platform.Resolution resolved = PlatformAPI.fetch(uri);       // THE RESOLUTION WITHOUT AN MRL, FOR TOOLS; null WHEN NO PLATFORM HANDLES THE LINK

// YOUR OWN SITE: CHECKED BEFORE THE BUILT-IN PLATFORMS, AND IT NEEDS NO STARTUP
PlatformAPI.register(new Platform() {
    @Override
    public String name() { return "MySite"; }

    @Override
    public Platform.Resolution resolve(final URI uri) throws PlatformException {
        if (!"mysite.com".equals(uri.getHost())) return null;            // NOT YOURS: THE NEXT PLATFORM TRIES
        if (isProfile(uri)) throw PlatformException.unsupported(this.name(), uri); // YOURS BUT NOTHING TO PLAY: MRL GOES ERROR
        if (isMature(uri)) throw new MatureContentException(this.name(), "Mature video"); // MRL GOES BLOCKED

        final Platform.Quality quality = new Platform.Quality(findVideo(uri), 1920, 1080); // REAL SIZE, 0 WHEN UNKNOWN
        final Platform.Source source = new Platform.Source(MediaType.VIDEO, null, null, null, List.of(quality), null, null);
        return new Platform.Resolution(null, source);                    // null EXPIRY: THE LINKS NEVER EXPIRE
    }
});
```

Любая другая ошибка вашей платформы тоже оформляется как `PlatformException`, а альбом или плейлист пропускает плохой
элемент, а не падает целиком.

## 2.3 ПОИСК

YouTube (с Binaries), Twitch, Kick и Imgur поддерживают поиск по ключевым словам, а ваша платформа присоединяется к ним,
если переопределит `search(query, limit)`.

```java
List<PlatformResult> results = PlatformAPI.search("lofi");   // RETURNS AT ONCE; THE LIST FILLS AS PLATFORMS ANSWER
boolean searching = PlatformAPI.searching("lofi");           // false ONCE EVERY PLATFORM ANSWERED; POLL BOTH FROM THE UI
for (final PlatformResult hit: results) {
    show(hit.title(), hit.thumbnail(), hit.platform().name()); // WHEN PICKED, PlayerAPI.mrl(hit.uri()) OPENS IT LIKE ANY LINK
}
List<PlatformResult> fresh = PlatformAPI.search("lofi", true); // ASK THE PLATFORMS AGAIN INSTEAD OF THE CACHE
```

Новый запрос заменяет выполняющийся, а запрос, похожий на продолжение набора предыдущего, немного ждёт следующего
нажатия клавиши. Twitch и Kick показывают только каналы, которые в эфире прямо сейчас.

## 2.4 ПРОТОКОЛ WATER

```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

Ссылки remote и global содержат ID файла, выданный сервером, а не путь к папке.

## 2.5 ДОВЕРИЕ HTTPS И УЧЁТНЫЕ ДАННЫЕ

HTTPS работает из коробки с любым сертификатом, которому Java уже доверяет. Воспроизведение проверяет каждый сертификат
по truststore JVM, который WATERMeDIA читает при своём запуске, и эта проверка никогда не отключается.

Чтобы использовать самоподписанный сертификат или собственный центр сертификации, импортируйте его в truststore и
укажите этот truststore Java до запуска WATERMeDIA (внутри Minecraft добавьте те же опции `-D` в аргументы JVM
лаунчера):

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- Пустой или повреждённый truststore не даёт _FFmpeg_ запуститься, поэтому видео остаётся выключенным, пока его не
  исправят.
- Собственной сборке _FFmpeg_ нужна такая же поддержка TLS, как у встроенной.
- Заголовки входа и cookie (`Authorization`, `Cookie`, `X-WaterMedia-Token`) доходят только до сервера первого запроса и
  никогда до редиректа на другой сервер, а редиректы могут вести только на ссылки http или https.

# 3. ВОСПРОИЗВЕДЕНИЕ МЕДИА

> [!NOTE]
> Настраивается: `player.defaultQuality`, `player.cacheBudget`, `player.watertx.cache` и
> `player.watertx.texturesBudget`. Смотрите их комментарии в `config/watermedia.toml`.

## 3.1 СОЗДАНИЕ ПЛЕЕРА И УПРАВЛЕНИЕ ИМ

```java
MediaPlayer player = PlayerAPI.player(mrl, 0,
        () -> PlayerAPI.glEngine(renderThread, renderExecutor),
        PlayerAPI::jsEngine);
if (player == null) {
    throw new IllegalStateException("Source is unavailable or its backend failed");
}
try {
    if (!player.start()) throw new IllegalStateException("Player refused to start");
} catch (RuntimeException | Error failure) {
    player.release();
    throw failure;
}
```

`player` возвращает null, если источник ещё разрешается, индекс неверный, нужный бэкенд недоступен или конструирование
бросает `Exception`; `Error` пробрасывается дальше. Проверьте статус MRL, прежде чем решать, повторять ли попытку.

- Поставщики вызываются только при необходимости, а изображения никогда не вызывают аудиопоставщик.
- Поставщик, возвращающий null, отключает этот вывод, например `() -> null` в качестве графического движка для
  воспроизведения только звука.
- Никогда не делите один движок между плеерами.

`TxMediaPlayer` воспроизводит поддерживаемые изображения и анимации, `FFMediaPlayer` воспроизводит аудио и видео через
_FFmpeg_, а `ServerMediaPlayer` представляет собой часы синхронизации без декодирования и нативных движков.

У всех трёх плееров одинаковые методы управления. Методы управления воспроизведением возвращают `true`, когда вызов
сработал, и `false`, когда он был отклонён: сейчас это невозможно, плеер уже прошёл `release()` или скорость вне
диапазона; настройки вывода ничего не возвращают.

```java
// PLAY, PAUSE AND STOP
boolean started = player.start();                 // PLAYS FROM THE BEGINNING; ALSO RESTARTS AN ENDED, STOPPED OR FAILED PLAYER
boolean startedPaused = player.startPaused();     // THE SAME, BUT LANDS PAUSED (SAFER THAN start() FOLLOWED BY pause())
boolean paused = player.pause();                  // false WHEN ALREADY PAUSED, ENDED, STOPPED OR FAILED; WHILE LOADING IT WAITS FOR THE PIPELINE
boolean resumed = player.resume();                // false UNLESS PAUSED
boolean pauseChanged = player.pause(wantPaused);  // pause() OR resume() FROM A FLAG, SUCH AS A CHECKBOX
boolean toggled = player.togglePlay();            // PAUSES, RESUMES, OR STARTS AN ENDED OR STOPPED PLAYER AGAIN
boolean stopped = player.stop();                  // STOPPED WITH time() AT 0; false WHEN ALREADY STOPPED OR NEVER STARTED

// MOVE THROUGH THE MEDIA (MILLISECONDS, CLAMPED TO THE DURATION)
boolean sought = player.seek(15_000);             // PRECISE; ON AN ENDED OR STOPPED PLAYER IT RESTARTS PAUSED AT THE TARGET
boolean soughtQuick = player.seekQuick(15_000);   // FASTER, LESS PRECISE ON VIDEO; THE SAME AS seek() ON IMAGES
boolean skipped = player.skipTime(-10_000);       // RELATIVE TO time(); NEGATIVE GOES BACK
boolean forwarded = player.forward();             // 5 SECONDS AHEAD (1 SECOND ON ANIMATIONS)
boolean rewound = player.rewind();                // 5 SECONDS BACK (1 SECOND ON ANIMATIONS)
boolean stepped = player.nextFrame();             // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK
boolean steppedBack = player.previousFrame();     // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK

// PLAYBACK OPTIONS
boolean speedChanged = player.speed(1.25f);       // (0, 4]; false ON LIVE STREAMS, JAVA SOUND OR A RATE THE AUDIO ENGINE REFUSES
boolean repeatChanged = player.repeat(true);      // false ONLY AFTER release()
boolean trackChanged = player.audioTrack(track);  // SEE AUDIO TRACKS AND LANGUAGES
boolean placed = player.spatialAudio(position);   // SEE SPATIAL AUDIO; false WITHOUT A SPATIAL OPENAL ENGINE

// OUTPUT SETTINGS: ALWAYS TAKEN, SO THEY RETURN NOTHING
player.volume(50);                                // 0 TO 100, CLAMPED; MUTE NEVER CHANGES IT
player.mute(true);
player.quality(MediaQuality.HIGH);                // THE CLOSEST AVAILABLE; HOT SWITCH ON VIDEO, NEXT START ON IMAGES
player.scale(1280, 720);                          // UPLOAD SIZE CAP PER AXIS, 0 FOR NO CAP
player.lod(MediaPlayer.LodLevel.FAR);             // UPLOAD SIZE BY DISTANCE: MAX 100%, CLOSE 75%, NEAR 50%, FAR 25%, FAR_AWAY 10%
player.displayTime(10_000);                       // A STILL IMAGE ENDS AFTER THIS AND NEVER LOOPS; 0 KEEPS IT UNTIL STOPPED
player.audioLanguage("es-MX");                    // PREFERRED AUDIO LANGUAGE FROM THE NEXT START

// WHAT THE MEDIA ALLOWS: ASK BEFORE SHOWING A CONTROL
boolean playable = player.canPlay();              // FROM THE FIRST SUCCESSFUL LOAD (FROM CREATION ON A CLOCK) UNTIL IT FAILS OR IS RELEASED
boolean seekable = player.canSeek();              // false UNTIL THE SOURCE OPENS, ON LIVE STREAMS AND ON STILL IMAGES
boolean speedable = player.canSpeed();            // A PURE QUERY, SAFE FROM ANY THREAD ON EVERY FRAME
boolean live = player.live();                     // false UNTIL THE SOURCE OPENS
boolean positional = player.spatial();            // POSITIONAL AUDIO IS AVAILABLE

// WHERE IT IS
MediaPlayer.Status status = player.status();      // ALSO playing(), paused(), buffering(), ended(), error()...
long time = player.time();                        // 0 BEFORE THE FIRST FRAME, THE DURATION ONCE ENDED
long duration = player.duration();                // 0 WHEN UNKNOWN OR LIVE
int buffered = player.buffered();                 // 0 TO 100: REAL PROGRESS WHILE A VIDEO LOADS OR BUFFERS; IMAGES AND CLOCKS REPORT 100 OR 0
float fps = player.fps();                         // 0 UNTIL KNOWN
Throwable failure = player.exception();           // WHY IT LANDED IN ERROR
int width = player.width();                       // UPLOADED SIZE; sourceWidth() AND sourceHeight() GIVE THE NATIVE ONE
int height = player.height();

// DRAW: ON THE RENDER THREAD, READ FRESH ON EVERY FRAME
long texture = player.texture();                  // OPENGL TEXTURE NAME OR VULKAN VkImageView HANDLE; 0 MEANS NOTHING TO DRAW

// CLEAN UP WHEN THE HOST DROPS THE SURFACE, FAILED PLAYBACK INCLUDED
player.release();                                 // IDEMPOTENT, LEAVES status() AT STOPPED; NEVER FROM A LISTENER OR PLAYBACK THREAD
```

Вызывайте `speed(...)` там, где аудиоконтекст текущий: часы плеера следуют только той скорости, которую принял
аудиодвижок. Масштабирование и уровень детализации работают на поддерживаемых раскладках пикселей, а встроенные
графические движки пока не принимают сжатые текстуры BC.

```java
final MediaPlayer.StatusListener playlist = new MediaPlayer.StatusListener() {
    @Override
    public void onStatus(final MediaPlayer source, final MediaPlayer.Status previous, final MediaPlayer.Status current) {
        // EVERY REAL TRANSITION, ONE AT A TIME AND IN ORDER; KEEP IT SHORT AND NEVER BLOCK
        if (current != MediaPlayer.Status.ENDED) return;
        source.onStatus(this); // ENDED, STOPPED AND ERROR CONSUME THE LISTENERS: REGISTER AGAIN TO KEEP HEARING
        source.start();        // RESTARTING HERE IS FINE; source.release() NEVER IS
    }
};
player.onStatus(playlist); // ADDING IT TWICE KEEPS IT ONCE
```

- Слушатель выполняется в том потоке, который вызвал изменение: в потоке воспроизведения, в общем тикере синхронизации
  или в потоке, вызвавшем `status()`, `time()` или `texture()` у плееров изображений, чьи часы определяют конец лениво.
- Он никогда не выполняется, пока плеер держит одну из своих блокировок, и не выполняется в потоке воспроизведения,
  которого ждёт `release()`. `release()` доставляет свой `STOPPED`, когда завершится и движки будут освобождены, поэтому
  к моменту этого колбэка у плеера уже ничего не остаётся в ожидании.

| Состояние плеера | Значение |
| --- | --- |
| `WAITING` | Создан и ждёт условий для запуска. |
| `LOADING` | Загружает или подготавливает медиа. |
| `BUFFERING` | Ждёт достаточного количества данных, чтобы продолжить. |
| `PLAYING` | Воспроизводит медиа. |
| `PAUSED` | На паузе и готов продолжить. |
| `STOPPED` | Остановлен и готов начать с начала. |
| `ENDED` | Дошёл до конца медиа. |
| `ERROR` | Воспроизведение столкнулось с ошибкой. |

`Status.allows(next)` сообщает, может ли пауза, продолжение, буферизация, завершение, остановка или ошибка перевести
статус прямо в `next`. Запуск, перемотка и перезапуск идут своими путями: плеер выходит из `LOADING` только когда
открывается его пайплайн (или когда он останавливается или падает), перемотка в `ENDED` или `STOPPED` перезапускает его,
а `STOPPED` не допускает прямых переходов.

## 3.2 АУДИОДОРОЖКИ И ЯЗЫКИ

Дублированные видео и файлы с несколькими аудиопотоками воспроизводят оригинальный звук (или звук по умолчанию), если вы
не попросите другое. Платформы перечисляют отдельные аудиодорожки источника, начиная с оригинальной, а внутри файла или
HLS-потока побеждает аудиопоток, помеченный как поток по умолчанию.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- Порядок выбора такой: ваш выбор через `audioTrack(...)`, затем `audioLanguage(...)`, затем оригинал (первая отдельная
  дорожка) или, если отдельных дорожек нет, аудиопоток файла по умолчанию, затем первый. Языковой тег сначала совпадает
  с таким же тегом, затем с тем же языком, поэтому `es-MX` возьмёт `es-419`, `es` или `spa`, который содержится в файле.
  Автоматического языка нет: без `audioLanguage(...)` играет оригинал, а не автоматический дубляж.
- `audioTracks()` перечисляет по одному `AudioTrack(track, stream, name, lang)` на каждый аудиопоток: сначала потоки
  основного входа, затем каждой отдельной дорожки из `source().audioTracks()`. `track` равен `AudioTrack.MAIN` или
  индексу отдельной дорожки, а `stream` является индексом потока _FFmpeg_ внутри неё.
- Отдельная дорожка, которая ещё не воспроизводилась, показывается как одна запись `provisional()` (`stream` равен -1),
  собранная из названия и языка платформы, поэтому получение списка никогда не открывает все дубляжи заранее. Как только
  она заиграет, её место займут реальные аудиопотоки. Список представляет собой неизменяемый снимок, который можно
  читать из любого потока.
- `audioTrack(track)` возвращает `true`, если выбор изменился. Работающий плеер перестраивает свой пайплайн на текущем
  времени (прямой эфир перезапускается с самого свежего момента), а выбор сохраняется при смене качества и перезапусках.
  `audioTrack()` возвращает то, что играет сейчас. Плееры изображений и серверные плееры, а также плееры без аудиодвижка
  ничего не перечисляют.

## 3.3 ВЫБОР ДВИЖКОВ

Разработка под Vulkan идёт в первую очередь; OpenGL остаётся поддерживаемым как второстепенный бэкенд. Решения о бэкенде
принимаются в вашем слое рендеринга.

| Фабрика | Обязательства хоста |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | Исполнитель выполняет задачи в потоке, который владеет контекстом OpenGL, и вы продолжаете выполнять его задачи каждый кадр, пока воспроизведение или освобождение могут его ждать, а также ещё раз после `release()`. Если оба аргумента null, контекстом должен владеть вызывающий поток. Никогда не переносите движок между контекстами. |
| `PlayerAPI.vkEngine(context)` | Предоставьте `VKContext`, чьи объекты Vulkan и механизм освобождения (retirement) живут дольше движка: держите `queueLock()` вокруг своих собственных submit и present и выполняйте колбэки `retire(...)`, когда завершатся ваши кадры в полёте (все сразу, когда устройство уходит). Чтобы декодировать видео на GPU, см. [Декодирование через Vulkan](#декодирование-через-vulkan). |
| `PlayerAPI.awtEngine(onFrame)` | Рисуйте опубликованное изображение из UI на AWT/Swing; при необходимости перенаправляйте необязательный колбэк в поток UI. |
| `PlayerAPI.jfxEngine(onFrame)` | Нужна среда выполнения JavaFX 13+; привяжите изображение движка к своему UI. Без неё фабрика бросает `IllegalStateException` вместо ошибки линковки. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Захватывает загруженные кадры без графического контекста, удобно для тестов и обработки изображений. Как и любой движок и медиаплеер, существует только на клиенте; выделенный сервер может создавать только часы `ServerMediaPlayer`. |
| `PlayerAPI.alEngine()` | Контекст OpenAL уже текущий и остаётся действительным в потоках, которые работают со звуком. |
| `PlayerAPI.alEngine(true)` | Тот же контракт OpenAL, воспроизведение через позиционные моно-синки (стереопары и дополнительные динамики). |
| `PlayerAPI.jsEngine()` | Устройство и линия Java Sound доступны; без позиционного звука. |

> [!IMPORTANT]
> **OpenGL:** для конвертации YUV и видео в оттенках серого на GPU нужен OpenGL 3.2 core (GLSL 150); без него кадры
> конвертируются в BGRA на CPU. Для загрузки прямо из потока декодирования нужен OpenGL 4.4 или `ARB_buffer_storage`;
> более старые драйверы загружают кадры в потоке рендеринга через два пиксельных буфера.

> [!IMPORTANT]
> **Vulkan:** `queue()` должна принимать задачи compute и transfer.
> - **Zero copy:** декодированные плоскости попадают на GPU без копирования на CPU, если устройство включает
>   `VK_EXT_external_memory_host` (`hostImportSupported()` возвращает true) и буфер плоскости выровнен по
>   `minImportedHostPointerAlignment()`; иначе плоскость копируется через staging-буфер.
> - **Аппаратная конвертация YUV:** 8-битные SDR-кадры NV12 и YUV 4:2:0, 4:2:2 и 4:4:4 с чётными размерами и без
>   основных цветов BT.2020 конвертируются аппаратно в сэмплере, если устройство включает `samplerYcbcrConversion`
>   (Vulkan 1.1, `ycbcrSampler()` возвращает true); все остальные раскладки используют вычислительный проход (compute
>   pass).
> - **Раскладки:** помимо 8-битных BGRA, RGBA, YUYV и UYVY, раскладке нужны форматы плоскостей с поддержкой
>   сэмплирования, линейной фильтрации и записи через transfer, иначе она откатывается на BGRA. RGB откатывается всегда.
> - **Декодирование на GPU:** см. [Декодирование через Vulkan](#декодирование-через-vulkan).

Базовые классы движков запечатаны (sealed): ваша интеграция предоставляет контексты хоста и исполнители, а не
собственные реализации движков. Движки сами управляют своими ресурсами воспроизведения, включая текстуры, аудиоисточники
и буферы.

Все движки следуют одному жизненному циклу:

- `release()` идемпотентен, никогда не должен выполняться одновременно с плеером, который передаёт данные движку, и
  оставляет движок неактивным: `released()` равен true, а `texture()` возвращает 0.
- Плеер проверяет `supports(format, bits)` перед загрузкой и откатывается на 8-битный BGRA, если движок отказывается от
  раскладки.
- Загрузки никогда не бросают исключений: при сбое драйвера кадр отбрасывается с одной ошибкой в логе, а последний кадр
  остаётся на экране.
- Программные и headless-движки отдают ненулевое значение-заглушку (sentinel) вместо дескриптора GPU, поэтому
  сравнивайте дескриптор только с 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` и `frameBytes` вычисляют размер каждой плоскости по `format()`,
  `width()`, `height()` и `bits()` движка, например, чтобы рассчитать бюджет его памяти. Субдискретизированные плоскости
  округляются вверх, как в _FFmpeg_, поэтому нечётные размеры сохраняют последний столбец и строку цветности.

Работа с цветом:

- Движки OpenGL и Vulkan конвертируют YUV с той матрицей (BT.601, BT.709 или BT.2020), диапазоном и положением
  цветности, которые объявляет каждое видео. Если видео ничего не объявляет, они угадывают, как _mpv_: BT.601 для
  SD-кадров (уже 1280 пикселей и высотой до 576 строк), иначе BT.709.
- HDR в PQ и HLG отображается как SDR: цвета BT.2020 переводятся в BT.709, а светлые участки сжимаются так, чтобы пик в
  1000 нит достигал белого.
- Движки на одном контексте OpenGL или устройстве Vulkan собирают эту конвертацию один раз и делят её между собой. Она
  уходит вместе с работой по освобождению последнего движка (задачей, которую `release()` ставит в исполнитель
  рендеринга OpenGL, или колбэками `VKContext.retire`), поэтому освободите каждый движок и дайте этой работе
  выполниться, прежде чем уничтожать контекст или устройство.
- Программные движки получают BGRA, который _FFmpeg_ конвертирует с объявленными матрицей и диапазоном, без tone mapping
  для HDR.

OpenAL требует, чтобы его исходный контекст был текущим в каждом потоке, который управляет плеером, включая тикер
синхронизации. Движок Java Sound сам сериализует свои вызовы, поэтому ему не нужны дополнительные блокировки, а когда
аудиофрагмент превышает буфер линии, он заново открывает линию пошире, отбрасывая всё, что ещё оставалось в старой.

> [!IMPORTANT]
> **OpenAL:** 4-, 6-, 7- и 8-канальному звуку на обычном движке нужен `AL_EXT_MCFORMATS` (пространственные движки сводят
> всё в стерео), моно- и стереосэмплам float и double нужны `AL_EXT_FLOAT32` и `AL_EXT_DOUBLE` (surround во float идёт
> вместе с `AL_EXT_MCFORMATS`), а часам, которые учитывают задержку вывода устройства, нужен `AL_SOFT_source_latency`;
> без них звук конвертируется в то, что устройство умеет воспроизводить.
> [Обработчикам окружения](#обработчики-окружения), которые используют EFX, нужен `ALC_EXT_EFX`.

Автономное приложение (при открытии JAR WATERMeDIA) рендерит через Vulkan, если пользователь не выберет OpenGL; когда
Vulkan недоступен, этот запуск идёт на OpenGL, а сохранённый выбор остаётся прежним. `-Dwatermedia.engine=opengl` или
`-Dwatermedia.engine=vulkan` переопределяет движок на один запуск без сохранения. Приложение хранит свой движок, масштаб
интерфейса (`0` следует за монитором, иначе множитель от 1 до 3 с шагом 0.25, который в настройках отображается как
`Auto`, `x1.0`, `x1.25` и так далее), CRT-эффект и звук клика в `config/app.properties` в своей рабочей директории.

### Декодирование через Vulkan

По умолчанию `VKContext.deviceInfo()` возвращает null, и видео декодируется в другом месте, а затем загружается из RAM.
Переопределите его, описав, как было создано ваше устройство (цепочка возможностей, переданная в `vkCreateDevice`,
которая остаётся выделенной и неизменной, пока живёт устройство, плюс включённые расширения экземпляра и устройства и
очереди, созданные для каждого семейства), и _FFmpeg_ будет декодировать H.264, H.265 и AV1 через Vulkan Video, а движок
будет сэмплировать декодированные изображения без пересылки через RAM.

> [!IMPORTANT]
> Для декодирования через Vulkan нужны:
> - Экземпляр и устройство Vulkan 1.3 с включёнными `timelineSemaphore` и `synchronization2`.
> - `VK_KHR_video_queue`, `VK_KHR_video_decode_queue` и расширение декодирования для каждого ускоряемого кодека:
>   `VK_KHR_video_decode_h264`, `VK_KHR_video_decode_h265` или `VK_KHR_video_decode_av1`.
> - Свободные очереди на вашем устройстве или расширения внешней памяти и семафоров для двойника; оба варианта описаны
>   ниже.
> - Нативные библиотеки с Vulkan Video (`CodecsAPI.vulkanDecode()` возвращает true) и включённый
>   `codecs.ffmpeg.hardwareAccel`.

Тогда _FFmpeg_ декодирует в одном из двух мест:

- **Ваше устройство**, если у него есть ещё и очереди, которыми может владеть _FFmpeg_: очередь с поддержкой compute
  (она же принимает transfer), отличная от `queue()`, плюс очередь декодирования видео, причём `queue()` создана
  последней в `queueFamily()`. Все остальные очереди всех семейств отходят _FFmpeg_, который отправляет в них работу из
  своих потоков, поэтому, пока существуют плееры, вы никогда не трогаете эти очереди и не вызываете `vkDeviceWaitIdle`;
  вместо этого ждите свою собственную очередь (`vkQueueWaitIdle` под `queueLock()`).
- **Двойник**, если у вашего устройства нет таких очередей, но включены `VK_KHR_external_memory_win32` и
  `VK_KHR_external_semaphore_win32` на Windows или `VK_KHR_external_memory_fd` и `VK_KHR_external_semaphore_fd` на
  других системах. Движок создаёт второе устройство на том же GPU с вашей цепочкой возможностей и расширениями, _FFmpeg_
  декодирует там, а движок импортирует каждое декодированное изображение в ваше устройство. Ваши очереди остаются только
  вашими.

В любом случае освобождайте каждый плеер до уничтожения устройства или его экземпляра: _FFmpeg_ использует их (включая
двойника), пока не исчезнет его последний декодер.

> [!IMPORTANT]
> Видео в 4:2:2, 4:4:4 и 12 бит декодируется на GPU только через _libplacebo_, которая превращает любую декодированную
> раскладку в SDR RGBA (HDR проходит tone mapping). Ей нужны `hostQueryReset`, `runtimeDescriptorArray`,
> `shaderStorageImageArrayNonUniformIndexing` и `maintenance4` на декодирующем устройстве, а на вашем собственном
> устройстве ещё и вторая очередь в `queueFamily()`; двойник включает эти возможности сам, если GPU их поддерживает. Без
> _libplacebo_ на GPU декодируются только потоки 4:2:0 (8 и 10 бит), и движок конвертирует их своими шейдерами.

## 3.4 ПРОСТРАНСТВЕННЫЙ ЗВУК

Создайте движок OpenAL через `PlayerAPI.alEngine(true)` до создания плеера. Пространственный режим зафиксирован на всё
время жизни движка: _FFmpeg_ сводит стерео- и surround-источники в стерео (центральный канал попадает в обе стороны,
моно-источники остаются моно), а движок воспроизводит их через синки (sinks), то есть позиционные моно-источники.
Обычная фабрика `alEngine()` воспроизводит все каналы на одном сухом источнике, привязанном к слушателю, хотя раскладки
каналов, которые устройство не умеет воспроизводить, всё равно могут быть сконвертированы в ближайшее количество
каналов. Java Sound возвращает `false` на пространственные обновления и не отдаёт нативный источник.

Движок стартует с одним синком, основным, который воспроизводит сухой звук `Mix.MONO`, привязанный к слушателю.
Передайте состояние `SpatialAudio`, чтобы разместить его в мире, или `null`, чтобы вернуться к сухому воспроизведению
относительно слушателя. Громкость, отключение звука, скорость, пауза и следование за часами (`sync`) не зависят от
пространственных настроек и доходят до каждого синка.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Создавайте, обновляйте и освобождайте движок в звуковом исполнителе хоста, когда исходный контекст OpenAL текущий. Хост
владеет устройством, контекстом и слушателем, и они должны пережить каждый плеер, который их использует.

- `FFMediaPlayer` использует собственные приватные потоки воспроизведения и, как и обычное воспроизведение через
  `ALEngine`, сейчас требует, чтобы контекст источника был текущим для всего процесса в этих потоках. Хосты, которые
  предоставляют свой аудиоконтекст только через привязку к потоку (thread-local), не поддерживаются: привязать его
  только в звуковом потоке вызывающего недостаточно.
- Нативные операции движка отклоняют чужой или отсутствующий контекст; они никогда не переключают и не пересоздают
  контекст хоста.
- Освобождайте плееры перед перезагрузкой устройства, а затем создавайте новые движки в новом контексте.
- `FFMediaPlayer.release()` прерывает свой пайплайн воспроизведения и дожидается его завершения перед удалением
  аудиоисточника; вызывайте его до уничтожения контекста и никогда как колбэк из этого пайплайна.

`SpatialAudio` проверяет, что позиции конечны и помещаются во float OpenAL, что `0 < referenceDistance < maxDistance` и
что rolloff конечен и неотрицателен; нулевой rolloff отключает затухание с расстоянием. То, как эти параметры ослабляют
звук, решает модель расстояния хоста, а API никогда не трогает глобальную модель расстояния, слушателя или скорость
источника, поэтому `maxDistance` не является универсальным радиусом тишины при любой модели OpenAL.

### Синки: стереопары и дополнительные динамики

Одно декодирование питает все синки пространственного движка, поэтому стереопанорама или динамик, размещённый в другом
месте, не стоят ни второго скачивания, ни второго декодирования. Каждый синк воспроизводит один `ALEngine.Mix`
декодированного звука: `LEFT`, `RIGHT` или `MONO` (среднее обоих каналов). Моно-источник воспроизводит одинаковые сэмплы
в любом миксе, поэтому стереопара никогда не замолкает на моно-медиа.

```java
// A STEREO PICTURE: THE PRIMARY TURNS INTO THE LEFT EDGE AND A NEW SINK PLAYS THE RIGHT ONE
final ALEngine.Sink left = player.sinks().get(0);
left.mix(ALEngine.Mix.LEFT);
final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
left.spatialAudio(new SpatialAudio(leftX, y, z, 8.0f, 64.0f, 1.0f, false, environment));
right.spatialAudio(new SpatialAudio(rightX, y, z, 8.0f, 64.0f, 1.0f, false, environment));

// A SPEAKER BLOCK: ONE MORE SINK AT ITS OWN POSITION, RELEASED WHEN THE BLOCK GOES AWAY
final ALEngine.Sink speaker = player.addSink(ALEngine.Mix.MONO);
speaker.spatialAudio(new SpatialAudio(speakerX, speakerY, speakerZ, 8.0f, 64.0f, 1.0f, false, environment));
speaker.release();
```

- Основной синк владеет `player.audioSource()`. Он ведёт часы медиа и живёт столько же, сколько движок; его `release()`
  возвращает `false`. `player.sinks()` перечисляет основной первым, а без пространственного движка OpenAL `sinks()` и
  `addSink(...)` дают пустой список или `null`.
- Каждый синк ставит в очередь одни и те же сэмплы, а воспроизведение, пауза, сброс, скорость и громкость выполняются
  одним пакетом на всех них, поэтому стереопара остаётся выровненной с точностью до сэмпла. Добавление синка во время
  воспроизведения или смена микса на стерео-медиа перестраивает каждую очередь по сэмплу основного синка, из-за чего
  синки замирают примерно на одно обновление микшера.
- У каждого синка свой `spatialAudio(...)`: адаптер окружения получает источник этого синка в `process` и `reset`, а
  `sink.release()` сбрасывает его перед удалением источника. Освобождение плеера освобождает все синки.
- Добавляйте, изменяйте и освобождайте синки в звуковом исполнителе хоста, когда контекст движка текущий, как и любое
  другое пространственное обновление. Их запросы (`source()`, `mix()`, `spatialAudio()`, `released()`) не требуют
  контекста.
- Хост может взять источник синка в свою звуковую систему и записывать его усиление (gain), высоту тона (pitch) и
  позицию, но никогда не должен запускать, ставить в очередь, снимать с очереди или удалять его: транспортом и буферами
  владеет движок. Новый синк стартует с текущими усилением и высотой тона движка.

### Обработчики окружения

Последний аргумент `SpatialAudio` представляет собой необязательный `SpatialAudio.Environment`, который применяет ваши
собственные эффекты, например окклюзию или реверберацию, к каждому источнику OpenAL. Это дверь для модов
пространственного звука: напишите в своём моде небольшой адаптер, который передаёт им каждый источник, и загружайте его,
только когда такой мод установлен.

```java
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        // EVERY spatialAudio(...) UPDATE: APPLY YOUR WHOLE ENVIRONMENT (EFX FILTERS AND SENDS) TO source
    }

    @Override
    public void reset(final int source) {
        // THE PROCESSOR IS REPLACED, REMOVED OR THE SINK IS RELEASED: REMOVE YOUR EFFECTS FROM source
    }
};
player.spatialAudio(new SpatialAudio(x, y, z, 8.0f, 64.0f, 1.0f, false, environment));
```

- Держите один стабильный экземпляр обработчика между обновлениями и вызывайте `spatialAudio(...)` из звукового
  исполнителя хоста, когда контекст текущий.
- Каждое обновление восстанавливает исходную позицию в мире и очищает прежние фильтры и вспомогательные посылы
  (auxiliary sends) источника перед вызовом `process`, поэтому обработчик может сдвигать `AL_POSITION` и должен
  применять всё своё окружение при каждом вызове.
- Ограничивайте частоту обновлений на стороне хоста, а не выходите досрочно внутри обработчика, и распределяйте много
  плееров по разным тикам.
- Движок удаляет только свой источник и буферы, но никогда фильтры или слоты эффектов обработчика.
- Если `process` или `reset` бросает исключение, эффекты источника очищаются, а исключение пробрасывается дальше;
  падающий `reset` не может помешать удалению источника при освобождении.
- `auxOnly = true` просит обработчик выдавать только отражённый звук; сам по себе он не глушит прямой путь, поэтому
  отключайте звук плеера, когда нужна полная тишина.
- Обработчик выполняется только во время ваших явных обновлений и никогда из цикла декодирования _FFmpeg_ или цикла
  воспроизведения. Базовое позиционирование работает без EFX; обработчику, который использует EFX, нужно устройство,
  которое его поддерживает.

### Проверка

`SpatialAudioTest` покрывает отклонение неверных аргументов, реальные координаты и затухание источников OpenAL,
восстановление исходной позиции после колбэка, очистку эффектов, обработку исключений, несовпадающие контексты и поздние
вызовы после освобождения. Он также генерирует стерео-WAV и воспроизводит его через `FFMediaPlayer`, проверяя, что
стереодекодирование доходит до каждого синка как моно-PCM в реальных буферах OpenAL. `ALSinkTest` покрывает набор
синков, миксы во всех типах сэмплов, пакетное управление, обратное давление (backpressure), подключение синков по сэмплу
основного и восстановление после опустошения буфера (underrun). Тестам с устройством нужно устройство вывода OpenAL, а
случаям с декодированием ещё и нативные библиотеки _FFmpeg_.

## 3.5 СИНХРОННОЕ ВОСПРОИЗВЕДЕНИЕ

`PlayerAPI.serverPlayer()` создаёт `ServerMediaPlayer`: часы без медиа и движков, которые работают на любой стороне. Они
отвечают за расчёт времени: запуск, пауза, перемотка, скорость, повтор и переход через начало цикла или в `ENDED` идут
по системным часам, а каждое успешное изменение увеличивает `revision()`. Всё остальное на хосте: кто может управлять
часами, как их снимки доходят до клиентов и где сохраняется их позиция. У движков и плееров нет публичных конструкторов:
каждый экземпляр создаётся через `PlayerAPI`.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

Плеер, получивший снимок через `sync(PlayerState)` (или `sync(ByteBuffer)` для закодированной формы), с этого момента
следует за часами. Каждые 50 мс он:

- Повторяет статус снимка: запуск, паузу и остановку. Снимок с `BUFFERING`, который бывает только у собранного вручную
  снимка, удерживается как пауза.
- Перенимает его повтор и скорость.
- Возвращает воспроизведение на место через `seekQuick` с ограничением частоты, как только `drift()` превысит
  `tolerance(ms)` (по умолчанию одна секунда).

Последний полученный снимок всегда побеждает, поэтому отправляйте их в том порядке, в котором их выдали часы.
`authority()` возвращает этот снимок, `authorityTime()` экстраполирует, где часы находятся сейчас, а `following()`
сообщает, следует ли плеер за часами. Коррекция никогда не подстраивает скорость воспроизведения; она пропускается, пока
плеер загружается или буферизует, и на повторяющемся медиа идёт коротким путём через границу цикла.

Управление, вызванное у ведомого плеера, применяется сразу и отменяется следующим тиком, который с ним не согласен,
поэтому направляйте пользовательское управление к часам через собственные пакеты, а результат пусть доставит снимок.
Покадровый шаг во время следования отклоняется, а громкость, отключение звука, масштабирование и пространственный звук
всегда остаются локальными.

Часы держат свою позицию (ноль после `start()`), пока `syncDuration(ms)` или `live(true)` не скажет им, на какой
временной шкале они работают. Сообщите длительность один раз с клиента, который открыл источник (первое положительное
значение действует всё время жизни часов, а последующие отличающиеся значения игнорируются), и восстанавливайте
сохранённую позицию через `syncTime(ms)` после запуска часов, поскольку `start()` начинает с нуля. `canSeek()` равен
false, а `seek(...)` возвращает `false`, пока длительность неизвестна, и всегда в прямом эфире. `release()` замораживает
позицию и оставляет часы в `STOPPED` с новой ревизией, поэтому сохраните и отправьте этот последний снимок, прежде чем
избавляться от часов, иначе плееры продолжат экстраполировать предыдущий. Освобождайте каждый плеер, когда заканчивается
его сессия на хосте.

### Формат снимка

`MediaPlayer.PlayerState` представляет собой запись фиксированного размера в 27 байт в порядке big-endian (версия,
ревизия, статус, время, длительность, скорость, флаги). `PlayerState.read(ByteBuffer)` читает только эти байты и
оставляет последующие данные вашему транспорту; собственный порядок байтов буфера не важен, а отклонённые данные не
меняют позицию. Проверяйте, кто отправил снимок, в своём транспорте: библиотека никогда не определяет участников.

# 4. ЗАГРУЗКА И СКАЧИВАНИЕ ФАЙЛОВ

> [!WARNING]
> Сервер работает по обычному HTTP: если он смотрит в интернет, поставьте его за собственный HTTPS-прокси. С токеном по
> умолчанию он отказывается слушать где-либо, кроме `127.0.0.1`, а смена токена при каждом обновлении сервера не даёт
> посторонним загружать файлы.

> [!NOTE]
> Настраивается: `network.server`, `serverBindAddress`, `serverPort`, `remoteUrl`, `token`, `maxServerRequests`,
> `serverTimeout`, `maxUploadSize` и `maxStorageSize`. Смотрите их комментарии в `config/watermedia.toml`.

Необязательный файловый сервер хранит файлы, которые загружают ваши игроки, и делится ими со всеми через ссылки
`water://remote/<id>`. Чтобы им пользоваться:

1. Включите его через `network.server`: `DEDICATED` запускает его на выделенных серверах, `ALWAYS` ещё и на клиентах.
2. Откройте его порт наружу: сам сервер этого не делает.
3. Укажите в `network.remoteUrl` публичный адрес и порт сервера, на сервере и на каждом клиенте.
4. Задайте серверу и каждому клиенту один и тот же собственный `network.token`.

Загруженные файлы попадают в `watermedia/files` в рабочей директории сервера; удаляйте их оттуда, чтобы освободить
место.

## 4.1 ЗАГРУЗКА

```java
NetworkAPI.UploadStatus status = NetworkAPI.upload(new File("clip.mp4"));
// LATER, FROM A TICK OR A UI TIMER
if (status.completed()) {
    URI link = status.uri(); // water://remote/<id>, READY FOR PlayerAPI.mrl
} else if (status.failed()) {
    String reason = status.error();
}
```

`NetworkAPI.upload` загружает в фоне и возвращает по одному `UploadStatus` на файл: `percentage()` и `displaySpeed()`
отслеживают передачу, `cancel()` останавливает её, а `error()` сообщает, почему загрузка не удалась.

## 4.2 СКАЧИВАНИЕ

> [!NOTE]
> Настраивается: `network.timeout`, `maxRedirects` и `maxTextBytes`. Смотрите их комментарии в `config/watermedia.toml`.

Воспроизводите удалённые файлы как любую другую ссылку: `PlayerAPI.mrl("water://remote/<id>")`. Для обычной работы с
HTTP `NetRequest` (в `org.watermedia.api.util`) отправляет один запрос с теми же правилами безопасности, что и
воспроизведение:

```java
try (NetRequest request = new NetRequest("https://example.com/pack.zip").httpsOnly().maxBytes(64L * 1024 * 1024)) {
    request.send();
    request.download(Path.of("pack.zip"), expectedSha256); // THE DIGEST IS MANDATORY
}
```

- `download(path, sha256)` записывает новый файл, проверяет его и удаляет, если что-то пошло не так.
- Каждый запрос отправляется один раз: настройте его, выполните `send()` и прочитайте ответ в одном и том же потоке.
- `text()` и `inputStream()` возвращают тело ответа с ошибочным статусом, а не бросают исключение, а `json(type)`
  разбирает его, если это JSON (null для любого другого типа); проверяйте `statusCode()`.

# 5. КОДЕКИ

> [!WARNING]
> Экспериментально: `decode`, `reader`, `sniff` и `encoder` могут измениться в любом обновлении, поэтому пока не
> используйте их в своих модах (см. [публичные и внутренние пакеты](#12-публичные-и-внутренние-пакеты)).

> [!NOTE]
> Настраивается: `codecs.maxImageSourceSize`, `codecs.png.failOnCorruptedData`, `codecs.png.useBKGDChunk`,
> `codecs.svg.maxSize` и `codecs.vp8.brokenTokens`. Смотрите их комментарии в `config/watermedia.toml`.

`CodecsAPI` выдаёт декодер (`ImageReader`) и энкодер (`Encoder`). Изображения, статичные или анимированные, используют
наши собственные кодеки на чистой Java и не требуют ни запуска, ни _FFmpeg_; видео проходит через _FFmpeg_.

## 5.1 ДЕКОДЕРЫ

### Статичные изображения

PNG, JPEG, WebP, SVG и Netpbm.

```java
ImageData image = CodecsAPI.decode(bytes);                   // A byte[], A ByteBuffer OR AN InputStream
ByteBuffer pixels = image.frames()[0];                       // TIGHTLY PACKED; JPEG AND WEBP COME ROTATED TO THEIR EXIF ORIENTATION
PixelFormat format = image.format();                         // BGRA (GRAY FOR GRAYSCALE JPEGS)
int width = image.width();
int height = image.height();

ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);  // ASK FOR BGRA, RGBA OR GRAY AND IT CONVERTS
```

### Анимированные изображения

GIF, APNG и анимированный WebP. Ридер проходит по кадрам один за другим, а не держит их все в памяти.

```java
try (ImageReader reader = CodecsAPI.reader(buffer)) {        // DON'T CHANGE buffer UNTIL THE READER CLOSES
    int frames = reader.frameCount();
    boolean forever = reader.repeat() == ImageData.REPEAT_FOREVER;
    reader.addFilter(new DownscaleFilter(0.5f));             // OPTIONAL: SHRINKS EVERY FRAME, NEVER ENLARGES

    while (reader.hasNext()) {
        ByteBuffer frame = reader.next();                    // REUSED ON THE NEXT CALL: COPY IT TO KEEP IT
        long delayMs = reader.delay();                       // HOW LONG THIS FRAME STAYS ON SCREEN
    }
    boolean rewound = reader.reset();                        // BACK TO THE FIRST FRAME; false WHEN IT CAN'T
    ImageData all = reader.readAll();                        // OR EVERY FRAME LEFT AT ONCE, AS COPIES
}
```

### Любое изображение

```java
MediaType type = CodecsAPI.sniff(buffer);                    // IMAGE, VIDEO, AUDIO... FROM THE FIRST BYTES
ImageMetadata info = reader.metadata();                      // ON AN OPEN READER: title(), authors()... AND value(CodecsAPI.METAKEY_EXIF)

try {
    ImageData image = CodecsAPI.decode(bytes);
} catch (UnsupportedFormatException unsupported) {
    // A VALID FILE USING A FEATURE THE DECODER LACKS: FALL BACK TO ANOTHER DECODER
} catch (CodecException broken) {
    // A BROKEN OR OVERSIZED FILE
}
```

Для некоторых форматов, которые `sniff` распознаёт как изображения (BMP, TIFF, AVIF...), декодера пока нет. Для ваших
собственных движков `new BcReader(buffer)` читает сжатые блоки BC1, BC3 и BC7 текстуры DDS; встроенные движки пока их не
рисуют.

### Видео

Видео и аудио декодируются внутри плееров: создайте плеер через `PlayerAPI.player` (см.
[воспроизведение медиа](#3-воспроизведение-медиа)).

## 5.2 ЭНКОДЕРЫ

```java
Set<VideoCodec> videoCodecs = Container.MP4.video();         // WHAT EACH CONTAINER ACCEPTS; A TRACK THAT
Set<AudioCodec> audioCodecs = Container.MP4.audio();         // DOESN'T FIT IS REFUSED BEFORE THE FILE IS TOUCHED
```

Каждый энкодер принимает один кадр за вызов в формате `Encoder.Video` с постоянно растущими временными метками в
микросекундах; каждый кадр длится до следующего. Передавайте данные каждому энкодеру из одного потока.

### Статичные и анимированные изображения

PNG (записывается как APNG, если получает больше одного кадра) и GIF, без _FFmpeg_. Они принимают кадры RGBA, BGRA, RGB
или GRAY.

```java
// STATIC: ONE FRAME AND NO AUDIO TRACK
Encoder.Video png = new Encoder.Video(VideoCodec.PNG, 512, 512, PixelFormat.RGBA, 1);
try (Encoder encoder = CodecsAPI.encoder(Container.PNG, Path.of("logo.png"), png, null)) {
    encoder.video(pixels, 0);
}

// ANIMATED: MORE FRAMES; THE FULL CONSTRUCTOR ADDS QUALITY (0 TO 100, 50 BY DEFAULT) AND PLAYS (0 LOOPS FOREVER)
Encoder.Video gif = new Encoder.Video(VideoCodec.GIF, 320, 240, PixelFormat.BGRA, 30, 80, 0);
try (Encoder encoder = CodecsAPI.encoder(Container.GIF, Path.of("clip.gif"), gif, null)) {
    for (int i = 0; i < frames.length; i++) encoder.video(frames[i], i * 33_333L);
}
```

### Видеофайлы

MP4, MOV, MKV и WebM через _FFmpeg_, плюс Ogg только для аудио. Они принимают любой несжатый `PixelFormat`.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60); // THE FULL CONSTRUCTOR SETS QUALITY, LIKE ON IMAGES
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) { // IOException WITHOUT FFMPEG
    encoder.video(frame, ptsUs);                             // EXACTLY video.frameBytes() BYTES
    encoder.audio(pcm);                                      // INTERLEAVED SAMPLES FROM TIME ZERO: PAD A LATE START WITH SILENCE
}                                                            // close() FINISHES THE FILE, WRITTEN AS <file>.part UNTIL THEN
```
