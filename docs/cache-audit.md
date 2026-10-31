# Аудит узких мест кэша видео в Rezka TV

## Краткий вывод

Текущая реализация кэширования и префетча видео (`VideoCache.kt`, `PlayerActivity.kt`) содержит **три критические архитектурные проблемы** и **четыре дефекта высокой важности**, приводящие к падению приложения, зависанию воспроизведения, конкурентной деградации сетевого канала и забиванию flash-памяти ТВ:

1. **Крах приложения при отмене префетча (`FATAL EXCEPTION`):** метод `VideoCache.cancel()` вызывает `DataSourceUtil.closeQuietly()` из главного потока, пока фоновый поток в `Dispatchers.IO` выполняет `read()`. `CacheDataSource` не потокобезопасен: обнуление внутренних ссылок вызывает `NullPointerException` или `IllegalStateException`, которые не перехватываются блоком `catch (e: IOException)` в `fill()` и валят процесс Android.
2. **Падение плеера после очистки кэша из настроек:** вызов `VideoCache.clear()` освобождает `SimpleCache` (`store.release()`). Уже созданный плеер продолжает держать ссылку на освобожденный кэш через `CacheDataSource.Factory`. На следующем запросе `SimpleCache` выбрасывает `IllegalStateException: State check failed.`, который **не перекрывается** флагом `FLAG_IGNORE_CACHE_ON_ERROR` (флаг реагирует только на чтение из кэша и `CacheException`). Воспроизведение крашится с ошибкой.
3. **Бесполезный prefetch и двойная нагрузка на сеть при HLS:** метод `variantSegments` всегда берет первый вариант плейлиста (`variants.firstOrNull()`), обычно являющийся низким битрейтом (360p), в то время как ExoPlayer через `AdaptiveTrackSelection` выбирает 1080p. В итоге prefetch забивает канал выкачиванием чужого качества, плеер качает свое качество отдельно, а кэш-хиты равны 0. При этом из-за отсутствия `FLAG_BLOCK_ON_CACHE` одновременный запрос одного и того же сегмента приводит к **двойной параллельной загрузке** из сети.
4. **Сжигание бюджета prefetch на локальном диске:** метод `drain` считает прочитанные байты без проверки попадания в кэш. Чтение уже закэшированных данных через `FileDataSource` списывает лимит `sizeBytes / 2`, прерывая префетч до того, как он доберется до нескачанных сегментов.
5. **Риск переполнения flash-памяти из-за `filesDir`:** кэш размещен в `context.filesDir` вместо `context.cacheDir`. Система Android никогда не очищает `filesDir` при нехватке места. В системных настройках ТВ файлы считаются «Данными приложения», а не «Кэшем», и кнопка системы «Очистить кэш» их не удаляет. Кроме того, удаление папки вручную оставляет зомби-таблицы в SQLite (`exoplayer_internal.db`), так как не вызывается `SimpleCache.delete(dir, databaseProvider)`.

---

## Версия Media3 в проекте

В файле `gradle/libs.versions.toml` версионирование не ведется (файл каталога версий отсутствует в проекте). Точная версия Media3 зафиксирована в `app/build.gradle.kts` (строка 83):

```kotlin
val media3 = "1.11.1"

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui-leanback:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    ...
}
```

Все доказательства ниже верифицированы по официальным исходным кодам `androidx.media3` версии **1.11.1** (артефакты `media3-datasource-1.11.1-sources.jar`, `media3-exoplayer-1.11.1-sources.jar`, `media3-database-1.11.1-sources.jar`).

---

## Сводная таблица дефектов

