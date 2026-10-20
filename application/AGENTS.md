# Инструкции для `:application`

Общие правила находятся в [корневом `AGENTS.md`](../AGENTS.md); они наследуются здесь. Этот модуль
зависит только от `:core` и содержит use cases и исходящие порты. Не импортируйте сюда `:integrations`
или конкретные реализации repository/provider.

`module-info.java` requires transitive `ru.onlychill.mbondsfx.core` (matching `api :core`) and exports
only `application.port` and `application.service`. Keep implementation providers out of this module;
JUnit tests compile and run on the classpath (`compileTestJava` has `inferModulePath = false`).
`ApplicationArchitectureContractTests` continues to receive its explicit source/build paths from the
Gradle `test` task.

- `application.port.BondReferenceDataSource` — минимальный sparse-контракт; сохраняйте только
  явно сообщённые факты и их source ID/единицы. Не создавайте из них `BondDefinition`.
- `LedgerRepository.commit` — атомарный CAS snapshot+journal contract; сервис строит переход только
  через `Ledger.apply`, передаёт явные portfolio/policy/operation и никогда не повторяет конфликт
  автоматически.
- Новые storage/provider adapters принадлежат отдельным реализациям, не этому модулю. Для нового
  Gradle-модуля при его создании нужны локальные правила, наследующие корневые.

Проверки — стандартный Gradle `test` task на JUnit Jupiter; `check` запускает его автоматически.
`--tests` выбирает тесты по классу/методу, а отчеты доступны в
`application/build/reports/tests/test/` и `application/build/test-results/test/`:

```bash
./gradlew :application:check
./gradlew --offline :application:check
./gradlew :application:test --tests '*ApplicationContractTests'
```
