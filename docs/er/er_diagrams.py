"""ER-диаграммы базы подсистемы для раздела 3: обращение и связанные с ним данные, справочники.
Нотация «вороньей лапки»: черта у таблицы означает «один», трезубец «много». Пунктир: связь по
коду без внешнего ключа. Стиль как у плакатов: чёрно-белые SVG, затем PNG в двойном масштабе.

Запуск: python docs/er/er_diagrams.py [папка]  (по умолчанию рядом со скриптом)
Содержание снято с базы стенда 07.11.2026 (миграции V1–V30). Если схема изменится, поправь
таблицы здесь; описание таблиц и колонок в docs/database.md.
"""
import html
import math
import os
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
os.makedirs(OUT, exist_ok=True)
FONT = "Times New Roman, serif"
HEADER, ROW = 30, 21


def esc(text):
    return html.escape(text, quote=False)


class Svg:
    def __init__(self, width, height):
        self.w, self.h, self.items, self.top = width, height, [], []

    def add(self, item, top=False):
        (self.top if top else self.items).append(item)

    def text(self, x, y, value, size=14, weight="normal", anchor="start", top=False):
        self.add(f'<text x="{x}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
                 f'text-anchor="{anchor}" dominant-baseline="middle">{esc(value)}</text>', top)

    def line(self, points, dash=False):
        """Ломаная; пунктир рисуется штрихами: pymupdf не понимает stroke-dasharray."""
        if not dash:
            d = " ".join(("M" if i == 0 else "L") + f"{x:.1f},{y:.1f}" for i, (x, y) in enumerate(points))
            self.add(f'<path d="{d}" fill="none" stroke="#000" stroke-width="1.4"/>')
            return
        parts, drawing, left = [], True, 7.0
        for (x1, y1), (x2, y2) in zip(points, points[1:]):
            length, pos = math.hypot(x2 - x1, y2 - y1), 0.0
            while pos < length:
                step = min(left, length - pos)
                if drawing:
                    a, b = pos / length, (pos + step) / length
                    parts.append(f"M{x1 + (x2 - x1) * a:.1f},{y1 + (y2 - y1) * a:.1f} "
                                 f"L{x1 + (x2 - x1) * b:.1f},{y1 + (y2 - y1) * b:.1f}")
                pos, left = pos + step, left - step
                if left <= 0:
                    drawing = not drawing
                    left = 7.0 if drawing else 5.0
        self.add(f'<path d="{" ".join(parts)}" fill="none" stroke="#000" stroke-width="1.4"/>')

    def save(self, name):
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}">\n'
               f'<rect width="{self.w}" height="{self.h}" fill="#fff"/>\n' + "\n".join(self.items + self.top) + "\n</svg>\n")
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(svg)
        try:
            import pymupdf
            pymupdf.open(path)[0].get_pixmap(matrix=pymupdf.Matrix(2, 2)).save(path[:-4] + ".png")
        except ImportError:
            print("pymupdf не установлен: PNG не сделан, SVG можно открыть браузером")


def entity(svg, x, y, w, name, rows):
    """Таблица: заголовок и строки (ключ, колонки). Возвращает нижнюю границу."""
    h = HEADER + len(rows) * ROW + 8
    svg.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="#fff" stroke="#000" stroke-width="1.6"/>')
    svg.add(f'<rect x="{x}" y="{y}" width="{w}" height="{HEADER}" fill="#e6e6e6" stroke="#000" stroke-width="1.6"/>')
    svg.text(x + w / 2, y + HEADER / 2 + 1, name, 15, "bold", "middle")
    for i, (key, columns) in enumerate(rows):
        cy = y + HEADER + 4 + ROW * i + ROW / 2
        if key:
            svg.text(x + 8, cy, key, 12, "bold")
        svg.text(x + 50, cy, columns, 14)
    return y + h


def relation(svg, points, dash=False, label=None, label_at=None):
    """Связь «один ко многим»: первая точка у таблицы «один», последняя у таблицы «много»."""
    svg.line(points, dash)
    (x0, y0), (x1, y1) = points[0], points[1]
    length = math.hypot(x1 - x0, y1 - y0)
    ux, uy = (x1 - x0) / length, (y1 - y0) / length
    bx, by = x0 + ux * 9, y0 + uy * 9
    svg.add(f'<path d="M{bx - uy * 7:.1f},{by + ux * 7:.1f} L{bx + uy * 7:.1f},{by - ux * 7:.1f}" '
            f'stroke="#000" stroke-width="1.6"/>', True)
    (x2, y2), (x3, y3) = points[-2], points[-1]
    length = math.hypot(x3 - x2, y3 - y2)
    ux, uy = (x3 - x2) / length, (y3 - y2) / length
    fx, fy = x3 - ux * 14, y3 - uy * 14
    svg.add(f'<path d="M{fx:.1f},{fy:.1f} L{x3 + uy * 8:.1f},{y3 - ux * 8:.1f} M{fx:.1f},{fy:.1f} '
            f'L{x3 - uy * 8:.1f},{y3 + ux * 8:.1f}" fill="none" stroke="#000" stroke-width="1.4"/>', True)
    if label:
        svg.text(label_at[0], label_at[1], label, 13, top=True)