| Место в коде | Что не так | Первоисточник (класс / метод / константа) | Практическое следствие для пользователя | Серьёзность |
|---|---|---|---|---|
| `VideoCache.cancel`, `fill` | Межпоточный вызов `closeQuietly` во время `read` в корутине. Гонка за полями `CacheDataSource`, вылет `NullPointerException` / `IllegalStateException`. | `androidx.media3.datasource.DataSource` (Javadoc `addTransferListener`: "not thread-safe"); `CacheDataSource.read:632,638` (`checkNotNull`); `VideoCache.fill:110` (ловит только `IOException`) | Внезапное падение (крэш) приложения при переходе между сериями, перемотке или изменении буфера. | **Критично** |
| `VideoCache.clear`, `PlayerActivity.onPlayerError` | Вызов `store.release()` делает старый `SimpleCache` недействительным. Экземпляр плеера держит старую фабрику. `FLAG_IGNORE_CACHE_ON_ERROR` не ловит `IllegalStateException`. | `SimpleCache:330,352` (`checkState(!released)`); `CacheDataSource.handleBeforeThrow:866`; `CacheDataSource.open:619` | Если очистить кэш в настройках во время паузы/воспроизведения, плеер падает с ошибкой и больше не воспроизводит видео до полного перезапуска активности. | **Критично** |
| `VideoCache.variantSegments` | Всегда берет `playlist.variants.firstOrNull()`, игнорируя трек плеера (адаптивный битрейт). | `VideoCache.kt:142`; `androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist.variants`; `DefaultTrackSelector` | Префетч качает худшее качество (360p), а плеер играет 1080p. 0% попаданий в кэш, пустая трата интернет-трафика, лаги и заикания плеера из-за конкуренции за Wi-Fi. | **Критично** |
| `VideoCache.prefetch`, `install` | Отсутствует флаг `FLAG_BLOCK_ON_CACHE`. При одновременном обращении плеера и префетча к незакэшированному спану кэш обходится. | `CacheDataSource.openNextSource:715-733`; `SimpleCache.startReadWriteNonBlocking:363`; `CachedContent.lockRange:115` | Двойная загрузка одних и тех же байтов по сети: плеер и префетч параллельно качают один сегмент, забивая канал связи. | **Важно** |
| `VideoCache.drain` | Считает все байты, прочитанные из `source`, включая закэшированные (отданные `FileDataSource`). | `VideoCache.drain:127`; `CacheDataSource.openNextSource:749`; `CacheDataSource.read:638` | Бюджет префетча сгорает на чтении файлов с диска. Префетч останавливается, так и не дойдя до нескачанных участков видео. | **Важно** |
| `VideoCache.create` | Кэш размещен в `app.filesDir` вместо `app.cacheDir`. | `Context.getFilesDir()`; `Context.getCacheDir()`; Android Storage Manager guidelines | Кэш не удаляется ОС при нехватке памяти; в настройках ТВ висит как «Данные приложения»; кнопка ТВ «Очистить кэш» не работает; риск полного отказа накопителя ТВ (0 байт). | **Важно** |
| `VideoCache.clear` | Удаление каталога через `deleteRecursively()` вместо `SimpleCache.delete(dir, databaseProvider)`. | `SimpleCache.delete:100`; `StandaloneDatabaseProvider`; `CachedContentIndex.DatabaseStorage:780` | В SQLite-базе `exoplayer_internal.db` навечно остаются зомби-таблицы от старых UID кэша. База данных приложения бесконечно разрастается. | **Важно** |
| `VideoCache.prefetch` (MP4) | Для MP4 всегда создается `DataSpec(Uri.parse(url))` с нулевой позиции (`position = 0`). | `VideoCache.kt:107` (`listOf(DataSpec(Uri.parse(url)))`); `DataSpec.position` | Для MP4 префетч всегда пытается читать фильм с начала, игнорируя текущую позицию просмотра (даже если фильм запущен на середине). | **Важно** |
| `VideoCache.create`, `SettingsActivity` | Смена размера кэша в настройках не передается в `VideoCache`. Проверка `cache?.let` в `create` — мертвый код. | `VideoCache.kt:48,96`; `SettingsActivity.kt` | Изменение лимита кэша (например, с 4 ГБ на 1 ГБ) не применяется в рантайме и игнорируется вплоть до очистки кэша или перезапуска процесса. | **Мелочь** |
| `VideoCache.install` | Не передается `CacheDataSource.EventListener`. | `CacheDataSource.Factory.setEventListener`; `CacheDataSource.notifyCacheIgnored:886` | При тихом переходе кэша в режим игнорирования из-за ошибок ввода-вывода нет никаких записей в логах (`Logcat`), деградация не диагностируема. | **Мелочь** |

