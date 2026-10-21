# Инструкции для `:persistence-memory`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Модуль
реализует application-порты `LedgerRepository` и `InvestmentAccountRepository`, не меняя доменную
семантику и не обещая сохранность после пересоздания репозитория.

- `commit` должен атомарно проверять версию и точное `before`-состояние, отклонять дубликат
  `(portfolio, operationId)`, а затем публиковать snapshot и полученный из `OperationResult`
  journal record с новой последовательностью.
- Сериализуйте commit по portfolio; чтение возвращает неизменяемые snapshot и journal. Не добавляйте
  часы, ID-генераторы, повторы, сериализацию или decode-слой для уже типизированного состояния.
- Investment-account registry атомарно регистрирует одну неизменяемую запись на portfolio: отсутствие
  означает unknown, точный повтор idempotent, конфликт типа/terms не заменяет прежнюю запись. Каждый
  factory create создает изолированный registry.
- Текущая реализация держит полную историю и имеет O(числа операций) копирование/проверку; не
  добавляйте compaction, SQLite или provider-bootstrap в эту фазу.
- JPMS descriptor requires только application (core читается транзитивно), provides
  `LedgerRepositoryFactory` и `InvestmentAccountRepositoryFactory` и не экспортирует implementation package.

Проверки запускаются стандартным Gradle `test` task с JUnit Jupiter; `check` запускает их
автоматически. Module tests остаются classpath-only и не являются JPMS proof:

```bash
./gradlew :persistence-memory:check
./gradlew --offline :persistence-memory:check
./gradlew :persistence-memory:test --tests '*PersistenceMemoryContractTests'
```