def legend(svg, y):
    svg.text(20, y, "Обозначения: PK первичный ключ, FK внешний ключ, UK уникальный ключ; черта у таблицы: «один», "
                    "трезубец: «много»; пунктир: связь по коду без внешнего ключа.", 14)


# ---------- 1. обращение и связанные с ним данные ----------
a = Svg(1720, 1000)
entity(a, 640, 40, 260, "department (отдел)", [("PK", "id"), ("UK", "code"), ("", "name, description")])
entity(a, 170, 30, 330, "deadline_norm (норматив срока)", [
    ("PK", "id"), ("UK", "code, valid_from"), ("", "title, term_days, counting"),
    ("", "max_extension_days, condition"), ("", "legal_basis, valid_to")])
entity(a, 600, 260, 340, "appeal (обращение)", [
    ("PK", "id: uuid"), ("UK", "public_number"), ("FK", "category; subcategory"), ("", "status, priority"),
    ("FK", "department_id"), ("", "assigned_subject: исполнитель"), ("", "counterparty_id: клиент из CDI"),
    ("", "customer_name, customer_email"), ("", "contract_*: снимок договора"), ("", "contract_client_role"),
    ("", "contract_link, requested_*"), ("FK", "norm_id"), ("", "legal_deadline, deadline_at"),
    ("", "deadline_extended_days"), ("", "escalation_level, escalated_at"), ("", "outcome, resolved_at"),
    ("FK", "final_category, final_subcategory"), ("", "channel, registered_by"), ("", "demo, anonymized_at"),
    ("", "created_at, updated_at, version")])
entity(a, 40, 230, 330, "appeal_history (ход обращения)", [
    ("PK", "id"), ("FK", "appeal_id"), ("", "action, description"), ("", "status_after"), ("", "actor, created_at")])
entity(a, 40, 410, 330, "appeal_message (переписка)", [
    ("PK", "id"), ("FK", "appeal_id"), ("", "author_type, author_name"), ("", "body, created_at")])
entity(a, 40, 570, 330, "appeal_attachment (файл)", [
    ("PK", "id: uuid"), ("FK", "appeal_id"), ("FK", "message_id"), ("UK", "storage_name"),
    ("", "original_name, content_type"), ("", "size_bytes, uploaded_at")])
entity(a, 40, 770, 330, "appeal_access_log (кто открывал)", [
    ("PK", "id"), ("FK", "appeal_id"), ("", "action, attachment_id"), ("", "viewer_subject, viewer_name"),
    ("", "viewer_role, viewer_department"), ("", "accessed_at")])
entity(a, 1060, 230, 330, "transfer_request (передача)", [
    ("PK", "id: uuid"), ("FK", "appeal_id"), ("FK", "source_department_id"), ("FK", "target_department_id"),
    ("", "reason, status, review_comment"), ("", "requested_by_*, reviewed_by_*"), ("", "created_at, reviewed_at")])
entity(a, 1060, 450, 330, "appeal_signal (сигнал в тексте)", [
    ("PK FK", "appeal_id"), ("PK FK", "signal_code"), ("", "excerpt, detected_at")])
entity(a, 1440, 450, 250, "text_signal (признак)", [
    ("PK", "code"), ("", "label, hint"), ("", "pattern, risk"), ("", "sort_order")])
entity(a, 1060, 590, 330, "assistant_suggestion (подсказка)", [
    ("PK FK", "appeal_id"), ("PK", "kind"), ("", "source, shown_at, shown_to"), ("", "decision, decided_at, decided_by")])
entity(a, 1060, 750, 330, "notification_request (уведомление)", [
    ("PK", "source_event_id"), ("FK", "appeal_id"), ("", "template, requested_at")])