---

## Детальный разбор гипотез и доказательства по исходникам Media3

### 1. Две CacheDataSource над одним SimpleCache: двойная загрузка или блокировка?

* **Гипотеза:** две `CacheDataSource` (плеер и prefetch) обращаются к одному незакэшированному участку. Что происходит — блокировка потоков или двойная загрузка?
* **Вердикт:** **Двойная параллельная сетевая загрузка (Double Download)**. Блокировки нет.
* **Доказательство по исходникам (`CacheDataSource.java`, `SimpleCache.java`, `CachedContent.java`):**
  1. В `VideoCache.install()` плеер получает фабрику с флагом `FLAG_IGNORE_CACHE_ON_ERROR`. Флаг `FLAG_BLOCK_ON_CACHE` (равный 1) **не установлен**. В `VideoCache.prefetch()` фабрика создается с флагами по умолчанию (`flags = 0`), где `FLAG_BLOCK_ON_CACHE` также **не установлен**.
  2. При открытии спана `CacheDataSource.openNextSource()` (строки 715–724) проверяет поле `blockOnCache`:
     ```java
     } else if (blockOnCache) {
       try {
         nextSpan = cache.startReadWrite(key, readPosition, bytesRemaining);
       } ...
     } else {
       nextSpan = cache.startReadWriteNonBlocking(key, readPosition, bytesRemaining);
     }
     ```
  3. Поскольку `blockOnCache == false`, вызывается `SimpleCache.startReadWriteNonBlocking()` (строки 350–370). Для незакэшированного диапазона `span.isCached == false` (это дыра / hole span: `span.isHoleSpan() == true`).
  4. Первый вызвавший поток (например, prefetch) выполняет:
     ```java
     CachedContent cachedContent = contentIndex.getOrAdd(key);
     if (cachedContent.lockRange(position, span.length)) {
       return span;
     }
     ```
     Диапазон успешно блокируется в `lockedRanges`, и первый поток начинает загрузку из upstream через `cacheWriteDataSource` (`TeeDataSource`), записывая данные в кэш.
  5. Второй вызвавший поток (плеер) для того же диапазона вызывает `startReadWriteNonBlocking()`. В `CachedContent.lockRange(position, span.length)` (строки 114–118):
     ```java
     for (int i = 0; i < lockedRanges.size(); i++) {
       if (lockedRanges.get(i).intersects(position, length)) {
         return false;
       }
     }
     ```
     Так как диапазон уже заблокирован первым потоком, метод возвращает `false`, а `startReadWriteNonBlocking()` возвращает `null`.
  6. Реакция `CacheDataSource` на `nextSpan == null` при выключенном `blockOnCache` (строки 726–731):
     ```java
     if (nextSpan == null) {
       // The data is locked in the cache, or we're ignoring the cache. Bypass the cache and read from upstream.
       nextDataSource = upstreamDataSource;
       nextDataSpec = requestDataSpec.buildUpon().setPosition(readPosition).setLength(bytesRemaining).build();
     }
     ```
     Поток **не ждет** освобождения блокировки, а переключается на прямое чтение из сети через `upstreamDataSource`.
  7. Каждые 100 КБ (`MIN_READ_BEFORE_CHECKING_CACHE = 100 * 1024`) второй поток перепроверяет кэш (`openNextSource(requestDataSpec, checkCache = true)`). Если первый поток еще не закончил запись спана, второй продолжает тянуть данные напрямую из сети.
* **Итог:** Оба потока параллельно выкачивают из сети одинаковые байты видео, конкурируя за канал связи.

---

### 2. Потокобезопасность prefetch: межпоточный `closeQuietly`

