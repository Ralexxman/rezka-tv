# Anubis на rezka.ag: что это и как обойти

Проверено 2026-10-30 на живом сайте: клиент без браузера получает вместо страниц заглушку
«Проверяем, что вы не бот!», после решения задачи — обычный HTML (507 КБ) и нормальный JSON
от `ajax/login/`.

## Что происходит

Сайт закрыт прокси [Anubis](https://github.com/TecharoHQ/anubis) v1.27.0: на любой запрос без
`браузерной` cookie отдаётся HTTP 200 со страницей задачи, а не контент. Признаки в теле ответа:

```html
<title>Проверяем, что вы не бот!</title>
<script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast","difficulty":2},
 "challenge":{"id":"<uuid>","method":"fast","randomData":"<128 hex>","difficulty":2,...}}</script>
```

Плюс `Set-Cookie: techaro.lol-anubis-cookie-verification-<hash>=<uuid>` — эту cookie обязательно
вернуть при отправке решения.

## Решение (метод fast/slow)

Найти минимальный `nonce` (десятичная строка), при котором

```text
hex(sha256(randomData + str(nonce))) начинается с difficulty нулей (шестнадцатеричных)
```

При `difficulty = 2` это в среднем 256 попыток, у нас ушло 254 — меньше миллисекунды.
Затем

```text
GET {origin}/.within.website/x/cmd/anubis/api/pass-challenge
    ?id=<challenge.id>&response=<hex>&nonce=<n>
    &redir=<исходный URL, url-encoded>&elapsedTime=<мс>
```

с cookie из первого ответа. Ответ 302 и `Set-Cookie: techaro.lol-anubis-auth-<hash>=<JWT>`;
после этого запросы проходят.

Источник алгоритма — серверная проверка `lib/challenge/proofofwork/proofofwork.go`
(TecharoHQ/anubis) и клиент, который делает ровно это для HDRezka:
[n0madic/go-hdrezka/anubis.go](https://github.com/n0madic/go-hdrezka/blob/master/anubis.go).

## Ловушки

- Honeypot: в заглушке есть ссылка `/.within.website/x/cmd/anubis/api/honeypot/<uuid>/init` —
  переходить по ней нельзя, это пометка «бот».
- Cookie привязана к User-Agent и IP: менять UA между решением и запросами нельзя
  (`metadata.User-Agent` и `X-Real-Ip` лежат внутри challenge).
- Решение одноразовое (`"spent": false`); при истечении срока cookie задачу надо решать заново.
- Методы `sha256`/`argon2id`/`hashx` (WASM) сложнее, но у rezka.ag сейчас `fast`.
- `anubis_base_prefix` и `anubis_public_url` пустые, поэтому путь — от корня домена.

## Замер

`python3` + `requests`, `difficulty 2`: 254 хеша, ~0 мс на ноутбуке; на Cortex-A53 ожидаемо
единицы миллисекунд. Никакого WebView для прохождения проверки не требуется.

## Проверка на живом сайте (30.10.2026)

Скрипт по адресу приложения (UA Chrome, без JS):

1. `GET https://rezka.ag/` → 200, 7 050 байт, `<title>Проверяем, что вы не бот!</title>`, Set-Cookie:
   `techaro.lol-anubis-auth-d82da6a5`, `techaro.lol-anubis-cookie-verification-d82da6a5`.
2. `difficulty 2`, nonce 8, 0 мс → `GET /.within.website/x/cmd/anubis/api/pass-challenge?id=…&response=…&nonce=…&redir=…&elapsedTime=1000`
   с этими cookie → **302** и новая `anubis-auth`.
3. Повтор `GET /` с обновлёнными cookie → **200, 511 691 байт**, настоящий заголовок сайта, задачи нет.
4. `POST /ajax/login/` (`login_name`, `login_password`, `login_not_save`) → **200 JSON**
   `{"success":false,"message":{"email":"Введен неверный email или пароль"}}` — страница входа отвечает как обычно.

Заглушка срабатывает на любом запросе с непустым `User-Agent` (Chrome, Android WebView — без разницы);
без `User-Agent` сайт отдаёт 511 КБ без задачи. Приложение отправляет UA, поэтому и получало заглушку
вместо JSON логина.

Проверять cookie надо на том же UA и IP, где задача получена: заглушка без cookie отвечает на
`pass-challenge` кодом **500**.

