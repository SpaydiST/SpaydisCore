# Промпт для миграции зависимого плагина на SpaydisCore

```text
Обнови этот плагин под текущую архитектуру SpaydisCore.

1. Полностью удали старые имена, пакеты, команды, permissions, config-ключи,
   звуки, namespace-ы, datapack-пути и PersistentDataContainer-ключи,
   которые относились к удалённой функциональности. Не оставляй aliases,
   переходные классы, прокси, старые namespace-ы или другие compatibility artifacts.
2. Используй только актуальные идентификаторы SpaydisCore:
   - команда администратора: /spaydiscore;
   - единственный короткий alias: /sc;
   - Java-пакет: macronux.spaydiscore;
   - bundled datapack: spaydiscore_datapack;
   - item translations: item.spaydiscore.*;
   - plugin namespace: spaydiscore.
3. Удали интеграции с удалёнными достижениями, рецептами и предметными сценариями.
   Не создавай им новые имена и не сохраняй их ради совместимости.
4. Сохрани только реально используемые независимые интеграции SpaydisCore:
   Limbo, порталы, lifecycle, Netherite allergy, voicechat и SMPRevive.
   Для voicechat и SMPRevive не меняй их собственные plugin IDs и публичные API.
5. Обнови plugin descriptors, reflection/class names, imports, тесты и документацию.
6. Проверь результат поиском по всему репозиторию: старых идентификаторов,
   старых путей и старых команд не должно остаться.

В конце перечисли изменённые файлы и отдельно подтверди, что compatibility artifacts
и устаревшие функции удалены.
```