* **Гипотеза:** вызов `active?.let { DataSourceUtil.closeQuietly(it) }` из главного потока во время `read` в корутине потока `Dispatchers.IO` безопасен или ломает контракт `DataSource`?
* **Вердикт:** **Контракт потокобезопасности грубо нарушен. Высокий риск аварийного падения приложения (`FATAL EXCEPTION`).**
* **Доказательство по исходникам (`DataSource.java`, `CacheDataSource.java`, `CachedContent.java`):**
  1. Интерфейс `androidx.media3.datasource.DataSource` **не предоставляет** гарантий потокобезопасности. В аннотациях методов (например, `addTransferListener`) прямо сказано: *"This method is not thread-safe"*.
  2. В `CacheDataSource.java` ни один метод (`open`, `read`, `close`) не синхронизирован. Внутренние поля `requestDataSpec`, `currentDataSpec`, `currentDataSource`, `currentHoleSpan` — обычные не-volatile ссылки.
  3. В `VideoCache.cancel()` из главного потока вызывается `closeQuietly(active)`. Метод `CacheDataSource.close()` (строка 683) выполняет:
     ```java
     requestDataSpec = null;
     actualUri = null;
     readPosition = 0;
     ...
     closeCurrentSource(); // обнуляет currentDataSpec = null и currentDataSource = null
     ```
  4. Одновременно в пуле `Dispatchers.IO` выполняется метод `CacheDataSource.read()`:
     ```java
     DataSpec requestDataSpec = checkNotNull(this.requestDataSpec); // Строка 632 -> NullPointerException!
     DataSpec currentDataSpec = checkNotNull(this.currentDataSpec); // Строка 633 -> NullPointerException!
     ...
     int bytesRead = checkNotNull(currentDataSource).read(buffer, offset, length); // Строка 638 -> NullPointerException!
     ```
  5. Если `closeCurrentSource()` вызывается одновременно из двух потоков (из `cancel()` и из `drain()`), освобождение дыры `cache.releaseHoleSpan(currentHoleSpan)` приводит к вызову `CachedContent.unlockRange(position)`. В строке 136 `CachedContent.java`:
     ```java
     public void unlockRange(long position) {
       for (int i = 0; i < lockedRanges.size(); i++) {
         if (lockedRanges.get(i).position == position) {
           lockedRanges.remove(i);
           return;
         }
       }
       throw new IllegalStateException(); // Строка 136 -> IllegalStateException!
     }
     ```
  6. В `VideoCache.fill()` (строка 110) блок обработки исключений перехватывает **исключительно** `IOException`:
     ```kotlin
     try {
         while (io.isActive && left > 0 && queue.hasNext()) left -= drain(source, queue.next(), buffer, left)
     } catch (e: IOException) {
         Log.i(TAG, "prefetch stopped: ${e.message}")
     }
     ```
     Исключения `NullPointerException` и `IllegalStateException` являются `RuntimeException`. Они пробивают блок `catch`, приводят к падению корутины в `io.launch` и крашат процесс приложения через глобальный `Thread.UncaughtExceptionHandler`.

---

### 3. `VideoCache.clear()` при активном воспроизведении

* **Гипотеза:** `store.release()` и `dir.deleteRecursively()` при работающем плеере. Какое исключение возникнет и перекроет ли его `FLAG_IGNORE_CACHE_ON_ERROR`?
* **Вердикт:** **Выброс `IllegalStateException`. Флаг `FLAG_IGNORE_CACHE_ON_ERROR` исключение НЕ перекрывает. Воспроизведение крашится.**
* **Доказательство по исходникам (`SimpleCache.java`, `CacheDataSource.java`):**
  1. В `SimpleCache.java` состояние освобождения отслеживается флагом `released`. Проверка `checkState(!released)` выполняется во всех операциях (строки 280, 308, 317, 323, 330, 352, 374, 399, 438, 447, 455, 461, 468, 502, 515). Метод `Assertions.checkState` при нарушении условия всегда выбрасывает `java.lang.IllegalStateException`.
  2. Когда плеер пытается запросить следующий сегмент, вызывается `CacheDataSource.open()`. Первая же строчка (строка 587):
     ```java
     actualUri = getRedirectedUriOrDefault(cache, key, requestDataSpec.uri);
     ```
     вызывает `cache.getContentMetadata(key)` -> `checkState(!released)` -> выбрасывается `IllegalStateException("State check failed.")`.
  3. В `CacheDataSource.open()` блок обработки ошибок (строка 618):
     ```java
     } catch (Throwable e) {
       handleBeforeThrow(e);
       throw e;
     }
     ```
     Метод `handleBeforeThrow(Throwable exception)` (строка 866):
     ```java
     private void handleBeforeThrow(Throwable exception) {
       if (isReadingFromCache() || exception instanceof CacheException) {
         seenCacheError = true;
       }
     }
     ```
     - В момент падения чтение из кэша еще не началось (`isReadingFromCache() == false`).
     - `IllegalStateException` **не является** наследником `CacheException` (`CacheException` наследует `IOException`).
     - Следовательно, `seenCacheError` **даже не выставляется в true**!
     - Но главное: даже если бы флаг выставился, конструкция `throw e;` **безусловно пробрасывает исключение наружу** на текущем запросе!
  4. Исключение перехватывается внутренним циклом ExoPlayer (`ExoPlayerImplInternal`), транслируется в `PlaybackException.ERROR_CODE_UNSPECIFIED` и передается в `PlayerListener.onPlayerError()`.
  5. В `PlayerActivity.onPlayerError()` срабатывает ветка `start(episode, position)`, которая повторно использует созданный экземпляр `player`. Но плеер сконфигурирован с `DefaultMediaSourceFactory`, содержащим фабрику с **тем самым уже освобожденным экземпляром `SimpleCache`**. Плеер входит в бесконечный цикл ошибок и показывает `showError()`.

