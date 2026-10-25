"""
Резервная копия стенда и проверка восстановления (решение 46).

    python scripts/backup.py            # копия: сначала база, потом файлы вложений
    python scripts/backup.py --verify   # восстановить последнюю копию во временную базу и сверить

Копия кладётся в backups/<дата-время>/: db.dump (pg_dump, формат custom),
attachments.tar.gz (том с файлами) и manifest.json (сколько строк было в таблицах).
Порядок важен: файлы копируются после базы, поэтому у каждой записи о файле в копии базы
файл в архиве есть. Лишний файл в архиве безвреден, недостающий означал бы потерю.

Проверка не трогает рабочую базу: копия восстанавливается в отдельную базу
insurance_appeals_restore, сверяются версия схемы, число строк и наличие файлов, затем
временная база удаляется. Нужен только Python 3 и запущенный стенд.
"""
import json
import subprocess
import sys
import tarfile
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKUPS = ROOT / "backups"
DB, USER, RESTORE_DB = "insurance_appeals", "appeals", "insurance_appeals_restore"
TABLES = ["appeal", "appeal_history", "appeal_message", "appeal_attachment", "transfer_request",
          "outbox_event", "event_audit", "appeal_access_log", "settings_audit"]


def compose(*args, stdin=None, stdout=None):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True, stdin=stdin,
                          stdout=stdout if stdout is not None else subprocess.PIPE, stderr=subprocess.PIPE)


def sql(query, database=DB):
    out = compose("exec", "-T", "postgres", "psql", "-U", USER, "-d", database, "-At", "-c", query).stdout
    return out.decode("utf-8").strip()


def counts(database=DB):
    return {table: int(sql(f"select count(*) from {table}", database)) for table in TABLES}


def schema_version(database=DB):
    return sql("select max(version::int) from flyway_schema_history where success", database)


def backup():
    target = BACKUPS / datetime.now().strftime("%Y%m%d-%H%M%S")
    target.mkdir(parents=True)
    manifest = {"createdAt": datetime.now().isoformat(timespec="seconds"), "schemaVersion": schema_version(),
                "rows": counts()}
    started = time.perf_counter()
    with open(target / "db.dump", "wb") as dump:
        compose("exec", "-T", "postgres", "pg_dump", "-U", USER, "-d", DB, "-Fc", stdout=dump)
    db_seconds = time.perf_counter() - started
    # файлы после базы: всё, на что ссылается копия базы, уже лежит на диске
    with open(target / "attachments.tar.gz", "wb") as archive:
        compose("exec", "-T", "app", "tar", "czf", "-", "-C", "/data/attachments", ".", stdout=archive)
    manifest["seconds"] = {"database": round(db_seconds, 1), "total": round(time.perf_counter() - started, 1)}
    manifest["bytes"] = {name: (target / name).stat().st_size for name in ["db.dump", "attachments.tar.gz"]}
    (target / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Копия {target.relative_to(ROOT)}: база {manifest['bytes']['db.dump'] // 1024} КБ, "
          f"файлы {manifest['bytes']['attachments.tar.gz'] // 1024} КБ, {manifest['seconds']['total']} с")
    return target


def verify():
    copies = sorted(path for path in BACKUPS.glob("*") if (path / "manifest.json").exists())
    if not copies:
        sys.exit("Копий нет: сначала python scripts/backup.py")
    source = copies[-1]
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    compose("exec", "-T", "postgres", "dropdb", "-U", USER, "--if-exists", RESTORE_DB)
    compose("exec", "-T", "postgres", "createdb", "-U", USER, RESTORE_DB)
    try:
        started = time.perf_counter()
        with open(source / "db.dump", "rb") as dump:
            compose("exec", "-T", "postgres", "pg_restore", "-U", USER, "-d", RESTORE_DB, "--no-owner",
                    "--exit-on-error", stdin=dump)
        restore_seconds = round(time.perf_counter() - started, 1)
        restored = counts(RESTORE_DB)
        version = schema_version(RESTORE_DB)
        stored = set(sql("select storage_name from appeal_attachment", RESTORE_DB).split())
    finally:
        compose("exec", "-T", "postgres", "dropdb", "-U", USER, "--if-exists", RESTORE_DB)
    with tarfile.open(source / "attachments.tar.gz") as archive:
        files = {Path(member.name).name for member in archive.getmembers() if member.isfile()}

    missing = sorted(stored - files)
    differs = {table: (manifest["rows"][table], restored[table]) for table in TABLES
               if manifest["rows"][table] != restored[table]}
    print(f"Копия {source.name}: восстановлена за {restore_seconds} с, версия схемы {version} "
          f"(в копии {manifest['schemaVersion']})")
    print("Строк:", ", ".join(f"{table} {restored[table]}" for table in TABLES))
    print(f"Файлов в архиве {len(files)}, записей о файлах {len(stored)}, без файла {len(missing)}")
    if differs:
        # счёт строк делается до pg_dump: на работающем стенде строки могут добавиться между ними
        print("Расходится с подсчётом перед копией (запись шла во время копирования):", differs)
    ok = version == manifest["schemaVersion"] and not missing
    print("Итог:", "восстановление проверено" if ok else "ПРОВЕРКА НЕ ПРОЙДЕНА")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    verify() if "--verify" in sys.argv else backup()
