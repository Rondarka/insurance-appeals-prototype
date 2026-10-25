package ru.mtuci.appeals.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Справочник формы читается целиком одним запросом и только на чтение, поэтому без
 * JPA-сущностей: пять связанных таблиц с составными ключами проще прочитать SQL.
 */
@Repository
public class FormSchemaRepository {

    /** Одна строка: одно поле типа обращения; у типа без полей поля пустые. */
    public record Row(
            String categoryCode,
            String categoryLabel,
            String typeCode,
            String typeLabel,
            Boolean policyholderOnly,
            String fieldCode,
            String fieldLabel,
            String inputType,
            String hint,
            String contractAttribute,
            Boolean required
    ) {
    }

    private final JdbcClient jdbc;

    public FormSchemaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Документ, который стоит приложить к обращению этого типа (решение 42). */
    public record Document(String categoryCode, String typeCode, String label) {
    }

    public List<Document> documents() {
        return jdbc.sql("""
                        select category as category_code, type_code, label
                        from appeal_type_document
                        order by category, type_code, sort_order
                        """)
                .query(Document.class)
                .list();
    }

    /** Вид страхования из справочника подсистемы. */
    public record Product(String code, String label) {
    }

    public List<Product> products() {
        return jdbc.sql("select code, label from insurance_product order by sort_order")
                .query(Product.class)
                .list();
    }

    /** productCode == null: все типы, иначе только допустимые для продукта договора. */
    public List<Row> rows(String productCode) {
        return jdbc.sql("""
                        select c.code as category_code, c.label as category_label,
                               t.code as type_code, t.label as type_label, t.policyholder_only,
                               f.code as field_code, f.label as field_label,
                               f.input_type, f.hint, f.contract_attribute, tf.required
                        from appeal_category c
                        join appeal_type t on t.category = c.code
                        left join appeal_type_field tf on tf.category = t.category and tf.type_code = t.code
                        left join form_field f on f.code = tf.field_code
                        where cast(:productCode as varchar) is null
                           or exists (select 1 from appeal_type_product p
                                      where p.category = t.category
                                        and p.type_code = t.code
                                        and p.product_code = :productCode)
                        order by c.sort_order, t.sort_order, tf.sort_order
                        """)
                .param("productCode", productCode)
                .query(Row.class)
                .list();
    }
}