---

### 4. Что именно перекрывает `CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR` и что он скрывает

* **Вердикт:** Флаг защищает **только последующие** запросы и только от ошибок диска/базы кэша, маскируя полную деградацию кэша без логов.
* **Доказательство по исходникам (`CacheDataSource.java`):**
  1. Javadoc константы `FLAG_IGNORE_CACHE_ON_ERROR` (строки 368–372):
     > *"A flag indicating whether the cache is bypassed following any cache related error. If set then cache related exceptions may be thrown for one cycle of open, read and close calls. Subsequent cycles of these calls will then bypass the cache."*
  2. Флаг активируется через `handleBeforeThrow()` только при условиях:
     - Ошибка произошла в момент, когда `currentDataSource == cacheReadDataSource` (поврежден файл спана на диске, `FileNotFoundException`, ошибка I/O чтения файла);
     - Либо брошено исключение класса `CacheException` (ошибка внутренней базы данных или блокировки кэша).
  3. При наступлении этих условий выставляется флаг экземпляра `seenCacheError = true`. Начиная со следующего вызова `open()`, метод `shouldIgnoreCacheForRequest()` возвращает `CACHE_IGNORED_REASON_ERROR`.
  4. После этого данный экземпляр `CacheDataSource` **навсегда отключает кэш**:
     - Чтение идет исключительно из `upstreamDataSource` (сети);
     - Запись в кэш (`cacheWriteDataSource`) полностью блокируется.
  5. **Что он скрывает:** Полный и тихий отказ дискового кэша. Если flash-память ТВ переполнена или SQLite-база повреждена, приложение не падает, но начинает скачивать 100% видеопотока из интернета, генерируя гигабайты паразитного трафика. Из-за отсутствия `EventListener` в коде `VideoCache.kt` этот факт нигде не логируется.

---

### 5. Eviction во время чтения: LeastRecentlyUsedCacheEvictor

* **Гипотеза:** что произойдет, если `LeastRecentlyUsedCacheEvictor` вытеснит спан, который прямо сейчас читает плеер?
* **Вердикт:** **`CacheDataSource` падает с ошибкой ввода-вывода (IO error), прозрачной перезагрузки из сети не происходит.**
* **Доказательство по исходникам (`LeastRecentlyUsedCacheEvictor.java`, `SimpleCache.java`, `FileDataSource.java`):**
  1. В `SimpleCache.java` кэшированные спаны (`span.isCached == true`) **не блокируются от удаления при чтении**. Метод `startReadWriteNonBlocking` выполняет только `touchSpan()`.
  2. При добавлении новых данных `LeastRecentlyUsedCacheEvictor.onSpanAdded()` вызывает `evictCache()`, который без проверки занятости вызывает `cache.removeSpan(leastRecentlyUsed.first())`.
  3. В `CachedContent.removeSpan(span)` (строка 252) вызывается `span.file.delete()`.
  4. На уровне ядра Linux/Android (POSIX): если файл уже был открыт через `RandomAccessFile` в `FileDataSource`, дескриптор остается валидным до вызова `close()`, и текущее чтение блока может завершиться успешно.
  5. Однако:
     - Если вытеснение произошло в зазоре между получением метаданных спана в `SimpleCache` и вызовом `FileDataSource.open()`, открытие бросает `FileNotFoundException` -> `FileDataSourceException`.
     - Если плеер выполняет микроджоггинг/перемотку в пределах того же спана, файл уже удален.
  6. Реакция `CacheDataSource`: метод `handleBeforeThrow()` видит `isReadingFromCache() == true`, выставляет `seenCacheError = true` и **пробрасывает исключение наружу** (`throw e;`). Плеер падает с ошибкой `PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND`. Автоматического переключения на upstream в рамках упавшего запроса архитектурой Media3 не предусмотрено.

