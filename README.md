Mengram

Mengram — это модифицированная версия официального клиента Telegram для Android, построенная на актуальной базе исходного кода (DrKLO/Telegram). Основная цель проекта — обеспечить стабильную связь в регионах с нестабильным доступом к сети без необходимости ручного поиска прокси-серверов.
Основные особенности

    MengramProxyEngine: Встроенный движок для автоматического поиска и проверки MTProto-прокси.

    Автоматическая ротация: Система мониторинга состояния сети, которая автоматически переключает прокси-сервер, если текущее соединение прервано или замедлено.

    Поддержка современных протоколов: Парсинг и поддержка FakeTLS (секреты с префиксом ee) для обхода глубокой фильтрации трафика (DPI).

    Многопоточная проверка: Быстрый перебор сотен доступных узлов для выбора наиболее качественного соединения с минимальным пингом.

    Интеграция в процесс авторизации: Движок начинает поиск прокси уже на экране ввода номера телефона, позволяя войти в аккаунт без использования сторонних VPN-сервисов.

Разработка и использование

Код данного проекта предоставляется "как есть". Вы можете свободно использовать, копировать, модифицировать и распространять любые части кода Mengram для своих собственных нужд или форков без каких-либо ограничений.


You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

Проект основан на официальном исходном коде Telegram для Android.