relation(a, [(770, 141), (770, 260)])                                  # отдел ведёт обращения
relation(a, [(500, 110), (560, 110), (560, 280), (600, 280)])          # норматив применён к обращениям
relation(a, [(600, 320), (370, 320)])                                  # история
relation(a, [(600, 470), (370, 470)])                                  # переписка
relation(a, [(600, 650), (370, 650)])                                  # файлы
relation(a, [(200, 532), (200, 570)])                                  # файлы сообщения
relation(a, [(640, 718), (640, 850), (370, 850)])                      # журнал доступа
relation(a, [(940, 320), (1060, 320)])                                 # передачи
relation(a, [(900, 80), (1180, 80), (1180, 230)], label="источник", label_at=(1188, 200))
relation(a, [(900, 120), (1290, 120), (1290, 230)], label="получатель", label_at=(1298, 200))
relation(a, [(940, 500), (1060, 500)])                                 # сигналы
relation(a, [(1440, 500), (1390, 500)])                                # признак
relation(a, [(940, 650), (1060, 650)])                                 # подсказки
relation(a, [(900, 718), (900, 800), (1060, 800)])                     # уведомления
legend(a, 975)
a.save("ER-диаграмма 1 — обращение и связанные с ним данные.svg")


# ---------- 2. справочники: схема формы, нормативы, маршрутизация ----------
b = Svg(1640, 920)
entity(b, 20, 40, 250, "department (отдел)", [("PK", "id"), ("UK", "code"), ("", "name, description")])
entity(b, 330, 40, 270, "routing_rule (правило)", [
    ("PK", "id"), ("FK", "category"), ("", "subcategory"), ("FK", "department_id"), ("", "priority, sla_hours")])
entity(b, 660, 40, 260, "appeal_category (категория)", [("PK", "code"), ("", "label"), ("", "sort_order")])
entity(b, 20, 290, 250, "form_field (поле формы)", [
    ("PK", "code"), ("", "label, input_type"), ("", "hint"), ("", "contract_attribute")])
entity(b, 330, 290, 270, "appeal_type_field", [
    ("PK FK", "category, type_code"), ("PK FK", "field_code"), ("", "required, sort_order")])
entity(b, 660, 290, 260, "appeal_type (тип обращения)", [
    ("PK FK", "category"), ("PK", "code"), ("", "label, sort_order"), ("", "policyholder_only")])
entity(b, 1010, 290, 270, "appeal_type_norm", [
    ("FK", "category, type_code"), ("", "product_code"), ("", "norm_code"), ("", "priority")])
entity(b, 1340, 290, 290, "deadline_norm (норматив)", [
    ("PK", "id"), ("UK", "code, valid_from"), ("", "title, term_days, counting"),
    ("", "max_extension_days, condition"), ("", "legal_basis, valid_to")])
entity(b, 330, 560, 270, "appeal_type_product", [("PK FK", "category, type_code"), ("PK", "product_code")])
entity(b, 660, 560, 260, "answer_template (шаблон)", [
    ("PK", "id"), ("FK", "category, subcategory"), ("", "outcome"), ("", "title, body")])
entity(b, 1010, 560, 300, "appeal (обращение)", [
    ("PK", "id"), ("FK", "category"), ("FK", "final_category, final_subcategory"),
    ("FK", "requested_product_code"), ("", "остальное: на первой диаграмме")])
entity(b, 1360, 560, 240, "insurance_product (вид)", [("PK", "code"), ("", "label, sort_order")])
entity(b, 330, 760, 270, "appeal_type_document", [
    ("PK FK", "category, type_code"), ("PK", "sort_order"), ("", "label")])

relation(b, [(270, 100), (330, 100)])                                  # отдел в правилах
relation(b, [(660, 100), (600, 100)])                                  # категория в правилах
relation(b, [(790, 141), (790, 290)])                                  # типы категории
relation(b, [(270, 340), (330, 340)])                                  # поле в типах
relation(b, [(660, 340), (600, 340)])                                  # поля типа
relation(b, [(920, 340), (1010, 340)])                                 # нормативы типа
relation(b, [(1340, 340), (1280, 340)], dash=True, label="по code", label_at=(1286, 322))
relation(b, [(660, 395), (630, 395), (630, 600), (600, 600)])          # продукты типа
relation(b, [(690, 412), (690, 520), (645, 520), (645, 810), (600, 810)])  # документы типа
relation(b, [(790, 412), (790, 560)])                                  # шаблоны типа
relation(b, [(920, 395), (960, 395), (960, 646), (1010, 646)])          # итоговый тип обращения
b.text(952, 540, "итоговый тип", 13, anchor="end", top=True)
relation(b, [(920, 120), (980, 120), (980, 625), (1010, 625)])         # категория обращения
relation(b, [(1480, 640), (1480, 667), (1310, 667)])                   # вид страхования, если договор не найден
legend(b, 900)
b.save("ER-диаграмма 2 — справочники.svg")
print("ok")
