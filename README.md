FrostByte - Java RAT (Remote Access Trojan)

# Security Demo PoC - Анализ вредоносного мода

⚠️ **ВНИМАНИЕ**: Данный репозиторий содержит вредоносный код. Создан в образовательных целях и для анализа.

## Обнаруженные индикаторы (IoC)

### Сетевые:
- **C2 Сервер**: `185.199.199.145:8787`
- **Cloudflare Tunnel**: `reuters-violations-gender-bias.trycloudflare.com`
- **Протокол**: WebSocket (`/ws/mod/{sessionId}`)
- **Telegram API**: `api.telegram.org`

### Файловая система (Windows):
- Установка: `%LOCALAPPDATA%\Microsoft\Windows\Explorer\ThumbCacheToDelete\cache_XXX\`
- Маскировка: `thumbcache_XX.dat` (на самом деле JAR)
- Автозагрузка: `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\MicrosoftEdgeUpdate_XXX.bat`
- Конфиг: `%APPDATA%\SecurityDemoPoc\config.json`
- Манифест: `%LOCALAPPDATA%\Microsoft\Windows\Explorer\iconcache_48\layout.idx`

## Что делает мод

### Возможности удалённого управления:
1. **Screen Streaming**: Трансляция экрана в реальном времени
2. **Webcam Capture**: Включение и трансляция с веб-камеры
3. **Audio Capture**: Запись микрофона и системного звука
4. **File Browser**: Просмотр, чтение и скачивание файлов
5. **File Drop**: Загрузка и запуск файлов на ПК жертвы
6. **Mouse Control**: Удалённое управление мышью
7. **Troll Feature**: Полноэкранные пугалки со звуком

### Маскировка:
- Имена файлов и папок имитируют системные компоненты Windows
- Использует рефлексию для работы с разными версиями Minecraft
- Поддерживает Fabric и Forge

## Версии Minecraft
- Клиентские моды для Fabric (EnvType.CLIENT)

## Серверная инфраструктура
- **Backend**: Node.js/Python (неизвестно)
- **Панель управления**: Веб-интерфейс с просмотром сессий
- **Авторизация**: По sessionId (хеш от deviceId)

## Доказательства (Personal Log)
- [Дата] Обнаружено подозрительное поведение
- [Дата] Проанализирован код
- [Дата] Переустановлена система

## Контакт для исследователей
- **Telegram**: @freezemethod 
  
