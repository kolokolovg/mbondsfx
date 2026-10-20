# Инструкции для `:persistence-memory`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Модуль
реализует только application-порт `LedgerRepository`, не меняя доменную семантику и не обещая
сохранность после пересоздания репозитория.

- `commit` должен атомарно проверять версию и точное `before`-состояние, отклонять дубликат
  `(portfolio, operationId)`, а затем публиковать snapshot и полученный из `OperationResult`
  journal record с новой последовательностью.
- Сериализуйте commit по portfolio; чтение возвращает неизменяемые snapshot и journal. Не добавляйте
  часы, ID-генераторы, повторы, сериализацию или decode-слой для уже типизированного состояния.
- Текущая реализация держит полную историю и имеет O(числа операций) копирование/проверку; не
  добавляйте compaction, SQLite или provider-bootstrap в эту фазу.
- JPMS descriptor requires только application (core читается транзитивно), provides
  `LedgerRepositoryFactory` и не экспортирует implementation package.

Проверки — dependency-free Java runner через `JavaExec`:

```bash
./gradlew :persistence-memory:check
./gradlew --offline :persistence-memory:check
```
