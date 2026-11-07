"""
Подготовить стенд к показу: защита, снимки экрана, тест удобства (решение 50).

    python scripts/prepare_demo.py              # сначала резервная копия, потом подготовка
    python scripts/prepare_demo.py --no-backup

Демонстрационные данные создаются на дату первого запуска (решение 33) и со временем
стареют: через месяц открытые обращения в очереди выглядят просроченными на недели, хотя
отдел ничего не нарушал. Остатки ручных проверок и экспериментов тоже копятся в очередях
и в журнале событий. Скрипт возвращает стенд в состояние «как будто сегодня»:

1. Резервная копия (scripts/backup.py), чтобы прежнее состояние можно было вернуть.
2. Удаляет все обращения: демонстрационные и созданные вручную, скриптами экспериментов и
   нагрузочным тестом. Вместе с ними уходят записи журнала событий и outbox, которые
   ссылаются на удалённые обращения. Справочники, правила и настройки не трогаются.
3. Перезапускает приложение: генератор заново создаёт 300 обращений за полгода до
   сегодняшнего дня, открытые снова в сроке.
4. От имени клиентов Анны и Петра подаёт через API несколько обращений с датами
   относительно сегодняшнего дня. Они проходят настоящий путь: регистрация, outbox,
   RabbitMQ, маршрутизация в отдел.
5. Печатает итог: сколько обращений, сколько открытых с нарушенным сроком по закону.

Нужны запущенный стенд (docker compose up -d) и Python 3, сторонние пакеты не нужны.
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "experiments"))
import stand  # noqa: E402  общие адреса стенда и вызовы docker compose

TODAY = date.today()


def day(offset):
    return (TODAY + timedelta(days=offset)).isoformat()


# Обращения клиентов из кабинета: договоры из заглушки учётной системы (contracts-stub)
LIVE_APPEALS = [
    ("anna", {"contractId": "22222222-2222-2222-2222-222222222222", "category": "COMPLAINT",
              "subcategory": "PRETENSION",
              "details": {"violationDate": day(-12), "claimAmount": "85000",
                          "desiredOutcome": "Пересчитать и доплатить возмещение"},
              "subject": "Не согласна с суммой выплаты по КАСКО",
              "description": "Выплата занижена: оценка не учла замену фары. Прошу пересчитать."}),
    ("anna", {"contractId": "22222222-2222-2222-2222-222222222222", "category": "CLAIM",
              "subcategory": "AUTO",
              "details": {"incidentDate": day(-3), "policyNumber": "КАСКО-2026-004821",
                          "incidentPlace": "г. Москва, ул. Тверская, д. 7, парковка"},
              "subject": "Поцарапали машину на парковке",
              "description": "Вернулась к машине: царапина на двери и разбито зеркало. "
                             "Виновник не найден, вызывала полицию."}),
    ("anna", {"contractId": "21111111-1111-1111-1111-111111111111", "category": "TECHNICAL",
              "subcategory": "LOGIN",
              "details": {"systemSection": "Личный кабинет", "device": "Android, Chrome",
                          "errorText": "Неверный код подтверждения"},
              "subject": "Не приходит код входа в личный кабинет",
              "description": "Утром пытаюсь войти в личный кабинет, код из SMS не принимается. "
                             "Перезапрашивала три раза."}),
    ("petr", {"contractId": stand.CONTRACT, "category": "CLAIM", "subcategory": "AUTO",
              "details": {"incidentDate": day(-2), "incidentPlace": "г. Москва, ул. Профсоюзная, д. 12, двор"},
              "subject": "ДТП во дворе, повреждён бампер",
              "description": "При выезде с парковки во дворе задел соседний автомобиль. "
                             "Оформили европротокол, фото приложу."}),
]


def sql(query):
    return stand.sql(query)


def login(user):
    form = urllib.parse.urlencode({"client_id": "appeals-experiments", "grant_type": "password",
                                   "username": user, "password": stand.PASSWORD, "scope": "openid"}).encode()
    with urllib.request.urlopen(f"{stand.KEYCLOAK}/realms/insurer/protocol/openid-connect/token", form) as response:
        return json.load(response)["access_token"]


def submit(token, body):
    request = urllib.request.Request(stand.APP + "/api/appeals", method="POST", data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + token,
                                              "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def app_ready():
    try:
        with urllib.request.urlopen(stand.APP + "/", timeout=5) as response:
            return response.status == 200
    except (urllib.error.URLError, ConnectionError, TimeoutError):
        return False


def wait(what, predicate, limit_s):
    took = stand.wait_until(predicate, limit_s, step_s=2)
    if took is None:
        sys.exit(f"Не дождались: {what} за {limit_s} с. Проверьте docker compose logs app.")
    print(f"  {what}: {took} с")


def main():
    if not app_ready():
        sys.exit("Стенд не отвечает на " + stand.APP + ": сначала docker compose up -d")

    if "--no-backup" not in sys.argv:
        print("1. Резервная копия")
        subprocess.run([sys.executable, str(ROOT / "scripts" / "backup.py")], cwd=ROOT, check=True)
    else:
        print("1. Резервная копия пропущена (--no-backup)")

    before = sql("select count(*) filter (where demo), count(*) filter (where not demo) from appeal")
    print(f"2. Удаляем обращения (демонстрационных | остальных: {before.replace('|', ' | ')})")
    sql("delete from appeal")
    sql("delete from staff_member where subject like 'demo-%'")
    sql("delete from event_audit e where not exists (select 1 from appeal a where a.id = e.appeal_id)")
    sql("delete from outbox_event o where not exists (select 1 from appeal a where a.id = o.appeal_id)")

    print("3. Перезапуск приложения, генератор создаёт демонстрационные данные на сегодня")
    stand.compose("restart", "app")
    wait("приложение поднялось", app_ready, 240)
    wait("демонстрационные обращения созданы",
         lambda: int(sql("select count(*) from appeal where demo")) >= 300, 240)

    print("4. Обращения клиентов через API")
    created = []
    tokens = {}
    for user, body in LIVE_APPEALS:
        tokens.setdefault(user, login(user))
        status, payload = submit(tokens[user], body)
        if status >= 300:
            sys.exit(f"  {user}: «{body['subject']}» не принято, код {status}: {payload}")
        created.append(payload["id"])
        print(f"  {user}: {payload.get('publicNumber', payload['id'])} «{body['subject']}»")
    ids = ", ".join(f"'{appeal_id}'" for appeal_id in created)
    wait("все обращения клиентов маршрутизированы",
         lambda: int(sql(f"select count(*) from appeal where id in ({ids}) and status <> 'PENDING_ROUTING'"))
         == len(created), 60)

    total, open_count, overdue = sql("""
        select count(*), count(*) filter (where status not in ('RESOLVED', 'CLOSED')),
               count(*) filter (where status not in ('RESOLVED', 'CLOSED') and legal_deadline < current_date)
        from appeal""").split("|")
    print(f"5. Готово на {TODAY:%d.%m.%Y}: обращений {total}, открытых {open_count}, "
          f"открытых с нарушенным сроком по закону {overdue}")


if __name__ == "__main__":
    main()