---

### 6. Смена размера кэша в рантайме

* **Гипотеза:** `release()` + новый `SimpleCache` на том же каталоге. Законно ли по контракту `SimpleCache` и что происходит с ранее выданными фабриками?
* **Вердикт:** **По контракту lock-файла законно, но ломает все существующие фабрики и плееры. В коде проекта смена размера в рантайме вообще не работает (мертвый код).**
* **Доказательство по исходникам (`SimpleCache.java`, `VideoCache.kt`):**
  1. В `SimpleCache.java` блокировка каталога проверяется в памяти процесса через статический `HashSet<File> lockedCacheDirs`:
     ```java
     private static synchronized boolean lockFolder(File cacheDir) {
       return lockedCacheDirs.add(cacheDir.getAbsoluteFile());
     }
     ```
     При вызове `release()` вызывается `unlockFolder(cacheDir)`. Поэтому создание нового экземпляра `SimpleCache` на том же каталоге после `release()` допустимо и не вызывает `IllegalStateException("Another SimpleCache instance uses the folder")`.
  2. Однако в `VideoCache.kt` смена размера в рантайме **невозможна физически**:
     ```kotlin
     fun install(context: Context, upstreamFactory: DataSource.Factory): DataSource.Factory {
         ...
         val store = synchronized(lock) {
             if (clearing) null else cache ?: create(app)
         } ?: return upstreamFactory
         ...
     }
     ```
     Метод `create(app)` вызывается **только если `cache == null`**. Внутри `create(app)` блок:
     ```kotlin
     cache?.let { current ->
         if (sizeBytes == size) return current
         cancel()
         current.release()
         cache = null
     }
     ```
     является **мертвым кодом**: если `cache != null`, `create()` никогда не вызывается. А в `SettingsActivity` изменение настройки `cache_gb` вообще не дергает `VideoCache`.
  3. Если бы размер кэша изменился принудительно через `release()`, ранее выданная плееру фабрика `CacheDataSource.Factory` осталась бы привязана к мертвому экземпляру `SimpleCache`, что вызвало бы крах плеера (аналогично гипотезе 3).

---

### 7. Prefetch HLS: выбор варианта качества и однократный парсинг

* **Гипотеза:** `variantSegments` берет `playlist.variants.firstOrNull()`. Совпадает ли это с качеством плеера? Последствия однократного парсинга.
* **Вердикт:** **Качество почти никогда не совпадает (качаются не те сегменты). Префетч бесполезен и вредит.**
* **Доказательство по исходникам (`VideoCache.kt`, `HlsMultivariantPlaylist.java`):**
  1. В `VideoCache.kt` (строка 142):
     ```kotlin
     private fun variantSegments(playlist: HlsMultivariantPlaylist, positionMs: Long, depth: Int): List<DataSpec> {
         val variant = playlist.variants.firstOrNull()?.url
         ...
     }
     ```
  2. В мультивариантном плейлисте HLS варианты отсортированы по профилям кодирования CDN (как правило, первый вариант — это 360p или поток с низким битрейтом для мобильных устройств).
  3. Плеер ExoPlayer использует `AdaptiveTrackSelection` и `DefaultTrackSelector`, выбирая вариант по разрешению экрана ТВ и скорости канала (например, 1080p).
  4. Сегменты потоков 360p и 1080p имеют **совершенно разные URI** и разные файлы чанков на сервере CDN.
  5. В результате prefetch выкачивает в кэш сотни мегабайт сегментов 360p. Плеер запрашивает сегменты 1080p, получает 0% кэш-хитов и качает всё заново из сети.
  6. **Однократный парсинг:** `fill()` строит неизменяемый список `specs` один раз при старте задачи. Если пользователь перемотал видео вперед, prefetch продолжает скачивать сегменты из точки старта. Для Live-трансляций список сегментов устаревает мгновенно.

