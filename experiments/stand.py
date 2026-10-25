"""Общее для экспериментов: вход в стенд, запросы к подсистеме, RabbitMQ и docker compose."""
import base64
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

APP = os.environ.get("APP_URL", "http://localhost:18080")
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:18082")
STUB = os.environ.get("STUB_URL", "http://localhost:18081")
RABBIT = os.environ.get("RABBIT_URL", "http://localhost:15672")
USER = os.environ.get("EXPERIMENT_USER", "petr")
# пароли демонстрационного стенда, см. README
PASSWORD = os.environ.get("EXPERIMENT_PASSWORD", "demo")
RABBIT_AUTH = "Basic " + base64.b64encode(b"appeals:appeals_dev").decode()
ROOT = Path(__file__).resolve().parent.parent
# КАСКО Петра в заглушке учётной системы
CONTRACT = "29999999-9999-9999-9999-999999999999"
POLICY = "КАСКО-2026-999999"

_token = {"value": None, "at": 0.0}


def token():
    """Токен живёт 5 минут; берём новый раз в 4 минуты, вход в замер не попадает."""
    if _token["value"] is None or time.monotonic() - _token["at"] > 240:
        form = urllib.parse.urlencode({"client_id": "appeals-experiments", "grant_type": "password",
                                       "username": USER, "password": PASSWORD, "scope": "openid"}).encode()
        with urllib.request.urlopen(f"{KEYCLOAK}/realms/insurer/protocol/openid-connect/token", form) as response:
            _token["value"], _token["at"] = json.load(response)["access_token"], time.monotonic()
    return _token["value"]


def call(method, path, body=None):
    """Запрос к подсистеме: код ответа, тело и время в миллисекундах."""
    request = urllib.request.Request(APP + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": "Bearer " + token(),
                                              "Content-Type": "application/json"})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, payload = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, payload = error.code, json.loads(error.read() or b"{}")
    return status, payload, round((time.perf_counter() - started) * 1000)


def rabbit(method, path, body=None):
    """Веб-API RabbitMQ (виртуальный хост appeals)."""
    request = urllib.request.Request(RABBIT + "/api" + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": RABBIT_AUTH, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def compose(*args):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, capture_output=True)


def sql(query):
    out = compose("exec", "-T", "postgres", "psql", "-U", "appeals", "-d", "insurance_appeals", "-At", "-c", query)
    return out.stdout.decode("utf-8").strip()


def claim(label, degraded=False):
    """Страховой случай по КАСКО Петра: с выбранным договором или по виду и номеру."""
    return {
        "contractId": None if degraded else CONTRACT,
        "productCode": "CASCO" if degraded else None,
        "policyNumber": POLICY if degraded else None,
        "category": "CLAIM", "subcategory": "AUTO",
        "details": {"incidentDate": "2026-10-20", "incidentPlace": "г. Москва, ул. Тверская, д. 1"},
        "subject": f"Эксперимент: {label}",
        "description": "Обращение создано скриптом из experiments/ для замера поведения при отказе.",
    }


def wait_until(predicate, limit_s, step_s=1.0):
    """Сколько секунд прошло до выполнения условия; None, если не дождались."""
    started = time.perf_counter()
    while time.perf_counter() - started < limit_s:
        try:
            if predicate():
                return round(time.perf_counter() - started, 1)
        except (urllib.error.URLError, ConnectionError):
            pass
        time.sleep(step_s)
    return None


def appeal_status(appeal_id):
    return call("GET", f"/api/appeals/{appeal_id}")[1].get("status")


def save(name, result):
    from datetime import datetime
    out = ROOT / "experiments" / "results"
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"{name}-{datetime.now():%Y%m%d-%H%M}.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return path.relative_to(ROOT)
