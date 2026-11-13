# lab-contracts

Общие DTO Control, Detector и Runtime генерируются из OpenAPI. `Problem` генерируется
один раз в `lab.contracts.common.model`, `Incident` — в `lab.contracts.detector.model`.
Изменять DTO нужно через спецификации и настройки генерации, а не вручную в `target/`.

## JSON вне контроллера

`ContractJsonCodec` доступен как Spring bean и как самостоятельный класс:

```java
var codec = new ContractJsonCodec();
CreateTrialRequest request = codec.readCreateTrialRequest(json);
String serialized = codec.writeCreateTrialRequest(request);

LogBatch batch = codec.read(inputStream, LogBatch.class);
byte[] bytes = codec.writeLogBatchAsBytes(batch);
```

Универсальные публичные методы `read(String, Class)`, `read(byte[], Class)`,
`read(InputStream, Class)`, `writeAsString` и `writeAsBytes` работают со всеми общими DTO.
Поток закрывает вызывающий код. Для access-логов есть методы чтения и записи JSONL.
`validateJson` проверяет JSON полностью, `validate` — JSON-представление объекта.

## Общие правила JSON

`ContractJsonSupport` задаёт общие настройки парсинга. Автоконфигурация применяет их
к создаваемому Spring Boot `JsonMapper`, поэтому они действуют и для Shop DTO в обоих
режимах, и для других DTO, читаемых стандартным Jackson-конвертером.

- Разрешён стандартный JSON: без комментариев, одинарных кавычек, завершающих запятых,
  некавыченных ключей и значений `NaN`/`Infinity`.
- Ключ объекта нельзя повторять, в том числе во вложенных объектах и `Map`.
- Одно JSON-тело содержит ровно одно корневое значение.
- Неизвестные свойства DTO отклоняются, если сам DTO явно не разрешает их игнорировать.
- Неявное приведение скалярных типов запрещено: число/boolean не становится строкой,
  строка не становится числом/boolean, дробное число не становится целым.
- Число не используется как порядковый номер enum, `null` не становится значением
  по умолчанию для Java-примитива, скаляр не оборачивается автоматически в массив
  и массив не разворачивается в скаляр.

Например, `{"login":123,"password":"secret"}`, `{"quantity":"2"}`,
`{"quantity":2.0}` и `{"login":"first","login":"second"}` отклоняются.
Для поля типа number целочисленная запись `1` допустима. `null` у ссылочного поля
и пустая строка сами по себе не запрещены: их допустимость задаётся схемой или
проверками приложения. Неверный диапазон, например `quantity=-1`, тоже относится
к проверкам конкретного режима Shop, а не к приведению JSON-типов.

Повторяющиеся ключи JSON и повторяющиеся элементы массива — разные случаи:
уникальность элементов проверяется только там, где схема содержит `uniqueItems`.
Эти настройки не проверяют повторяющиеся query-параметры и HTTP-заголовки.

Самостоятельный codec использует те же правила. Конструктор с переданным `JsonMapper`
создаёт его копию с обязательными настройками парсинга; исходный mapper не меняется.
Если приложение вручную объявляет собственный bean `JsonMapper`, оно должно применить
настройки явно: Boot builder customizer не изменяет уже созданные mapper.

```java
@Bean
JsonMapper jsonMapper() {
    return ContractJsonSupport.configure(JsonMapper.builder()).build();
}
```

## Spring MVC

Автоконфигурация добавляет `ContractJsonHttpMessageConverter`. Он применяется только
к общим DTO и JSON media types (`application/json`, `application/*+json`).
Чтение тела и сериализация ответа происходят на этапе HTTP message conversion Spring.
Предварительного чтения тела в advice или фильтре нет.

Строки, `byte[]`, файлы, multipart, коллекции и остальные DTO обслуживаются обычными
конвертерами приложения. Общие настройки JSON действуют при использовании Jackson;
чтение текста, бинарных данных и файлов другими конвертерами не меняется.
Shop DTO не проходят автоматическую проверку общих контрактных схем: их ограничения
зависят от режима Shop. Общий `Problem` проверяется во всех приложениях.

## Проверки

Исходные `contracts/schemas/{common,control,detector,logs,runtime}.yaml` упаковываются
в JAR. Валидатор JSON Schema Draft 2020-12 проверяет их непосредственно, включая
`required`, nullable-поля, `additionalProperties`, вложенные элементы, `uniqueItems`,
форматы UUID/date-time, диапазоны и условия соответствия `Problem.code/status`.
Повторяющиеся ключи и несколько корневых JSON-значений запрещены парсером.

При чтении проверяется исходный JSON до создания DTO: значения по умолчанию и `Set`
не скрывают отсутствующие поля и дубликаты. При записи проверяются фактически
сериализованные данные. Nullable-поля, обязательные по схеме, сохраняются как `null`;
непереданный необязательный `seed` не записывается.

Лимиты из `contracts/semantics.md`: 64 KiB на AccessLog и 8 MiB на LogBatch.
Входящий поток читается максимум до лимита плюс один байт. Внутри пачки размер записи
считается по компактному JSON в UTF-8. Проверка числа записей задаётся YAML-схемой.

Правила жизненного цикла прогона, дедупликация доставки, сопоставление evidence и
вычисление отчётов остаются в Control/Detector, как указано в `contracts/semantics.md`.
Библиотека выбрасывает `ContractValidationException`; общего HTTP-обработчика ошибок нет.

## Проверка сборки

Нужен JDK 25:

```shell
mvn -pl libs/lab-contracts -am test
```