---

### 8. Бюджет префетча: сжигание лимита на кэшированных спанах

* **Гипотеза:** `drain` считает все прочитанные байты, включая попадания в кэш. Сгорает ли бюджет на уже скачанном?
* **Вердикт:** **Подтверждено. Бюджет префетча тратится на чтение локального диска.**
* **Доказательство по исходникам (`VideoCache.kt`, `CacheDataSource.java`):**
  1. В `VideoCache.kt`:
     ```kotlin
     var left = sizeBytes / BUDGET_PART // Например, 2 ГБ для кэша 4 ГБ
     while (io.isActive && left > 0 && queue.hasNext()) left -= drain(source, queue.next(), buffer, left)
     ```
  2. В `drain()`:
     ```kotlin
     val read = source.read(buffer, 0, buffer.size)
     left -= read
     ```
  3. Источник `source` сконфигурирован как `CacheDataSource`. Когда `source.open(spec)` вызывается для сегмента, который уже лежит в кэше, `openNextSource()` открывает `cacheReadDataSource` (`FileDataSource`).
  4. Метод `source.read()` читает данные с диска и возвращает количество прочитанных байт. `drain` списывает это количество из остатка бюджета `left`.
  5. Если в кэше уже есть 2 ГБ видеоданных, `drain` считывает 2 ГБ с flash-диска в пустой массив `buffer`, сжигает весь бюджет `left` до 0 и завершает работу корутины.
  6. **Итог:** Ни одного нового байта из сети не загружено, процессор и flash-память ТВ нагреты бесполезным чтением, prefetch завершился преждевременно.

---

### 9. Каталог кэша в `filesDir` вместо `cacheDir`

* **Гипотеза:** последствия размещения кэша в `filesDir`.
* **Вердикт:** **Нарушение платформенных соглашений Android Storage. Риск переполнения памяти ТВ и недействительность системной очистки кэша.**
* **Доказательство по документации Android и исходникам (`Context.java`, `SimpleCache.java`):**
  1. Каталог `Context.getCacheDir()` очищается операционной системой автоматически при нехватке свободного места на устройстве (`StorageManager`). Каталог `Context.getFilesDir()` защищен от автоочистки OS: Android никогда не удалит из него файлы самостоятельно, даже если свободное место упадет до нуля.
  2. В интерфейсе Android TV (Настройки -> Приложения -> Rezka TV) размер `filesDir/video` отображается в графе **«Данные приложения»**, а не «Кэш». Кнопка системного меню ТВ **«Очистить кэш»** стирает только `cacheDir` и оставляет гигабайты в `filesDir/video` нетронутыми.
  3. На телевизорах TCL RTD284x доступно всего около 1.5–2 ГБ пользовательского пространства. При дефолтном лимите `cache_gb = 4` кэш способен полностью исчерпать физический накопитель ТВ до 0 байт, вызывая крах системных сервисов Android TV.
  4. **Утечка таблиц в SQLite:** при вызове `VideoCache.clear()` удаляется только каталог на диске через `dir?.deleteRecursively()`. Но база метаданных `StandaloneDatabaseProvider` (`exoplayer_internal.db`) хранит таблицы `ExoPlayerCacheIndex<uid>` и `ExoPlayerCacheFileMetadata<uid>`. В классе `SimpleCache` для корректного удаления предусмотрен специальный метод (строка 100):
     ```java
     public static void delete(File cacheDir, @Nullable DatabaseProvider databaseProvider)
     ```
     Поскольку `VideoCache.clear()` не вызывает `SimpleCache.delete(dir, databaseProvider)`, база данных `exoplayer_internal.db` никогда не очищается от старых UID и бесконечно разрастается zombie-таблицами при каждой очистке кэша.

---

### 10. Повторная перекачка при перезапуске на той же позиции

* **Гипотеза:** может ли prefetch по кругу перекачивать уже скачанное при перезапуске?
* **Вердикт:** **Подтверждено для MP4 (всегда с 0 байта), а также для HLS при динамических URL сегментов и в режиме спин-лупа по диску.**
* **Доказательство:**
  1. Для одиночных файлов (MP4) в строке 107 `VideoCache.kt`:
     `val specs = if (url.endsWith(HLS)) segments(url, positionMs) else listOf(DataSpec(Uri.parse(url)))`
     Параметр `positionMs` не преобразуется в байтовое смещение. Создается `DataSpec` с `position = 0`. При каждом перезапуске prefetch перечитывает весь MP4-файл с самого начала.
  2. Для HLS: если у CDN в URL сегментов подставляются динамические GET-параметры (токены доступа, сессии), `CacheKeyFactory.DEFAULT` строит ключ из полного URI (`dataSpec.uri.toString()`). Старые закэшированные сегменты не находятся, и данные скачиваются по сети заново.
  3. Из-за логики в `PlayerActivity.drivePrefetch()` при колебаниях буфера в диапазоне 5–10 секунд (`PREFETCH_LOW_MS` / `PREFETCH_START_MS`) prefetch непрерывно отменяется и запускается вновь, каждый раз заново считывая первые закэшированные сегменты с диска до исчерпания бюджета.

---

## Проверено, всё в порядке

В ходе аудита подтверждена корректность следующих проектных решений:

1. **Использование `StandaloneDatabaseProvider` вместо устаревших индексных файлов:**
   В `VideoCache.kt` передается `StandaloneDatabaseProvider(app)`. Согласно документации Media3 (`androidx.media3.datasource.cache.SimpleCache`), использование SQLite `DatabaseProvider` является рекомендуемым и производительным способом хранения индексов кэша, заменяющим устаревший `legacyIndex`.
2. **Каноническое встраивание `CacheDataSource.Factory` в плеер:**
   Оборачивание `upstreamFactory` в `CacheDataSource.Factory().setCache(store).setUpstreamDataSourceFactory(upstreamFactory)` в методе `VideoCache.install()` полностью соответствует официальному паттерну интеграции Media3 с `DefaultMediaSourceFactory`.
3. **Корректность разрешения относительных URI в плейлистах HLS:**
   Конструкция `URI(playlist.baseUri).resolve(it.url).toString()` в `mediaSegments` и `variantSegments` корректно обрабатывает спецификацию RFC 8216 (HLS) для относительных ссылок на сегменты и вложенные плейлисты.
4. **Очистка ресурсов при уничтожении экрана плеера:**
   В `PlayerActivity.onDestroy()` вызываются `VideoCache.cancel()`, `scope.cancel()` и `player.release()`, что предотвращает утечку тяжелых нативных декодеров ExoPlayer и фоновых корутин плеера.
5. **Размер буфера ввода-вывода префетча:**
   Размер `READ_BUFFER = 64 * 1024` (64 КБ) оптимален для I/O-операций на 32-битном ARM без избыточного давления на Garbage Collector (GC).

---

## Ограничения аудита

1. **Статический анализ:** аудит проведен на основе анализа исходного кода приложения Rezka TV и исходных кодов библиотеки `androidx.media3` версии `1.11.1`.
2. **Аппаратные замеры:** замеры реальной загрузки шины памяти, профилирование кадров (`adb shell dumpsys gfxinfo`) и времени отклика на физическом телевизоре TCL (RTD284x) в рамках данной задачи не выполнялись, так как требовали подключения к физическому ТВ.
3. **Особенности CDN-провайдеров:** структура плейлистов HLS анализировалась по типовым тестовым фикстурам проекта (`stream_plain.json`, `stream_series.json`). Реальные ссылки с зеркал Rezka могут иметь специфические политики TTL токенов в query-параметрах.
